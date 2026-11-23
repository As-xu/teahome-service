package com.teaplayer.teahome.crawl;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 一轮抓取的编排：建 run/task、翻页、批量详情、逐条入库、汇总状态。 */
@Service
public class CrawlService {

    private static final Logger log = LoggerFactory.getLogger(CrawlService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final ZoneOffset UTC = ZoneOffset.UTC;

    public record RunOutcome(long runId, String status, int itemsFetched, int itemsUpserted, int failures) {
        static RunOutcome skipped() {
            return new RunOutcome(0, "skipped", 0, 0, 0);
        }
    }

    private final JdbcClient jdbc;
    private final SourceRepository sources;
    private final MacCmsClient client;
    private final ItemWriter writer;
    private final CategoryClassifier classifier;
    private final CrawlProperties props;

    public CrawlService(JdbcClient jdbc, SourceRepository sources, MacCmsClient client,
                        ItemWriter writer, CategoryClassifier classifier, CrawlProperties props) {
        this.jdbc = jdbc;
        this.sources = sources;
        this.client = client;
        this.writer = writer;
        this.classifier = classifier;
        this.props = props;
    }

    public RunOutcome run(String kind, String trigger) {
        if (!props.isEnabled() && !"manual".equals(trigger)) {
            return RunOutcome.skipped();
        }
        classifier.reloadFromDb(jdbc);
        Instant now = Instant.now();
        int windowHours = switch (kind) {
            case "hourly", "manual" -> props.getHourlyWindowHours();
            case "daily" -> props.getDailyWindowHours();
            default -> props.getHourlyWindowHours();
        };
        Instant windowFrom = now.minus(Duration.ofHours(windowHours));
        String dedupeKey = scheduledDedupe(kind, trigger, now);
        Optional<Long> runId = insertRun(kind, windowFrom, now, trigger, dedupeKey);
        if (runId.isEmpty()) {
            log.info("抓取跳过（dedupe 命中） kind={} key={}", kind, dedupeKey);
            return RunOutcome.skipped();
        }
        long id = runId.get();
        long started = System.nanoTime();
        List<SourceRow> rows = sources.enabledSources();
        jdbc.sql("UPDATE teaplayer_crawl_run SET source_total = :total WHERE id = :id")
                .param("total", rows.size())
                .param("id", id)
                .update();

        int sourceOk = 0;
        int sourceFailed = 0;
        int pages = 0;
        int fetched = 0;
        int upserted = 0;
        int details = 0;
        int failures = 0;

        for (SourceRow source : rows) {
            SourceTask task = crawlSource(id, source, kind, windowHours);
            pages += task.pages();
            fetched += task.itemsFetched();
            upserted += task.itemsUpserted();
            details += task.detailsFetched();
            failures += task.failures();
            if ("ok".equals(task.status())) {
                sourceOk++;
            } else {
                sourceFailed++;
            }
        }

        int durationMs = (int) Duration.ofNanos(System.nanoTime() - started).toMillis();
        String status = sourceFailed == 0 ? "ok" : (upserted == 0 ? "failed" : "partial");
        jdbc.sql("""
                        UPDATE teaplayer_crawl_run
                           SET status = :status, finished_at = :finishedAt,
                               source_ok = :sourceOk, source_failed = :sourceFailed,
                               pages = :pages, items_fetched = :fetched, items_upserted = :upserted,
                               details_fetched = :details, failures = :failures,
                               duration_ms = :durationMs
                         WHERE id = :id
                        """)
                .param("status", status)
                .param("finishedAt", ts(Instant.now()))
                .param("sourceOk", sourceOk)
                .param("sourceFailed", sourceFailed)
                .param("pages", pages)
                .param("fetched", fetched)
                .param("upserted", upserted)
                .param("details", details)
                .param("failures", failures)
                .param("durationMs", durationMs)
                .param("id", id)
                .update();
        log.info("抓取完成 run={} kind={} status={} sources={} pages={} items={} upserted={} failures={} {}ms",
                id, kind, status, rows.size(), pages, fetched, upserted, failures, durationMs);
        return new RunOutcome(id, status, fetched, upserted, failures);
    }

    /** 清理过期条目与空 group；单独一轮 run_kind=retention。 */
    public RunOutcome retention() {
        Instant now = Instant.now();
        Optional<Long> runId = insertRun("retention", now.minus(Duration.ofDays(props.getTtlDays())),
                now, "scheduler", "retention:" + now.truncatedTo(ChronoUnit.HOURS));
        if (runId.isEmpty()) {
            return RunOutcome.skipped();
        }
        long id = runId.get();
        long started = System.nanoTime();
        int deleted = writer.purgeExpired(now);
        int durationMs = (int) Duration.ofNanos(System.nanoTime() - started).toMillis();
        jdbc.sql("""
                        UPDATE teaplayer_crawl_run
                           SET status = 'ok', finished_at = :finishedAt, items_skipped = :deleted,
                               duration_ms = :durationMs
                         WHERE id = :id
                        """)
                .param("finishedAt", ts(Instant.now()))
                .param("deleted", deleted)
                .param("durationMs", durationMs)
                .param("id", id)
                .update();
        log.info("过期清理完成 run={} deleted={} {}ms", id, deleted, durationMs);
        return new RunOutcome(id, "ok", 0, 0, deleted);
    }

    private SourceTask crawlSource(long runId, SourceRow source, String kind, int windowHours) {
        long taskId = jdbc.sql("""
                        INSERT INTO teaplayer_crawl_task (run_id, source_id, task_kind, status, started_at)
                        VALUES (:runId, :sourceId, :kind, 'running', now())
                        RETURNING id
                        """)
                .param("runId", runId)
                .param("sourceId", source.id())
                .param("kind", kind)
                .query(Long.class)
                .single();
        long started = System.nanoTime();
        int pages = 0;
        int fetched = 0;
        int upserted = 0;
        int details = 0;
        int failures = 0;
        String status = "ok";
        String errorCode = null;
        String errorDetail = null;

        try {
            SourceSpec spec = source.parsedSpec();
            List<RawItem> listed = new ArrayList<>();
            int page = 1;
            while (page <= props.getMaxPages()) {
                MacCmsPage result = client.list(source, spec, windowHours, page);
                pages++;
                listed.addAll(result.items());
                if (result.items().isEmpty() || result.pageCount() <= page) {
                    break;
                }
                page++;
            }
            fetched = listed.size();

            Set<String> ids = new LinkedHashSet<>();
            for (RawItem item : listed) {
                ids.add(item.id());
            }
            Map<String, RawItem> detailMap = fetchDetails(source, spec, ids);
            details = detailMap.size();

            for (RawItem item : listed) {
                RawItem enriched = detailMap.getOrDefault(item.id(), item);
                try {
                    writer.write(source, enriched);
                    upserted++;
                } catch (RuntimeException e) {
                    failures++;
                    log.warn("条目入库失败 source={} item={} title={} err={}",
                            source.id(), item.id(), item.title(), e.toString());
                }
            }
            if (failures > 0) {
                status = upserted == 0 ? "failed" : "partial";
            }
            if (failures > 0) {
                sources.markFailure(source.id(), "item_failures",
                        failures + " 条写入失败（本轮共 " + fetched + " 条）");
            } else {
                sources.markSuccess(source.id(), (int) Duration.ofNanos(System.nanoTime() - started).toMillis());
            }
        } catch (RuntimeException e) {
            status = "failed";
            errorCode = "source_failed";
            errorDetail = truncate(e.toString(), 500);
            sources.markFailure(source.id(), errorCode, errorDetail);
            log.warn("源抓取失败 source={} err={}", source.id(), e.toString());
        }

        int durationMs = (int) Duration.ofNanos(System.nanoTime() - started).toMillis();
        jdbc.sql("""
                        UPDATE teaplayer_crawl_task
                           SET status = :status, pages_done = :pages, items_fetched = :fetched,
                               items_upserted = :upserted, failures = :failures,
                               finished_at = now(), duration_ms = :durationMs,
                               last_error_code = :errorCode, last_error_detail = :errorDetail
                         WHERE id = :id
                        """)
                .param("status", status)
                .param("pages", pages)
                .param("fetched", fetched)
                .param("upserted", upserted)
                .param("failures", failures)
                .param("durationMs", durationMs)
                .param("errorCode", errorCode)
                .param("errorDetail", errorDetail)
                .param("id", taskId)
                .update();
        return new SourceTask(status, pages, fetched, upserted, details, failures);
    }

    private Map<String, RawItem> fetchDetails(SourceRow source, SourceSpec spec, Set<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, RawItem> out = new HashMap<>();
        List<String> all = new ArrayList<>(ids);
        int batch = Math.max(1, props.getDetailBatchSize());
        for (int from = 0; from < all.size(); from += batch) {
            List<String> slice = all.subList(from, Math.min(all.size(), from + batch));
            try {
                out.putAll(client.details(source, spec, slice));
            } catch (RuntimeException e) {
                log.warn("详情批次失败 source={} from={} size={} err={}",
                        source.id(), from, slice.size(), e.toString());
            }
        }
        return out;
    }

    private Optional<Long> insertRun(String kind, Instant from, Instant to, String trigger, String dedupeKey) {
        return jdbc.sql("""
                        INSERT INTO teaplayer_crawl_run
                            (run_kind, window_from, window_to, scheduled_at, started_at,
                             status, triggered_by, dedupe_key)
                        VALUES
                            (:kind, :from, :to, :now, :now, 'running', :trigger, :dedupeKey)
                        ON CONFLICT (dedupe_key) DO NOTHING
                        RETURNING id
                        """)
                .param("kind", kind)
                .param("from", ts(from))
                .param("to", ts(to))
                .param("now", ts(Instant.now()))
                .param("trigger", trigger)
                .param("dedupeKey", dedupeKey)
                .query(Long.class)
                .optional();
    }

    private static String scheduledDedupe(String kind, String trigger, Instant now) {
        if (!"scheduler".equals(trigger)) {
            return null;
        }
        return switch (kind) {
            case "hourly" -> "hourly:" + now.truncatedTo(ChronoUnit.HOURS);
            case "daily" -> "daily:" + now.atZone(ZONE).toLocalDate();
            case "retention" -> "retention:" + now.truncatedTo(ChronoUnit.HOURS);
            default -> null;
        };
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(UTC);
    }

    private record SourceTask(String status, int pages, int itemsFetched,
                              int itemsUpserted, int detailsFetched, int failures) {
    }
}
