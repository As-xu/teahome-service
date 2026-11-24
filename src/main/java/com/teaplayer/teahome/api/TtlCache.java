package com.teaplayer.teahome.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 极简 TTL 缓存：条目数有上限，读时惰性过期。 */
public class TtlCache<K, V> {

    private record Entry<V>(V value, long expiresAt) {
    }

    private final long defaultTtlMs;
    private final int maxEntries;
    private final Map<K, Entry<V>> map = new ConcurrentHashMap<>();

    public TtlCache(long ttlMs, int maxEntries) {
        this.defaultTtlMs = ttlMs;
        this.maxEntries = maxEntries;
    }

    public V get(K key) {
        Entry<V> entry = map.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expiresAt()) {
            map.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    public void put(K key, V value) {
        put(key, value, defaultTtlMs);
    }

    public void put(K key, V value, long ttlMs) {
        if (map.size() >= maxEntries) {
            long now = System.currentTimeMillis();
            map.entrySet().removeIf(e -> now > e.getValue().expiresAt());
            while (map.size() >= maxEntries) {
                K oldest = null;
                long oldestAt = Long.MAX_VALUE;
                for (Map.Entry<K, Entry<V>> e : map.entrySet()) {
                    if (e.getValue().expiresAt() < oldestAt) {
                        oldestAt = e.getValue().expiresAt();
                        oldest = e.getKey();
                    }
                }
                if (oldest == null) {
                    break;
                }
                map.remove(oldest);
            }
        }
        map.put(key, new Entry<>(value, System.currentTimeMillis() + Math.max(1L, ttlMs)));
    }
}
