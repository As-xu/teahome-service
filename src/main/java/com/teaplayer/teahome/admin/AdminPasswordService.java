package com.teaplayer.teahome.admin;

import java.util.concurrent.Semaphore;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Argon2id hashing bounded to two concurrent derivations (the host has ~11 GiB RAM). */
@Service
@Profile("admin")
public class AdminPasswordService {

    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 65536, 3);
    private final Semaphore permits = new Semaphore(2);

    public String hash(String raw) {
        permits.acquireUninterruptibly();
        try {
            return encoder.encode(raw);
        } finally {
            permits.release();
        }
    }

    public boolean matches(String raw, String stored) {
        if (raw == null || stored == null) {
            return false;
        }
        permits.acquireUninterruptibly();
        try {
            return encoder.matches(raw, stored);
        } catch (RuntimeException e) {
            return false;
        } finally {
            permits.release();
        }
    }
}
