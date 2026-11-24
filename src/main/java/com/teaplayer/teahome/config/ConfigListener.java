package com.teaplayer.teahome.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Dedicated PostgreSQL LISTEN connection, outside the Hikari pool.
 *
 * <p>Only reloads on {@code teaplayer_config} notifications; the scheduled poll in
 * {@link ConfigService} stays as the fallback when this listener cannot connect.</p>
 */
@Component
public class ConfigListener {

    private static final Logger log = LoggerFactory.getLogger(ConfigListener.class);

    private final Environment environment;
    private final ConfigService config;
    private final String role;

    private volatile Thread thread;
    private volatile Connection connection;

    public ConfigListener(Environment environment, ConfigService config,
                          @Value("${TEAHOME_ROLE:api}") String role) {
        this.environment = environment;
        this.config = config;
        this.role = role;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if ("migrate".equals(role) || "crawl".equals(role)) {
            return;
        }
        String url = environment.getProperty("spring.datasource.url");
        if (url == null || !url.startsWith("jdbc:postgresql")) {
            return;
        }
        Thread worker = new Thread(this::listen, "config-listen");
        worker.setDaemon(true);
        thread = worker;
        worker.start();
    }

    private void listen() {
        String url = environment.getProperty("spring.datasource.url");
        String user = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        Properties properties = new Properties();
        if (user != null) {
            properties.setProperty("user", user);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        while (!Thread.currentThread().isInterrupted()) {
            try (Connection conn = DriverManager.getConnection(url, properties)) {
                connection = conn;
                try (Statement statement = conn.createStatement()) {
                    statement.execute("LISTEN teaplayer_config");
                }
                PGConnection pg = conn.unwrap(PGConnection.class);
                log.info("配置 LISTEN 已建立");
                while (!Thread.currentThread().isInterrupted()) {
                    var notifications = pg.getNotifications(1000);
                    if (notifications != null && notifications.length > 0) {
                        log.info("收到配置变更通知：{}", notifications[notifications.length - 1].getParameter());
                        config.reload();
                    }
                }
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                log.warn("配置 LISTEN 断开，5 秒后重连：{}", e.toString());
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @PreDestroy
    void stop() {
        Thread worker = thread;
        if (worker != null) {
            worker.interrupt();
        }
        Connection conn = connection;
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }
}
