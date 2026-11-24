package com.teaplayer.teahome.api.search;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.teaplayer.teahome.api.ApiException;
import com.teaplayer.teahome.api.TtlCache;
import com.teaplayer.teahome.config.ConfigService;
import com.teaplayer.teahome.crawl.CategoryClassifier;
import com.teaplayer.teahome.crawl.MacCmsClient;
import com.teaplayer.teahome.crawl.RawItem;
import com.teaplayer.teahome.crawl.SourceRepository;
import com.teaplayer.teahome.crawl.SourceRow;
import com.teaplayer.teahome.crawl.TitleNormalizer;

import jakarta.annotation.PreDestroy;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> CATEGORIES =
            Set.of("movie", "tv", "anime", "variety", "documentary", "short", "other");
    private static final int MAX_ITEMS = 60;

    private final SourceRepository sources;
    private final MacCmsClient client;
    private final CategoryClassifier classifier;
    private final ConfigService config;
    private final ExecutorService pool = new ThreadPoolExecutor(6, 6, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(12), r -> {
                Thread thread = new Thread(r, "search");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final TtlCache<String, SearchDtos.SearchResponse> cache = new TtlCache<>(600_000L, 200);

    public SearchService(SourceRepository sources, MacCmsClient client, CategoryClassifier classifier,
                         ConfigService config) {
        this.sources = sources;
        this.client = client;
        this.classifier = classifier;
        this.config = config;
    }

    public SearchDtos.SearchResponse search(String query, String category) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 1 || q.length() > 50) {
            throw ApiException.badRequest("bad_query", "q 长度必须在 1 到 50 之间");
        }
        String cat = category == null || category.isBlank() || "all".equalsIgnoreCase(category)
                ? null : category.toLowerCase(Locale.ROOT);
        if (cat != null && !CATEGORIES.contains(cat)) {
            throw ApiException.badRequest("bad_category", "未知分类: " + category);
        }
        String cacheKey = q.toLowerCase(Locale.ROOT) + "|" + (cat == null ? "" : cat);
        SearchDtos.SearchResponse cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<SourceRow> enabled = sources.enabledSources().stream()
                .filter(SourceRow::isMacCms)
                .toList();
        java.time.Duration perSource = config.duration("search.timeout_per_source",
                java.time.Duration.ofMillis(3500));
        List<Callable<SourceResult>> tasks = new ArrayList<>(enabled.size());
        for (SourceRow source : enabled) {
            tasks.add(() -> {
                try {
                    List<RawItem> items = client.search(source, source.parsedSpec(), q, perSource);
                    return new SourceResult(source.id(), items, null);
                } catch (RuntimeException e) {
                    log.debug("搜索源失败 source={} reason={}", source.id(), e.getMessage());
                    return new SourceResult(source.id(), List.of(), code(e));
                }
            });
        }

        List<SearchDtos.Failed> failed = new ArrayList<>();
        Map<String, MutableItem> merged = new LinkedHashMap<>();
        List<Future<SourceResult>> futures = new ArrayList<>(tasks.size());
        java.time.Duration deadlineDuration = config.duration("search.deadline", java.time.Duration.ofSeconds(5));
        long deadline = System.nanoTime() + deadlineDuration.toNanos();
        for (int i = 0; i < tasks.size(); i++) {
            try {
                futures.add(pool.submit(tasks.get(i)));
            } catch (RejectedExecutionException e) {
                futures.add(null);
            }
        }
        for (int i = 0; i < futures.size(); i++) {
            SourceRow source = enabled.get(i);
            Future<SourceResult> future = futures.get(i);
            if (future == null) {
                failed.add(new SearchDtos.Failed(source.id(), "busy"));
                continue;
            }
            SourceResult result;
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    future.cancel(true);
                    failed.add(new SearchDtos.Failed(source.id(), "timeout"));
                    continue;
                }
                result = future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                failed.add(new SearchDtos.Failed(source.id(), "timeout"));
                continue;
            } catch (CancellationException e) {
                failed.add(new SearchDtos.Failed(source.id(), "timeout"));
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.upstream("搜索被中断");
            } catch (Exception e) {
                failed.add(new SearchDtos.Failed(source.id(), "error"));
                continue;
            }
            if (result.error() != null) {
                failed.add(new SearchDtos.Failed(source.id(), result.error()));
                continue;
            }
            for (RawItem raw : result.items()) {
                CategoryClassifier.Result classified = classifier.classify(raw.category());
                if (cat != null && !classified.codes().contains(cat)) {
                    continue;
                }
                String key = TitleNormalizer.mergeKey(raw.title(), raw.year());
                if (key == null || key.isBlank()) {
                    continue;
                }
                if (!merged.containsKey(key) && merged.size() >= MAX_ITEMS) {
                    continue;
                }
                MutableItem item = merged.computeIfAbsent(key, k -> new MutableItem(
                        k, raw.title(), parseYear(raw.year()), classified.codes(), classified.primary(),
                        raw.remark()));
                item.sources.add(new SearchDtos.SourceRef(source.id(), raw.id()));
            }
        }

        List<SearchDtos.Item> items = new ArrayList<>(merged.size());
        for (MutableItem item : merged.values()) {
            items.add(new SearchDtos.Item(item.key, item.title, item.year, item.codes, item.primary,
                    item.remark, List.copyOf(item.sources)));
            if (items.size() >= MAX_ITEMS) {
                break;
            }
        }
        SearchDtos.SearchResponse response = new SearchDtos.SearchResponse(q, cat, items,
                !failed.isEmpty(), failed, OffsetDateTime.now(ZONE).toString());
        if (failed.isEmpty()) {
            cache.put(cacheKey, response);
        }
        return response;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    private static String code(RuntimeException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("timeout") || message.contains("超时")) {
            return "timeout";
        }
        if (message.contains("allow_hosts")) {
            return "blocked_host";
        }
        if (message.contains("HTTP 403")) {
            return "http_403";
        }
        if (message.contains("中断")) {
            return "interrupted";
        }
        return "upstream";
    }

    private static Integer parseYear(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int year = Integer.parseInt(value.trim());
            return year >= 1900 && year <= 2100 ? year : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record SourceResult(String sourceId, List<RawItem> items, String error) {
    }

    private static final class MutableItem {
        private final String key;
        private final String title;
        private final Integer year;
        private final List<String> codes;
        private final String primary;
        private final String remark;
        private final List<SearchDtos.SourceRef> sources = new ArrayList<>();

        private MutableItem(String key, String title, Integer year, List<String> codes,
                            String primary, String remark) {
            this.key = key;
            this.title = title;
            this.year = year;
            this.codes = codes;
            this.primary = primary;
            this.remark = remark;
        }
    }
}
