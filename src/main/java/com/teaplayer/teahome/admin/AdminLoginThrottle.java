package com.teaplayer.teahome.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Per-IP login backoff: 5 failures in 5 minutes, then a 60 second lock. */
@Service
@Profile("admin")
public class AdminLoginThrottle {

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final Duration LOCK = Duration.ofSeconds(60);

    private record Bucket(Deque<Instant> failures, Instant lockedUntil) {
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public boolean allowed(String ip) {
        Bucket bucket = buckets.get(key(ip));
        if (bucket == null) {
            return true;
        }
        return bucket.lockedUntil() == null || bucket.lockedUntil().isBefore(Instant.now());
    }

    public void failure(String ip) {
        String key = key(ip);
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(new ArrayDeque<>(), null));
        synchronized (bucket) {
            Instant now = Instant.now();
            while (!bucket.failures().isEmpty() && bucket.failures().peekFirst().isBefore(now.minus(WINDOW))) {
                bucket.failures().pollFirst();
            }
            bucket.failures().addLast(now);
            if (bucket.failures().size() >= MAX_FAILURES) {
                bucket.failures().clear();
                buckets.put(key, new Bucket(new ArrayDeque<>(), now.plus(LOCK)));
            }
        }
        if (buckets.size() > 10_000) {
            Instant cutoff = Instant.now().minus(WINDOW.multipliedBy(2));
            buckets.entrySet().removeIf(e -> e.getValue().lockedUntil() == null
                    || e.getValue().lockedUntil().isBefore(cutoff));
        }
    }

    public void success(String ip) {
        buckets.remove(key(ip));
    }

    private static String key(String ip) {
        return ip == null ? "unknown" : ip;
    }
}
