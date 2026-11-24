package com.teaplayer.teahome.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 配置轮询兜底：与抓取调度解耦，保证 api 实例（关闭 crawl 调度）
 * 也能在漏掉 NOTIFY 时于 reload-ms 内刷新到最新快照。
 */
@Component
public class ConfigPoller {

    private static final Logger log = LoggerFactory.getLogger(ConfigPoller.class);

    private final ConfigService config;
    private final long reloadMs;
    private final String role;
    private ScheduledExecutorService executor;

    public ConfigPoller(ConfigService config,
                        @Value("${teahome.config.reload-ms:30000}") long reloadMs,
                        @Value("${TEAHOME_ROLE:api}") String role) {
        this.config = config;
        this.reloadMs = Math.max(5_000L, reloadMs);
        this.role = role == null ? "" : role.trim();
    }

    @PostConstruct
    void start() {
        if ("migrate".equalsIgnoreCase(role)) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "config-poller");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                config.reload();
            } catch (RuntimeException e) {
                log.warn("配置轮询刷新失败，保留上一版：{}", e.getMessage());
            }
        }, reloadMs, reloadMs, TimeUnit.MILLISECONDS);
        log.info("配置轮询已启动，间隔 {}ms，role={}", reloadMs, role);
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
