package com.teaplayer.teahome.crawl;

import java.sql.Connection;
import java.sql.SQLException;
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
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.teaplayer.teahome.config.ConfigService;

/** 一轮抓取的编排：建 run/task、翻页、批量详情、逐条入库、汇总状态。 */
@Service
public class CrawlService {

    private static final Logger log = LoggerFactory.getLogger(CrawlService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final Duration STALE_AFTER = Duration.ofHours(3);

    public record RunOutcome(long runId, String status, int itemsFetched, int itemsUpserted, int failures) {
        static RunOutcome skipped(String reason) {
            log.info("抓取跳过：{}", reason);
            return new RunOutcome(0, "skipped", 0, 0, 0);
        }
    }

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final SourceRepository sources;
    private final MacCmsClient client;
    private final ItemWriter writer;
    private final CategoryClassifier classifier;
    private final CrawlProperties props;
    private final ConfigService config;

    public CrawlService(JdbcClient jdbc, DataSource dataSource, SourceRepository sources, MacCmsClient client,
                        ItemWriter writer, CategoryClassifier classifier, CrawlProperties props,
                        ConfigService config) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.sources = sources;
        this.client = client;
        this.writer = writer;
        this.classifier = classifier;
        this.props = props;
        this.config = config;
    }

    public RunOutcome run(String kind, String trigger) {
        if (!props.isEnabled() && !"manual".equals(trigger)) {
            return RunOutcome.skipped("disabled");
        }
        if ("scheduler".equals(trigger) && ("hourly".equals(kind) || "daily".equals(kind))
                && !config.boolValue("crawl." + kind + ".enabled", true)) {
            return RunOutcome.skipped("disabled by config");
        }
        return withLock("crawl", () -> doRun(kind, trigger));
    }

    public RunOutcome retention(String trigger) {
        if (!props.isEnabled() && !"manual".equals(trigger)) {
            return RunOutcome.skipped("disabled");
        }
        return withLock("crawl", () -> doRetention(trigger));
    }

    private RunOutcome doRun(String kind, String trigger) {
        classifier.reloadFromDb(jdbc);
        recoverStaleRuns();
        Instant now = Instant.now();
        int windowHours = switch (kind) {
            case "daily" -> config.intValue("crawl.daily.window_hours", props.getDailyWindowHours());
            default -> config.intValue("crawl.hourly.window_hours", props.getHourlyWindowHours());
        };
        Instant windowFrom = now.minus(Duration.ofHours(windowHours));
        String dedupeKey = scheduledDedupe(kind, trigger, now);
        Optional<Long> runId = insertRun(kind, windowFrom, now, trigger, dedupeKey);
        if (runId.isEmpty()) {
            return RunOutcome.skipped("dedupe " + dedupeKey);
        }
        long id = runId.get();
        long started = System.nanoTime();
        int sourceOk = 0;
        int sourceFailed = 0;
        int pages = 0;
        int fetched = 0;
        int upserted = 0;
        int details = 0;
        int failures = 0;
        String status = "failed";
        String error = null;

        try {
            List<SourceRow> rows = sources.enabledSources();
            jdbc.sql("UPDATE teaplayer_crawl_run SET source_total = :total WHERE id = :id")
                    .param("total", rows.size())
                    .param("id", id)
                    .update();
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
            status = sourceFailed == 0 ? "ok" : (upserted == 0 ? "failed" : "partial");
        } catch (RuntimeException e) {
            error = truncate(e.toString(), 500);
            log.error("抓取 run 异常 run={} kind={}", id, kind, e);
        } finally {
            int durationMs = (int) Duration.ofNanos(System.nanoTime() - started).toMillis();
            jdbc.sql("""
                            UPDATE teaplayer_crawl_run
                               SET status = :status, finished_at = :finishedAt,
                                   source_ok = :sourceOk, source_failed = :sourceFailed,
                                   pages = :pages, items_fetched = :fetched, items_upserted = :upserted,
                                   details_fetched = :details, failures = :failures,
                                   duration_ms = :durationMs,
                                   error_code = :errorCode, error_detail = :error
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
                    .param("errorCode", error == null ? null : "run_failed")
                    .param("error", error)
                    .param("id", id)
                    .update();
            log.info("抓取完成 run={} kind={} status={} sources={} pages={} items={} upserted={} failures={} {}ms",
                    id, kind, status, sourceOk + sourceFailed, pages, fetched, upserted, failures, durationMs);
        }
        return new RunOutcome(id, status, fetched, upserted, failures);
    }

    private RunOutcome doRetention(String trigger) {
        recoverStaleRuns();
        Instant now = Instant.now();
        String dedupeKey = "scheduler".equals(trigger)
                ? "retention:" + now.truncatedTo(ChronoUnit.HOURS) : null;
        Optional<Long> runId = insertRun("retention",
                now.minus(Duration.ofDays(config.intValue("retention.ttl_days", props.getTtlDays()))),
                now, trigger, dedupeKey);
        if (runId.isEmpty()) {
            return RunOutcome.skipped("dedupe retention");
        }
        long id = runId.get();
        long started = System.nanoTime();
        int deleted = 0;
        String status = "ok";
        String error = null;
        try {
            deleted = writer.purgeExpired(now);
        } catch (RuntimeException e) {
            status = "failed";
            error = truncate(e.toString(), 500);
            log.error("过期清理失败 run={}", id, e);
        } finally {
            int durationMs = (int) Duration.ofNanos(System.nanoTime() - started).toMillis();
            jdbc.sql("""
                            UPDATE teaplayer_crawl_run
                               SET status = :status, finished_at = :finishedAt, items_skipped = :deleted,
                                   duration_ms = :durationMs,
                                   error_code = :errorCode, error_detail = :error
                             WHERE id = :id
                            """)
                    .param("status", status)
                    .param("finishedAt", ts(Instant.now()))
                    .param("deleted", deleted)
                    .param("durationMs", durationMs)
                    .param("errorCode", error == null ? null : "retention_failed")
                    .param("error", error)
                    .param("id", id)
                    .update();
            log.info("过期清理完成 run={} status={} deleted={} {}ms", id, status, deleted, durationMs);
        }
        return new RunOutcome(id, status, 0, 0, deleted);
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
        int missing = 0;
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
            DetailResult detail = fetchDetails(source, spec, ids);
            details = detail.found().size();
            missing = detail.missing();
            for (RawItem item : listed) {
                RawItem enriched = detail.found().get(item.id());
                if (enriched == null) {
                    continue; // 详情缺失：绝不用列表页字段覆盖已有条目
                }
                try {
                    writer.write(source, enriched, true);
                    upserted++;
                } catch (RuntimeException e) {
                    failures++;
                    log.warn("条目入库失败 source={} item={} title={} err={}",
                            source.id(), item.id(), item.title(), e.toString());
                }
            }
            if (failures > 0 || missing > 0) {
                status = upserted == 0 ? "failed" : "partial";
            }
            if (failures > 0 || missing > 0) {
                errorCode = missing > 0 ? "detail_missing" : "item_failures";
                errorDetail = truncate(missing + " 条详情缺失，" + failures + " 条写入失败（本轮 " + fetched + " 条）", 500);
                sources.markFailure(source.id(), errorCode, errorDetail);
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
                .param("failures", failures + missing)
                .param("durationMs", durationMs)
                .param("errorCode", errorCode)
                .param("errorDetail", errorDetail)
                .param("id", taskId)
                .update();
        return new SourceTask(status, pages, fetched, upserted, details, failures + missing);
    }

    private DetailResult fetchDetails(SourceRow source, SourceSpec spec, Set<String> ids) {
        if (ids.isEmpty()) {
            return new DetailResult(Map.of(), 0);
        }
        Map<String, RawItem> out = new HashMap<>();
        List<String> all = new ArrayList<>(ids);
        int missing = 0;
        int batch = Math.max(1, config.intValue("crawl.detail_batch_size", props.getDetailBatchSize()));
        for (int from = 0; from < all.size(); from += batch) {
            List<String> slice = all.subList(from, Math.min(all.size(), from + batch));
            try {
                Map<String, RawItem> part = client.details(source, spec, slice);
                out.putAll(part);
                missing += slice.size() - part.size();
            } catch (RuntimeException e) {
                missing += slice.size();
                log.warn("详情批次失败 source={} from={} size={} err={}",
                        source.id(), from, slice.size(), e.toString());
            }
        }
        return new DetailResult(out, missing);
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

    /** 进程崩溃后遗留的 running 记录，超过 3 小时视为失败，避免永久卡住。 */
    private void recoverStaleRuns() {
        Instant cutoff = Instant.now().minus(STALE_AFTER);
        int runs = jdbc.sql("""
                        UPDATE teaplayer_crawl_run
                           SET status = 'failed', finished_at = now(),
                               error_code = COALESCE(error_code, 'stale'),
                               error_detail = COALESCE(error_detail, 'stale run recovered')
                         WHERE status = 'running' AND started_at < :cutoff
                        """)
                .param("cutoff", ts(cutoff))
                .update();
        int tasks = jdbc.sql("""
                        UPDATE teaplayer_crawl_task
                           SET status = 'failed', finished_at = now(),
                               last_error_code = 'stale',
                               last_error_detail = 'stale task recovered'
                         WHERE status = 'running' AND started_at < :cutoff
                        """)
                .param("cutoff", ts(cutoff))
                .update();
        if (runs > 0 || tasks > 0) {
            log.warn("回收 stale 抓取记录 runs={} tasks={}", runs, tasks);
        }
    }

    /** 用同一连接持有会话级 advisory lock，避免手工 CLI 与调度并发。 */
    private RunOutcome withLock(String key, Supplier<RunOutcome> body) {
        try (Connection connection = dataSource.getConnection()) {
            boolean acquired;
            try (var statement = connection.createStatement();
                 var rs = statement.executeQuery("SELECT pg_try_advisory_lock(hashtext('" + key + "'))")) {
                acquired = rs.next() && rs.getBoolean(1);
            }
            if (!acquired) {
                return RunOutcome.skipped("already running " + key);
            }
            try {
                return body.get();
            } finally {
                try (var statement = connection.createStatement()) {
                    statement.execute("SELECT pg_advisory_unlock(hashtext('" + key + "'))");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("抓取锁失败", e);
        }
    }

    private static String scheduledDedupe(String kind, String trigger, Instant now) {
        if (!"scheduler".equals(trigger)) {
            return null;
        }
        return switch (kind) {
            case "hourly" -> "hourly:" + now.truncatedTo(ChronoUnit.HOURS);
            case "daily" -> "daily:" + now.atZone(ZONE).toLocalDate();
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

    private record DetailResult(Map<String, RawItem> found, int missing) {
    }
}
