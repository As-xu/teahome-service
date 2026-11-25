package com.teaplayer.teahome.api.img;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.teaplayer.teahome.config.ConfigService;

/** 按 TTL 与容量上限清理封面缓存；文件与 DB 行同删，避免无限增长。 */
@Component
public class ImageCacheGc {

    private static final Logger log = LoggerFactory.getLogger(ImageCacheGc.class);

    private final JdbcClient jdbc;
    private final ConfigService config;
    private final Path root;

    public ImageCacheGc(JdbcClient jdbc, ConfigService config,
                        @Value("${teahome.image.dir:/data/images}") String root) {
        this.jdbc = jdbc;
        this.config = config;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Scheduled(cron = "0 20 3 * * *", zone = "Asia/Shanghai")
    public void run() {
        int ttlDays = config.intValue("image.cache_ttl_days", 30);
        long maxBytes = config.longValue("image.cache_max_bytes", 20L * 1024 * 1024 * 1024);
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(ttlDays);
        List<Row> rows = jdbc.sql("""
                        SELECT image_key, file_path, bytes, fetched_at
                          FROM teaplayer_image_cache
                         WHERE status = 'ok'
                         ORDER BY fetched_at ASC
                        """)
                .query((rs, n) -> new Row(rs.getString("image_key"), rs.getString("file_path"),
                        rs.getLong("bytes"), rs.getObject("fetched_at", OffsetDateTime.class)))
                .list();
        long total = rows.stream().mapToLong(Row::bytes).sum();
        long target = Math.max(0L, maxBytes - maxBytes / 20);
        int deleted = 0;
        for (Row row : rows) {
            boolean expired = row.fetchedAt() != null && row.fetchedAt().isBefore(cutoff);
            boolean over = total > target;
            if (!expired && !over) {
                continue;
            }
            if (remove(row)) {
                total -= row.bytes();
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("封面缓存清理完成 deleted={} remainingBytes={} maxBytes={} ttlDays={}",
                    deleted, total, maxBytes, ttlDays);
        }
    }

    private boolean remove(Row row) {
        try {
            jdbc.sql("DELETE FROM teaplayer_image_cache WHERE image_key = :key")
                    .param("key", row.key()).update();
        } catch (RuntimeException e) {
            log.warn("删除封面缓存行失败 key={}", row.key());
            return false;
        }
        if (row.filePath() != null && !row.filePath().isBlank()) {
            try {
                Path file = Path.of(row.filePath()).toAbsolutePath().normalize();
                if (file.startsWith(root)) {
                    Files.deleteIfExists(file);
                }
            } catch (IOException | RuntimeException e) {
                log.warn("删除封面文件失败 key={}", row.key());
            }
        }
        return true;
    }

    private record Row(String key, String filePath, long bytes, OffsetDateTime fetchedAt) {
    }
}
