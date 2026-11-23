package com.teaplayer.teahome.crawl;

import java.time.Duration;
import java.time.Instant;

/** 8 天滚动窗口的唯一实现：续期条件与到期时间都从这里出。 */
public final class TtlPolicy {

    private static final Duration MIN_FLOOR = Duration.ofMinutes(5);

    private TtlPolicy() {
    }

    /** min(vod_time + ttl, now + ttl)，并保证严格晚于 first_seen。 */
    public static Instant expiry(Instant vodTime, Instant now, int ttlDays) {
        Instant byVod = vodTime.plus(Duration.ofDays(ttlDays));
        Instant byNow = now.plus(Duration.ofDays(ttlDays));
        Instant expires = byVod.isBefore(byNow) ? byVod : byNow;
        Instant floor = now.plus(MIN_FLOOR);
        return expires.isAfter(floor) ? expires : floor;
    }

    /** 只有 vod_time 前移或内容变化才续期；last_seen 不参与。 */
    public static boolean shouldRenew(Instant newVodTime, Instant oldVodTime, String newHash, String oldHash) {
        if (newVodTime.isAfter(oldVodTime)) {
            return true;
        }
        return oldHash == null || !oldHash.equals(newHash);
    }

    public static Instant renewedExpiry(Instant oldExpires, Instant computed) {
        return oldExpires != null && oldExpires.isAfter(computed) ? oldExpires : computed;
    }
}
