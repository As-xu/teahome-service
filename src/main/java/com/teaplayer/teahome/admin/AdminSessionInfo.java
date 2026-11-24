package com.teaplayer.teahome.admin;

import java.time.Instant;
import java.util.UUID;

/** One resolved console session, joined with its user row. */
public record AdminSessionInfo(
        UUID id,
        long userId,
        String username,
        String displayName,
        String role,
        String csrfHash,
        Instant expiresAt,
        Instant lastSeenAt,
        Instant sudoUntil) {

    public boolean canWrite() {
        return "owner".equals(role) || "admin".equals(role);
    }

    public boolean sudoValid(Instant now) {
        return sudoUntil != null && sudoUntil.isAfter(now);
    }

    public boolean hasSudo() {
        return sudoValid(Instant.now());
    }
}
