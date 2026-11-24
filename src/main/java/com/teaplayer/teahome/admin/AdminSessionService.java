package com.teaplayer.teahome.admin;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Database-backed opaque admin sessions; only SHA-256 hashes are stored. */
@Service
@Profile("admin")
public class AdminSessionService {

    public record Issued(AdminSessionInfo info, String token, String csrfToken) {
    }

    private final JdbcClient jdbc;
    private final AdminProperties props;
    private final SecureRandom random = new SecureRandom();

    public AdminSessionService(JdbcClient jdbc, AdminProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @org.springframework.transaction.annotation.Transactional
    public Issued issue(long userId, String ip, String userAgent) {
        purgeExpired();
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(props.getAbsoluteHours() * 3600L);
        String token = randomToken();
        String csrf = randomToken();
        // Keep at most maxSessions-1 older sessions for this user.
        jdbc.sql("""
                UPDATE teaplayer_admin_session
                   SET revoked_at = now()
                 WHERE user_id = :userId
                   AND revoked_at IS NULL
                   AND expires_at > now()
                   AND id IN (
                       SELECT id FROM teaplayer_admin_session
                        WHERE user_id = :userId AND revoked_at IS NULL AND expires_at > now()
                        ORDER BY last_seen_at DESC
                        OFFSET :keep
                   )
                """)
                .param("userId", userId)
                .param("keep", Math.max(0, props.getMaxSessions() - 1))
                .update();
        jdbc.sql("""
                INSERT INTO teaplayer_admin_session
                    (user_id, token_hash, csrf_hash, expires_at, ip, user_agent)
                VALUES (:userId, :tokenHash, :csrfHash, :expires,
                        CAST(:ip AS inet), :userAgent)
                """)
                .param("userId", userId)
                .param("tokenHash", AdminHashes.sha256Hex(token))
                .param("csrfHash", AdminHashes.sha256Hex(csrf))
                .param("expires", OffsetDateTime.ofInstant(expires, java.time.ZoneOffset.UTC))
                .param("ip", ip)
                .param("userAgent", userAgent)
                .update();
        AdminSessionInfo info = find(AdminHashes.sha256Hex(token)).orElseThrow();
        return new Issued(info, token, csrf);
    }

    public Optional<AdminSessionInfo> resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String hash = AdminHashes.sha256Hex(rawToken);
        Optional<AdminSessionInfo> found = find(hash);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AdminSessionInfo session = found.get();
        Instant now = Instant.now();
        Instant idleDeadline = session.lastSeenAt().plusSeconds(props.getIdleMinutes() * 60L);
        if (idleDeadline.isBefore(now) || session.expiresAt().isBefore(now)) {
            revoke(hash);
            return Optional.empty();
        }
        if (session.lastSeenAt().isBefore(now.minusSeconds(30))) {
            jdbc.sql("UPDATE teaplayer_admin_session SET last_seen_at = now() WHERE id = :id")
                    .param("id", session.id())
                    .update();
        }
        return Optional.of(session);
    }

    public void markSudo(UUID sessionId) {
        jdbc.sql("""
                UPDATE teaplayer_admin_session
                   SET sudo_until = now() + make_interval(mins => :minutes)
                 WHERE id = :id
                """)
                .param("minutes", props.getSudoMinutes())
                .param("id", sessionId)
                .update();
    }

    public void revoke(String tokenHash) {
        jdbc.sql("UPDATE teaplayer_admin_session SET revoked_at = now() WHERE token_hash = :hash")
                .param("hash", tokenHash)
                .update();
    }

    public void revokeByRaw(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) {
            revoke(AdminHashes.sha256Hex(rawToken));
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public int revokeAllForUser(long userId) {
        return jdbc.sql("""
                UPDATE teaplayer_admin_session
                   SET revoked_at = now()
                 WHERE user_id = :userId AND revoked_at IS NULL
                """).param("userId", userId).update();
    }

    public int purgeExpired() {
        return jdbc.sql("""
                DELETE FROM teaplayer_admin_session
                 WHERE expires_at < now()
                    OR (revoked_at IS NOT NULL AND revoked_at < now() - interval '7 days')
                """).update();
    }

    private Optional<AdminSessionInfo> find(String tokenHash) {
        return jdbc.sql("""
                SELECT s.id, s.user_id, s.csrf_hash, s.expires_at, s.last_seen_at, s.sudo_until,
                       u.username, u.display_name, u.role, u.status
                  FROM teaplayer_admin_session s
                  JOIN teaplayer_admin_user u ON u.id = s.user_id
                 WHERE s.token_hash = :hash
                   AND s.revoked_at IS NULL
                   AND s.expires_at > now()
                   AND u.status = 'active'
                """)
                .param("hash", tokenHash)
                .query((rs, row) -> new AdminSessionInfo(
                        rs.getObject("id", UUID.class),
                        rs.getLong("user_id"),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("role"),
                        rs.getString("csrf_hash"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("last_seen_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("sudo_until", OffsetDateTime.class) == null
                                ? null : rs.getObject("sudo_until", OffsetDateTime.class).toInstant()))
                .optional();
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
