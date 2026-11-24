package com.teaplayer.teahome.admin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import com.teaplayer.teahome.config.ConfigService;
import com.teaplayer.teahome.config.SecretBox;

/** Admin-side configuration writes; the token API lives in ConfigController. */
@Service
@Profile("admin")
public class AdminConfigService {

    public record Row(String key, String group, String type, String value, String source,
                      String applyMode, boolean secret, boolean sensitive, String description,
                      long version, boolean editable) {
    }

    private final JdbcClient jdbc;
    private final ConfigService config;
    private final SecretBox secretBox;
    private final AdminAuditService audit;

    public AdminConfigService(JdbcClient jdbc, ConfigService config, SecretBox secretBox,
                              AdminAuditService audit) {
        this.jdbc = jdbc;
        this.config = config;
        this.secretBox = secretBox;
        this.audit = audit;
    }

    public List<Row> list() {
        Map<String, ConfigService.Entry> entries = config.entries();
        List<Row> rows = new ArrayList<>(jdbc.sql("""
                SELECT config_key, group_name, value_type, apply_mode, is_secret, is_sensitive, description
                  FROM teaplayer_config_def
                 WHERE deprecated_at IS NULL
                 ORDER BY group_name, sort_order, config_key
                """).query((rs, n) -> {
                    String key = rs.getString("config_key");
                    ConfigService.Entry entry = entries.get(key);
                    boolean secret = rs.getBoolean("is_secret");
                    boolean sensitive = rs.getBoolean("is_sensitive");
                    String value = entry == null ? null
                            : (secret || sensitive ? "******" : entry.value());
                    String source = entry == null ? "default" : entry.source();
                    long version = entry == null ? 0 : entry.version();
                    String applyMode = rs.getString("apply_mode");
                    return new Row(key, rs.getString("group_name"), rs.getString("value_type"),
                            value, source, applyMode, secret, sensitive,
                            rs.getString("description"), version,
                            !"env".equals(source) && "hot".equals(applyMode));
                }).list());
        rows.sort(Comparator.comparing(Row::group).thenComparing(Row::key));
        return rows;
    }

    @Transactional
    public Row update(String key, String rawValue, String reason, Long expectedVersion,
                      AdminSessionInfo actor, HttpServletRequest request) {
        if (reason == null || reason.isBlank() || reason.length() > 200) {
            throw new IllegalArgumentException("reason is required (max 200)");
        }
        ConfigService.Entry current = config.entries().get(key);
        if (current == null) {
            throw new IllegalArgumentException("unknown config key");
        }
        if ("env".equals(current.source())) {
            throw new IllegalArgumentException("该键由环境变量管理，不能在页面修改");
        }
        if (!"hot".equals(current.applyMode())) {
            throw new IllegalArgumentException("该键需要 next_run 或重启，本页只改 hot 键");
        }
        if (expectedVersion != null && expectedVersion != current.version()) {
            throw new IllegalArgumentException("版本已变化，请刷新后重试");
        }
        SecretBox.Sealed sealed = null;
        String normalized = null;
        if (current.secret()) {
            if (rawValue == null || rawValue.isBlank()) {
                return rowOf(key);
            }
            if (!secretBox.configured()) {
                throw new IllegalArgumentException("TEAHOME_CONFIG_KEYS 未配置，不能写 secret");
            }
            sealed = secretBox.seal(key, rawValue);
        } else {
            normalized = config.validateChange(key, rawValue);
        }
        long nextVersion = jdbc.sql("""
                SELECT COALESCE(MAX(revision_no), 0) + 1
                  FROM teaplayer_config_revision WHERE config_key = :key
                """).param("key", key).query(Long.class).single();
        if (sealed != null) {
            jdbc.sql("""
                    INSERT INTO teaplayer_config_value
                        (config_key, value_json, value_cipher, value_nonce, key_id, value_hint,
                         version, updated_by, updated_at)
                    VALUES (:key, NULL, :cipher, :nonce, :keyId, :hint, :version, :actor, now())
                    ON CONFLICT (config_key) DO UPDATE
                       SET value_json = NULL, value_cipher = EXCLUDED.value_cipher,
                           value_nonce = EXCLUDED.value_nonce, key_id = EXCLUDED.key_id,
                           value_hint = EXCLUDED.value_hint, version = EXCLUDED.version,
                           updated_by = EXCLUDED.updated_by, updated_at = now()
                    """)
                    .param("key", key).param("cipher", sealed.cipher()).param("nonce", sealed.nonce())
                    .param("keyId", sealed.keyId()).param("hint", sealed.hint())
                    .param("version", nextVersion).param("actor", "admin:" + actor.username())
                    .update();
        } else {
            jdbc.sql("""
                    INSERT INTO teaplayer_config_value (config_key, value_json, version, updated_by, updated_at)
                    VALUES (:key, CAST(:value AS jsonb), :version, :actor, now())
                    ON CONFLICT (config_key) DO UPDATE
                       SET value_json = EXCLUDED.value_json, version = EXCLUDED.version,
                           updated_by = EXCLUDED.updated_by, updated_at = now()
                    """)
                    .param("key", key).param("value", normalized)
                    .param("version", nextVersion).param("actor", "admin:" + actor.username())
                    .update();
        }
        jdbc.sql("""
                INSERT INTO teaplayer_config_revision
                    (config_key, revision_no, change_kind, old_value_json, new_value_json,
                     reason, actor_type, actor_id, actor_label, request_id)
                VALUES (:key, :revision, :kind, CAST(:oldValue AS jsonb), CAST(:newValue AS jsonb),
                        :reason, 'admin', :actorId, :actorLabel, :requestId)
                """)
                .param("key", key).param("revision", nextVersion)
                .param("kind", "db".equals(current.source()) ? "update" : "create")
                .param("oldValue", "db".equals(current.source()) && !current.secret()
                        ? current.value() : null)
                .param("newValue", normalized)
                .param("reason", reason)
                .param("actorId", String.valueOf(actor.userId()))
                .param("actorLabel", actor.username())
                .param("requestId", (String) request.getAttribute(AdminAttributes.REQUEST_ID))
                .update();
        audit.ok(actor, "config.update", "config", key,
                current.secret() ? "\"******\"" : jsonValue(current.value()),
                current.secret() ? "\"******\"" : jsonValue(normalized),
                reason, request);
        Runnable refresh = () -> {
            config.reload();
            config.notifyChange(key);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refresh.run();
                }
            });
        } else {
            refresh.run();
        }
        return rowOf(key);
    }

    private Row rowOf(String key) {
        return list().stream().filter(r -> r.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown config key"));
    }

    private static String jsonValue(String raw) {
        if (raw == null) {
            return "null";
        }
        String value = raw.trim();
        if (value.startsWith("{") || value.startsWith("[") || value.startsWith("\"")
                || value.equals("true") || value.equals("false") || value.equals("null")
                || value.matches("-?\\d+(\\.\\d+)?")) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
