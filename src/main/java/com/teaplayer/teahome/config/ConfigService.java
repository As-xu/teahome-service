package com.teaplayer.teahome.config;

import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Runtime configuration: env > database > definition default.
 *
 * <p>The loaded values are exposed as an immutable snapshot. A failed reload keeps the
 * previous snapshot instead of taking the service down.</p>
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);
    private static final String ENV_PREFIX = "TEAHOME_CFG_";
    private static final Pattern MIN = Pattern.compile("\"min\"\\s*:\\s*(-?\\d+)");
    private static final Pattern MAX = Pattern.compile("\"max\"\\s*:\\s*(-?\\d+)");

    private final JdbcClient jdbc;
    private final Environment environment;
    private final SecretBox secretBox;
    private final AtomicLong generation = new AtomicLong();

    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), 0, Instant.EPOCH);

    public ConfigService(JdbcClient jdbc, Environment environment, SecretBox secretBox) {
        this.jdbc = jdbc;
        this.environment = environment;
        this.secretBox = secretBox;
    }

    public record Entry(String key, String group, String type, String value, String source,
                        String applyMode, boolean secret, boolean sensitive,
                        String description, long version) {
    }

    public record Snapshot(Map<String, Entry> entries, Map<String, Row> defs, long generation, Instant loadedAt) {
    }

    private record Row(String key, String group, String type, String defaultValue, String constraints,
                       String applyMode, boolean secret, boolean sensitive, String description,
                       String dbValue, byte[] cipher, byte[] nonce, String keyId, long version) {
    }

    @PostConstruct
    void init() {
        reload();
    }

    @Scheduled(fixedDelayString = "${teahome.config.reload-ms:30000}")
    public void scheduledReload() {
        reload();
    }

    public synchronized void reload() {
        try {
            List<Row> rows = jdbc.sql("""
                    SELECT d.config_key, d.group_name, d.value_type,
                           d.default_value::text AS default_value,
                           d.constraints::text AS constraints,
                           d.apply_mode, d.is_secret, d.is_sensitive, d.description,
                           v.value_json::text AS db_value,
                           v.value_cipher, v.value_nonce, v.key_id,
                           COALESCE(v.version, 0) AS version
                      FROM teaplayer_config_def d
                      LEFT JOIN teaplayer_config_value v ON v.config_key = d.config_key
                     WHERE d.deprecated_at IS NULL
                     ORDER BY d.config_key
                    """).query((rs, i) -> new Row(
                    rs.getString("config_key"),
                    rs.getString("group_name"),
                    rs.getString("value_type"),
                    rs.getString("default_value"),
                    rs.getString("constraints"),
                    rs.getString("apply_mode"),
                    rs.getBoolean("is_secret"),
                    rs.getBoolean("is_sensitive"),
                    rs.getString("description"),
                    rs.getString("db_value"),
                    rs.getBytes("value_cipher"),
                    rs.getBytes("value_nonce"),
                    rs.getString("key_id"),
                    rs.getLong("version"))).list();

            Map<String, Entry> next = new LinkedHashMap<>();
            for (Row row : rows) {
                String envValue = environment.getProperty(envKey(row.key()));
                String raw;
                String source;
                if (envValue != null && !envValue.isBlank()) {
                    raw = envValue;
                    source = "env";
                } else if (row.secret() && row.cipher() != null) {
                    raw = secretBox.open(row.key(), row.keyId(), row.nonce(), row.cipher());
                    source = "db";
                } else if (row.dbValue() != null) {
                    raw = row.dbValue();
                    source = "db";
                } else {
                    raw = row.defaultValue();
                    source = "default";
                }
                String normalized = "secret".equals(row.type()) ? raw : normalize(row, raw);
                next.put(row.key(), new Entry(row.key(), row.group(), row.type(), normalized, source,
                        row.applyMode(), row.secret(), row.sensitive(), row.description(), row.version()));
            }
            Map<String, Row> defs = new LinkedHashMap<>();
            for (Row row : rows) {
                defs.put(row.key(), row);
            }
            checkDrift(defs, next);
            checkCrossKeys(next);
            snapshot = new Snapshot(Map.copyOf(next), Map.copyOf(defs), generation.incrementAndGet(), Instant.now());
            log.info("配置快照已加载：{} 项 generation={}", next.size(), snapshot.generation());
        } catch (RuntimeException e) {
            log.error("配置加载失败，继续使用上一版快照 generation={}", snapshot.generation(), e);
        }
    }

    private static void checkDrift(Map<String, Row> defs, Map<String, Entry> entries) {
        for (Row row : defs.values()) {
            if (row.description() == null || row.description().isBlank()) {
                log.warn("配置定义缺少描述：{}", row.key());
            }
            if (!entries.containsKey(row.key())) {
                log.warn("配置定义没有生效值：{}", row.key());
            }
        }
    }

    private void checkCrossKeys(Map<String, Entry> entries) {
        int feedDefault = intValue(entries, "feed.page_size_default", 24);
        int feedMax = intValue(entries, "feed.page_size_max", 60);
        if (feedDefault > feedMax) {
            throw new IllegalStateException("feed.page_size_default 不能大于 feed.page_size_max");
        }
        Duration deadline = duration(entries, "search.deadline", Duration.ofSeconds(5));
        Duration perSource = duration(entries, "search.timeout_per_source", Duration.ofMillis(3500));
        if (deadline.compareTo(perSource) < 0) {
            throw new IllegalStateException("search.deadline 不能小于 search.timeout_per_source");
        }
        int ttlDays = intValue(entries, "retention.ttl_days", 8);
        int hourly = intValue(entries, "crawl.hourly.window_hours", 3);
        if (hourly > ttlDays * 24) {
            throw new IllegalStateException("crawl.hourly.window_hours 不能超过 TTL");
        }
        long imageMax = longValue(entries, "image.max_bytes", 4L << 20);
        long cacheMax = longValue(entries, "image.cache_max_bytes", 20L << 30);
        if (imageMax > cacheMax) {
            throw new IllegalStateException("image.max_bytes 不能大于 image.cache_max_bytes");
        }
    }

    private static int intValue(Map<String, Entry> entries, String key, int fallback) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(unquote(entry.value()));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long longValue(Map<String, Entry> entries, String key, long fallback) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return fallback;
        }
        try {
            return Long.parseLong(unquote(entry.value()));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static Duration duration(Map<String, Entry> entries, String key, Duration fallback) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return fallback;
        }
        try {
            String value = unquote(entry.value());
            return value.startsWith("P") ? Duration.parse(value) : Duration.ofSeconds(Long.parseLong(value));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public void notifyChange(String key) {
        try {
            jdbc.sql("SELECT pg_notify('teaplayer_config', :payload)")
                    .param("payload", key)
                    .query(String.class)
                    .optional();
        } catch (RuntimeException e) {
            log.warn("pg_notify 失败：{}", e.toString());
        }
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public Map<String, Entry> entries() {
        return snapshot.entries();
    }

    public String string(String key, String fallback) {
        Entry entry = snapshot.entries().get(key);
        if (entry == null || entry.value() == null) {
            return fallback;
        }
        String value = entry.value();
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    public int intValue(String key, int fallback) {
        Entry entry = snapshot.entries().get(key);
        if (entry == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(unquote(entry.value()));
        } catch (NumberFormatException e) {
            log.warn("配置 {} 不是整数：{}", key, entry.value());
            return fallback;
        }
    }

    public long longValue(String key, long fallback) {
        Entry entry = snapshot.entries().get(key);
        if (entry == null) {
            return fallback;
        }
        try {
            return Long.parseLong(unquote(entry.value()));
        } catch (NumberFormatException e) {
            log.warn("配置 {} 不是长整数：{}", key, entry.value());
            return fallback;
        }
    }

    public boolean boolValue(String key, boolean fallback) {
        Entry entry = snapshot.entries().get(key);
        if (entry == null) {
            return fallback;
        }
        String value = unquote(entry.value());
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        return fallback;
    }

    public Duration duration(String key, Duration fallback) {
        Entry entry = snapshot.entries().get(key);
        if (entry == null) {
            return fallback;
        }
        String value = unquote(entry.value());
        try {
            return value.startsWith("P") ? Duration.parse(value) : Duration.ofSeconds(Long.parseLong(value));
        } catch (RuntimeException e) {
            log.warn("配置 {} 不是合法时长：{}", key, value);
            return fallback;
        }
    }

    public String source(String key) {
        Entry entry = snapshot.entries().get(key);
        return entry == null ? "missing" : entry.source();
    }

    public String validate(String key, String value) {
        Entry entry = snapshot.entries().get(key);
        if (entry == null) {
            throw new IllegalArgumentException("unknown config key: " + key);
        }
        Row row = snapshot.defs().get(key);
        if (row == null) {
            throw new IllegalArgumentException("unknown config key: " + key);
        }
        return normalize(row, value);
    }

    private String normalize(Row row, String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        switch (row.type()) {
            case "int", "long", "bytes" -> {
                long parsed = Long.parseLong(unquote(value));
                long min = constraint(row.constraints(), MIN, Long.MIN_VALUE);
                long max = constraint(row.constraints(), MAX, Long.MAX_VALUE);
                if (parsed < min || parsed > max) {
                    throw new IllegalArgumentException(row.key() + " out of range: " + parsed);
                }
                return Long.toString(parsed);
            }
            case "bool" -> {
                String lower = unquote(value).toLowerCase();
                if ("true".equals(lower) || "1".equals(lower)) {
                    return "true";
                }
                if ("false".equals(lower) || "0".equals(lower)) {
                    return "false";
                }
                throw new IllegalArgumentException(row.key() + " is not a boolean: " + value);
            }
            case "duration" -> {
                String unquoted = unquote(value);
                Duration duration = unquoted.startsWith("P")
                        ? Duration.parse(unquoted) : Duration.ofSeconds(Long.parseLong(unquoted));
                return "\"" + duration + "\"";
            }
            case "url" -> {
                String unquoted = unquote(value);
                if (!unquoted.startsWith("http://") && !unquoted.startsWith("https://")) {
                    throw new IllegalArgumentException(row.key() + " is not an http url: " + unquoted);
                }
                return quote(unquoted);
            }
            default -> {
                return value.startsWith("\"") ? value : quote(value);
            }
        }
    }

    private static long constraint(String constraints, Pattern pattern, long fallback) {
        if (constraints == null) {
            return fallback;
        }
        Matcher matcher = pattern.matcher(constraints);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : fallback;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String envKey(String key) {
        return ENV_PREFIX + key.replace('.', '_').replace('-', '_').toUpperCase();
    }
}
