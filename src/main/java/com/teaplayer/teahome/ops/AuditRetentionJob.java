package com.teaplayer.teahome.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public AuditRetentionJob(JdbcClient jdbc, ConfigService config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    @Scheduled(cron = "0 40 4 * * *", zone = "Asia/Shanghai")
    public void run() {
        int days = config.intValue("logging.audit_retention_days", 180);
        try {
            Long deleted = jdbc.sql("SELECT teaplayer_purge_audit(:days)")
                    .param("days", days)
                    .query(Long.class)
                    .single();
            if (deleted != null && deleted > 0) {
                log.info("审计清理完成 deleted={} retentionDays={}", deleted, days);
            }
        } catch (RuntimeException e) {
            log.warn("审计清理失败 retentionDays={} error={}", days, e.toString());
        }
    }
}
