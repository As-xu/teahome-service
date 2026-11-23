package com.teaplayer.teahome.auth;

import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 令牌校验：哈希查库 + 状态/过期/scope 判定；命中后节流回写 last_used。 */
@Service
public class ApiTokenService {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenService.class);

    private final JdbcClient jdbc;

    public ApiTokenService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TokenPrincipal> authenticate(String rawToken, String remoteIp) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String hash = TokenHasher.sha256Hex(rawToken);
        Optional<TokenPrincipal> found = jdbc.sql("""
                        SELECT id, label, scopes, status, expires_at
                          FROM teaplayer_api_token
                         WHERE token_hash = :hash
                        """)
                .param("hash", hash)
                .query((rs, rowNum) -> new TokenPrincipal(
                        rs.getLong("id"),
                        rs.getString("label"),
                        toArray(rs.getArray("scopes")),
                        rs.getString("status"),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .optional();

        if (found.isEmpty()) {
            return Optional.empty();
        }
        TokenPrincipal principal = found.get();
        if (!principal.isUsableAt(OffsetDateTime.now())) {
            return Optional.empty();
        }
        touch(principal.id(), remoteIp);
        return Optional.of(principal);
    }

    private void touch(long tokenId, String remoteIp) {
        try {
            jdbc.sql("""
                            UPDATE teaplayer_api_token
                               SET last_used_at = now(), last_used_ip = CAST(:ip AS inet)
                             WHERE id = :id
                               AND (last_used_at IS NULL OR last_used_at < now() - interval '60 seconds')
                            """)
                    .param("ip", remoteIp)
                    .param("id", tokenId)
                    .update();
        } catch (DataAccessException e) {
            // 回写失败不影响本次鉴权，最多丢一次使用时间。
            log.debug("更新令牌使用时间失败 tokenId={}", tokenId, e);
        }
    }

    private static String[] toArray(Array sqlArray) {
        if (sqlArray == null) {
            return new String[0];
        }
        try {
            Object value = sqlArray.getArray();
            return value instanceof String[] strings ? strings : new String[0];
        } catch (SQLException e) {
            return new String[0];
        }
    }
}
