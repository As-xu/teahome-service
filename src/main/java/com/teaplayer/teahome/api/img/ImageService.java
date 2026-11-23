package com.teaplayer.teahome.api.img;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.teaplayer.teahome.api.ApiException;

@Service
public class ImageService {

    private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");
    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    private final JdbcClient jdbc;
    private final ImageFetcher fetcher;
    private final Path root;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final java.util.concurrent.Semaphore fetchPermits = new java.util.concurrent.Semaphore(4);

    public ImageService(JdbcClient jdbc, ImageFetcher fetcher,
                        @Value("${teahome.image.dir:/data/images}") String root) {
        this.jdbc = jdbc;
        this.fetcher = fetcher;
        this.root = Path.of(root);
    }

    public record Loaded(byte[] bytes, String contentType, String etag) {
    }

    public Loaded load(String key) {
        if (key == null || !KEY.matcher(key.toLowerCase(Locale.ROOT)).matches()) {
            throw ApiException.notFound("封面不存在");
        }
        String imageKey = key.toLowerCase(Locale.ROOT);
        if (failedRecently(imageKey)) {
            throw ApiException.upstream("封面暂不可用");
        }
        Cached cached = cached(imageKey);
        if (cached != null) {
            byte[] bytes = read(Path.of(cached.filePath()));
            if (bytes != null) {
                return new Loaded(bytes, cached.contentType(), cached.etag());
            }
        }
        synchronized (locks.computeIfAbsent(imageKey, k -> new Object())) {
            try {
                Cached again = cached(imageKey);
                if (again != null) {
                    byte[] bytes = read(Path.of(again.filePath()));
                    if (bytes != null) {
                        return new Loaded(bytes, again.contentType(), again.etag());
                    }
                }
                return fetchAndStore(imageKey, again != null ? again : cached);
            } finally {
                locks.remove(imageKey);
            }
        }
    }

    private Loaded fetchAndStore(String key, Cached cached) {
        String sourceUrl = cached == null ? lookupUrl(key) : cached.sourceUrl();
        if (sourceUrl == null) {
            throw ApiException.notFound("封面不存在");
        }
        ImageFetcher.Fetched fetched;
        boolean acquired = false;
        try {
            acquired = fetchPermits.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS);
            if (!acquired) {
                throw ApiException.upstream("封面服务繁忙");
            }
            fetched = fetcher.fetch(sourceUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.upstream("封面服务中断");
        } catch (RuntimeException e) {
            recordFailure(key, sourceUrl, e);
            throw e;
        } finally {
            if (acquired) {
                fetchPermits.release();
            }
        }
        Path target = root.resolve(key);
        Path tmp = null;
        try {
            Files.createDirectories(root);
            tmp = Files.createTempFile(root, key, ".part");
            Files.write(tmp, fetched.bytes());
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw ApiException.upstream("封面写入失败");
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 临时文件清理失败不覆盖主错误
                }
            }
        }
        String host = host(sourceUrl);
        jdbc.sql("""
                        INSERT INTO teaplayer_image_cache
                            (image_key, source_url, source_host, content_sha, bytes, content_type,
                             file_path, etag, status, fetched_at, updated_at)
                        VALUES (:key, :url, :host, :sha, :bytes, :type, :path, :etag, 'ok', now(), now())
                        ON CONFLICT (image_key) DO UPDATE
                           SET source_url = EXCLUDED.source_url,
                               source_host = EXCLUDED.source_host,
                               content_sha = EXCLUDED.content_sha,
                               bytes = EXCLUDED.bytes,
                               content_type = EXCLUDED.content_type,
                               file_path = EXCLUDED.file_path,
                               etag = EXCLUDED.etag,
                               status = 'ok',
                               fail_count = 0,
                               last_error_code = NULL,
                               fetched_at = now(),
                               updated_at = now()
                        """)
                .param("key", key)
                .param("url", sourceUrl)
                .param("host", host)
                .param("sha", fetched.sha256())
                .param("bytes", fetched.bytes().length)
                .param("type", fetched.contentType())
                .param("path", target.toString())
                .param("etag", fetched.sha256())
                .update();
        return new Loaded(fetched.bytes(), fetched.contentType(), fetched.sha256());
    }

    private boolean failedRecently(String key) {
        return jdbc.sql("""
                        SELECT 1
                          FROM teaplayer_image_cache
                         WHERE image_key = :key
                           AND status = 'failed'
                           AND last_error_at > now() - interval '60 seconds'
                        """)
                .param("key", key)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    private void recordFailure(String key, String sourceUrl, RuntimeException error) {
        String code = error instanceof ApiException api ? api.code() : "upstream";
        try {
            jdbc.sql("""
                            INSERT INTO teaplayer_image_cache
                                (image_key, source_url, source_host, bytes, file_path, status,
                                 fail_count, last_error_code, last_error_at, updated_at)
                            VALUES (:key, :url, :host, 0, '', 'failed', 1, :code, now(), now())
                            ON CONFLICT (image_key) DO UPDATE
                               SET source_url = EXCLUDED.source_url,
                                   source_host = EXCLUDED.source_host,
                                   status = 'failed',
                                   fail_count = teaplayer_image_cache.fail_count + 1,
                                   last_error_code = EXCLUDED.last_error_code,
                                   last_error_at = now(),
                                   updated_at = now()
                            """)
                    .param("key", key)
                    .param("url", sourceUrl)
                    .param("host", host(sourceUrl))
                    .param("code", code)
                    .update();
        } catch (RuntimeException e) {
            log.warn("记录封面失败状态失败 key={} code={}", key, code);
        }
    }

    private String lookupUrl(String key) {
        List<String> urls = jdbc.sql("""
                        SELECT cover_url
                          FROM teaplayer_source_item
                         WHERE cover_key = :key AND cover_url IS NOT NULL AND status = 'active'
                         UNION ALL
                        SELECT cover_url
                          FROM teaplayer_title_group
                         WHERE cover_key = :key AND cover_url IS NOT NULL
                         LIMIT 1
                        """)
                .param("key", key)
                .query(String.class)
                .list();
        return urls.isEmpty() ? null : urls.get(0);
    }

    private Cached cached(String key) {
        return jdbc.sql("""
                        SELECT source_url, file_path, content_type, etag
                          FROM teaplayer_image_cache
                         WHERE image_key = :key AND status = 'ok'
                        """)
                .param("key", key)
                .query((rs, n) -> new Cached(rs.getString("source_url"), rs.getString("file_path"),
                        rs.getString("content_type"), rs.getString("etag")))
                .optional()
                .orElse(null);
    }

    private static byte[] read(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String host(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private record Cached(String sourceUrl, String filePath, String contentType, String etag) {
    }
}
