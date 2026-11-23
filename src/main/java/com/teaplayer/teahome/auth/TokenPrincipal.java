package com.teaplayer.teahome.auth;

import java.time.OffsetDateTime;
import java.util.Arrays;

/** 一次请求的令牌主体；只包含鉴权需要的字段。 */
public record TokenPrincipal(
        long id,
        String label,
        String[] scopes,
        String status,
        OffsetDateTime expiresAt) {

    public boolean hasScope(String scope) {
        return scopes != null && Arrays.stream(scopes).anyMatch(scope::equals);
    }

    public boolean isUsableAt(OffsetDateTime now) {
        return "active".equals(status)
                && (expiresAt == null || expiresAt.isAfter(now))
                && (hasScope("read") || hasScope("admin"));
    }
}
