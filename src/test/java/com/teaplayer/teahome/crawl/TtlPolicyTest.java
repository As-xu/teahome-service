package com.teaplayer.teahome.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class TtlPolicyTest {

    private final Instant now = Instant.parse("2026-04-01T00:00:00Z");

    @Test
    void expiryUsesEarlierOfVodAndNowWindow() {
        assertThat(TtlPolicy.expiry(now.minus(Duration.ofDays(2)), now, 8))
                .isEqualTo(now.plus(Duration.ofDays(6)));
        assertThat(TtlPolicy.expiry(now.plus(Duration.ofDays(30)), now, 8))
                .isEqualTo(now.plus(Duration.ofDays(8)));
    }

    @Test
    void expiryHasFiveMinuteFloor() {
        assertThat(TtlPolicy.expiry(now.minus(Duration.ofDays(30)), now, 8))
                .isEqualTo(now.plus(Duration.ofMinutes(5)));
    }

    @Test
    void renewsOnlyWhenVodMovesForwardOrContentChanges() {
        Instant oldVod = now.minus(Duration.ofDays(3));
        assertThat(TtlPolicy.shouldRenew(oldVod.plusSeconds(60), oldVod, "a", "a")).isTrue();
        assertThat(TtlPolicy.shouldRenew(oldVod, oldVod, "b", "a")).isTrue();
        assertThat(TtlPolicy.shouldRenew(oldVod, oldVod, "a", "a")).isFalse();
        assertThat(TtlPolicy.shouldRenew(oldVod, oldVod, "a", null)).isTrue();
    }

    @Test
    void renewedExpiryNeverShrinks() {
        Instant oldExpiry = now.plus(Duration.ofDays(9));
        assertThat(TtlPolicy.renewedExpiry(oldExpiry, now.plus(Duration.ofDays(7))))
                .isEqualTo(oldExpiry);
        assertThat(TtlPolicy.renewedExpiry(oldExpiry, now.plus(Duration.ofDays(10))))
                .isEqualTo(now.plus(Duration.ofDays(10)));
    }
}
