package com.teaplayer.teahome.api.admin;

import com.teaplayer.teahome.api.ApiException;
import com.teaplayer.teahome.auth.TokenAuthFilter;
import com.teaplayer.teahome.auth.TokenPrincipal;
import com.teaplayer.teahome.config.ConfigService;
import com.teaplayer.teahome.config.SecretBox;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** P3 configuration surface. The web management UI arrives in P4. */
@RestController
@RequestMapping("/v1/admin/config")
public class ConfigController {

    private final ConfigService config;
    private final SecretBox secretBox;
    private final JdbcClient jdbc;

    public ConfigController(ConfigService config, SecretBox secretBox, JdbcClient jdbc) {
        this.config = config;
        this.secretBox = secretBox;
        this.jdbc = jdbc;
    }

    public record EntryView(String key, String group, String type, String value, String source,
                            String applyMode, boolean secret, boolean sensitive,
                            String description, long version) {
    }

    public record ListView(List<EntryView> entries, long generation, Instant loadedAt) {
    }

    public record UpdateRequest(String value, String reason, Long expectedVersion) {
    }

    @GetMapping
    public ListView list(@RequestAttribute(name = TokenAuthFilter.ATTRIBUTE, required = false) TokenPrincipal principal) {
        requireRead(principal);
        List<EntryView> entries = config.entries().values().stream()
                .sorted(Comparator.comparing(ConfigService.Entry::key))
                .map(entry -> new EntryView(entry.key(), entry.group(), entry.type(),
                        entry.secret() || entry.sensitive() ? "******" : redact(entry.value()),
                        entry.source(), entry.applyMode(), entry.secret(), entry.sensitive(),
                        entry.description(), entry.version()))
                .toList();
        return new ListView(entries, config.snapshot().generation(), config.snapshot().loadedAt());
    }

    @PutMapping("/{key}")
    @Transactional
    public EntryView update(@PathVariable("key") String key,
                            @RequestBody UpdateRequest request,
                            @RequestAttribute(name = TokenAuthFilter.ATTRIBUTE, required = false) TokenPrincipal principal,
                            @RequestAttribute(name = "teahome.requestId", required = false) String requestId) {
        requireAdmin(principal);
        if (request == null || request.value() == null) {
            throw ApiException.badRequest("bad_value", "value is required");
        }
        if (request.reason() == null || request.reason().isBlank() || request.reason().length() > 200) {
            throw ApiException.badRequest("bad_reason", "reason is required (max 200)");
        }
        ConfigService.Entry current = config.entries().get(key);
        if (current == null) {
            throw ApiException.notFound("unknown config key");
        }
        if ("env".equals(current.source())) {
            throw ApiException.conflict("config is managed by environment");
        }
        if (request.expectedVersion() != null && request.expectedVersion() != current.version()) {
            throw ApiException.conflict("config version changed");
        }
        String normalized;
        SecretBox.Sealed sealed = null;
        if (current.secret()) {
            if (!secretBox.configured()) {
                throw ApiException.badRequest("secret_keyring_missing", "TEAHOME_CONFIG_KEYS is not configured");
            }
            normalized = null;
            sealed = secretBox.seal(key, request.value());
        } else {
            try {
                normalized = config.validate(key, request.value());
            } catch (RuntimeException e) {
                throw ApiException.badRequest("bad_value", e.getMessage());
            }
        }
        long nextVersion = jdbc.sql("""
                SELECT COALESCE(MAX(revision_no), 0) + 1
                  FROM teaplayer_config_revision
                 WHERE config_key = :key
                """).param("key", key).query(Long.class).single();
        if (sealed != null) {
            jdbc.sql("""
                    INSERT INTO teaplayer_config_value
                        (config_key, value_json, value_cipher, value_nonce, key_id, value_hint,
                         version, updated_by, updated_at)
                    VALUES (:key, NULL, :cipher, :nonce, :keyId, :hint, :version, :actor, now())
                    ON CONFLICT (config_key) DO UPDATE
                       SET value_json = NULL,
                           value_cipher = EXCLUDED.value_cipher,
                           value_nonce = EXCLUDED.value_nonce,
                           key_id = EXCLUDED.key_id,
                           value_hint = EXCLUDED.value_hint,
                           version = EXCLUDED.version,
                           updated_by = EXCLUDED.updated_by,
                           updated_at = now()
                    """)
                    .param("key", key)
                    .param("cipher", sealed.cipher())
                    .param("nonce", sealed.nonce())
                    .param("keyId", sealed.keyId())
                    .param("hint", sealed.hint())
                    .param("version", nextVersion)
                    .param("actor", principal.label())
                    .update();
        } else {
            jdbc.sql("""
                    INSERT INTO teaplayer_config_value (config_key, value_json, version, updated_by, updated_at)
                    VALUES (:key, CAST(:value AS jsonb), :version, :actor, now())
                    ON CONFLICT (config_key) DO UPDATE
                       SET value_json = EXCLUDED.value_json,
                           version = EXCLUDED.version,
                           updated_by = EXCLUDED.updated_by,
                           updated_at = now()
                    """)
                    .param("key", key)
                    .param("value", normalized)
                    .param("version", nextVersion)
                    .param("actor", principal.label())
                    .update();
        }

        jdbc.sql("""
                INSERT INTO teaplayer_config_revision
                    (config_key, revision_no, change_kind, old_value_json, new_value_json,
                     reason, actor_type, actor_id, actor_label, request_id)
                VALUES (:key, :revision, :kind, CAST(:oldValue AS jsonb), CAST(:newValue AS jsonb),
                        :reason, 'token', :actorId, :actorLabel, :requestId)
                """)
                .param("key", key)
                .param("revision", nextVersion)
                .param("kind", current.source().equals("db") ? "update" : "create")
                .param("oldValue", !current.secret() && current.source().equals("db") ? current.value() : null)
                .param("newValue", normalized)
                .param("reason", request.reason())
                .param("actorId", "token:" + principal.id())
                .param("actorLabel", principal.label())
                .param("requestId", requestId)
                .update();

        jdbc.sql("""
                INSERT INTO teaplayer_audit_log
                    (actor_type, actor_id, actor_label, action, target_type, target_id,
                     result, "before", "after", changed_keys, reason, request_id)
                VALUES ('token', :actorId, :actorLabel, 'config.update', 'config', :key,
                        'ok', CAST(:before AS jsonb), CAST(:after AS jsonb), ARRAY[:key]::text[], :reason, :requestId)
                """)
                .param("actorId", "token:" + principal.id())
                .param("actorLabel", principal.label())
                .param("key", key)
                .param("before", current.source().equals("db")
                        ? "{\"value\":\"" + (current.secret() ? "******" : current.value()) + "\"}" : null)
                .param("after", current.secret() ? "{\"value\":\"******\"}" : "{\"value\":" + normalized + "}")
                .param("reason", request.reason())
                .param("requestId", requestId)
                .update();

        config.notifyChange(key);
        config.reload();
        ConfigService.Entry updated = config.entries().get(key);
        return new EntryView(updated.key(), updated.group(), updated.type(),
                updated.secret() || updated.sensitive() ? "******" : redact(updated.value()),
                updated.source(), updated.applyMode(), updated.secret(), updated.sensitive(),
                updated.description(), updated.version());
    }

    private static void requireRead(TokenPrincipal principal) {
        if (principal == null || !(principal.hasScope("read") || principal.hasScope("admin"))) {
            throw ApiException.forbidden("read scope required");
        }
    }

    private static void requireAdmin(TokenPrincipal principal) {
        if (principal == null || !principal.hasScope("admin")) {
            throw ApiException.forbidden("admin scope required");
        }
    }

    private static String redact(String value) {
        if (value == null) {
            return null;
        }
        String unquoted = value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
        return unquoted.length() <= 12 ? unquoted : unquoted.substring(0, 12) + "…";
    }
}
