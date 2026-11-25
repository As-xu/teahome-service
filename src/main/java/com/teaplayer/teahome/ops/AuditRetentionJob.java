package com.teaplayer.teahome.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.teaplayer.teahome.config.ConfigService;

/** 每日清理超过保留期的审计行；删除由 SECURITY DEFINER 函数受控执行。 */
@Component
public class AuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionJob.class);

    private final JdbcClient jdbc;
    private final ConfigService config;
    private final boolean opsScheduled;

    public AuditRetentionJob(JdbcClient jdbc, ConfigService config,
                             @Value("${teahome.ops.scheduled:true}") boolean opsScheduled) {
        this.jdbc = jdbc;
        this.config = config;
        this.opsScheduled = opsScheduled;
    }

    public record PurgeResult(long deleted, int retentionDays) {
    }

    @Scheduled(cron = "0 40 4 * * *", zone = "Asia/Shanghai")
    public void scheduled() {
        if (!opsScheduled) {
            return;
        }
        try {
            PurgeResult result = runNow();
            if (result.deleted() > 0) {
                log.info("审计清理完成 deleted={} retentionDays={}",
                        result.deleted(), result.retentionDays());
            }
        } catch (RuntimeException e) {
            log.warn("审计清理失败 retentionDays={} error={}", config.intValue("logging.audit_retention_days", 180),
                    e.toString());
        }
    }

    /** 当前生效的审计保留天数，供管理端展示。 */
    public int retentionDays() {
        return config.intValue("logging.audit_retention_days", 180);
    }

    public PurgeResult runNow() {
        int days = retentionDays();
        Long deleted = jdbc.sql("SELECT teaplayer_purge_audit(:days)")
                .param("days", days)
                .query(Long.class)
                .single();
        return new PurgeResult(deleted == null ? 0L : deleted, days);
    }
}
