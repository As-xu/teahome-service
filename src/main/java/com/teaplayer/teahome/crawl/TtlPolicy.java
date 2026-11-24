package com.teaplayer.teahome.crawl;

import java.time.Duration;
import java.time.Instant;

/** 8 天滚动窗口的唯一实现：续期条件与到期时间都从这里出。 */
public final class TtlPolicy {

    private TtlPolicy() {
    }

    /** 记录/更新后至少保留 ttl 天：max(vod_time + ttl, now + ttl)。 */
    public static Instant expiry(Instant vodTime, Instant now, int ttlDays) {
        Instant byVod = vodTime.plus(Duration.ofDays(ttlDays));
        Instant byNow = now.plus(Duration.ofDays(ttlDays));
        return byVod.isAfter(byNow) ? byVod : byNow;
    }

    /** 只有 vod_time 前移或内容变化才续期；last_seen 不参与。 */
    public static boolean shouldRenew(Instant newVodTime, Instant oldVodTime, String newHash, String oldHash) {
        if (newVodTime.isAfter(oldVodTime)) {
            return true;
        }
        return oldHash == null || !oldHash.equals(newHash);
    }

    /** 内容真正变化时重置为 now+ttl；旧值更晚则保留旧值，绝不缩短。 */
    public static Instant renewedExpiry(Instant oldExpires, Instant now, int ttlDays) {
        Instant reset = now.plus(Duration.ofDays(ttlDays));
        return oldExpires != null && oldExpires.isAfter(reset) ? oldExpires : reset;
    }
}
