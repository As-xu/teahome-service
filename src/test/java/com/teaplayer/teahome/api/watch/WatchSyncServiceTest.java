package com.teaplayer.teahome.api.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.teaplayer.teahome.api.ApiException;

/**
 * 只测入参规整与 JSON 编解码：落库靠集成环境，单测不下 DB
 * （jdbc 传 null 即可，被测的方法都不碰它）。
 */
class WatchSyncServiceTest {

    private final WatchSyncService service = new WatchSyncService(null);

    private static String codeOf(Throwable e) {
        return ((ApiException) e).code();
    }

    private static WatchDtos.ProgressItem progress(Map<String, List<Long>> episodes) {
        return new WatchDtos.ProgressItem(
                null, "src-a", "item-9", "繁花", 0, 2, "第3集",
                123_000L, 2_700_000L, episodes,
                "2026-11-28T13:00:00Z", false);
    }

    @Test
    void keyIsDerivedFromSourceAndItem() {
        WatchDtos.ProgressItem clean = service.normalizeProgress(
                progress(Map.of("2", List.of(123_000L, 2_700_000L))));
        assertThat(clean.key()).isEqualTo("src-a|item-9");
        assertThat(WatchDtos.deletedOf(clean.deleted())).isFalse();
        assertThat(clean.updatedAt()).isEqualTo("2026-11-28T13:00:00Z");
        assertThat(clean.episodes()).containsEntry("2", List.of(123_000L, 2_700_000L));
    }

    @Test
    void rejectsMissingFields() {
        assertThatThrownBy(() -> service.normalizeProgress(null))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("bad_progress"));
        WatchDtos.ProgressItem noTitle = new WatchDtos.ProgressItem(
                null, "src-a", "item-9", "  ", 0, 0, "", 0L, 0L, Map.of(), null, false);
        assertThatThrownBy(() -> service.normalizeProgress(noTitle))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("bad_item"));
    }

    @Test
    void negativeNumbersAreEitherClampedOrRejected() {
        // 集号是脏数据，夹到 0 比整条丢掉更划算。
        WatchDtos.ProgressItem clamped = new WatchDtos.ProgressItem(
                null, "s", "i", "t", -3, -1, null, null, null, Map.of(), null, false);
        WatchDtos.ProgressItem clean = service.normalizeProgress(clamped);
        assertThat(clean.lineIndex()).isZero();
        assertThat(clean.episodeIndex()).isZero();
        assertThat(clean.position()).isZero();
        // 播放位置为负说明客户端状态坏了，宁可回 400 让人看见。
        WatchDtos.ProgressItem badPosition = new WatchDtos.ProgressItem(
                null, "s", "i", "t", 0, 0, "", -1L, 0L, Map.of(), null, false);
        assertThatThrownBy(() -> service.normalizeProgress(badPosition))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("bad_progress"));
    }

    @Test
    void badTimestampFallsBackToNow() {
        WatchDtos.ProgressItem clean = service.normalizeProgress(new WatchDtos.ProgressItem(
                null, "s", "i", "t", 0, 0, "", 0L, 0L, Map.of(), "not-a-time", false));
        // 解析失败不该把整条进度丢掉，退化成「现在」。
        assertThat(Instant.parse(clean.updatedAt()))
                .isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
    }

    @Test
    void episodesMustBePairs() {
        assertThatThrownBy(() -> service.cleanEpisodes(Map.of("1", List.of(1L))))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("bad_episodes"));
        assertThatThrownBy(() -> service.cleanEpisodes(Map.of("1", List.of(1L, -1L))))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("bad_episodes"));
        assertThat(service.cleanEpisodes(Map.of(" ", List.of(1L, 2L)))).isEmpty();
        assertThat(service.cleanEpisodes(null)).isEmpty();
    }

    @Test
    void episodesRoundTripThroughTextColumn() {
        Map<String, List<Long>> episodes = new java.util.LinkedHashMap<>();
        episodes.put("0", List.of(1L, 2L));
        episodes.put("5", List.of(3L, 4L));
        String text = service.encodeEpisodes(episodes);
        assertThat(text).isEqualTo("0:1:2;5:3:4");
        assertThat(service.decodeEpisodes(text)).isEqualTo(episodes);
        // 坏数据当没有按集记录，不能让整次拉取失败。
        assertThat(service.decodeEpisodes("0:1")).isEmpty();
        assertThat(service.decodeEpisodes("x:1:2")).isEmpty();
        assertThat(service.decodeEpisodes(null)).isEmpty();
        assertThat(service.encodeEpisodes(Map.of())).isEmpty();
    }

    @Test
    void favoriteDefaultsUpdatedAtToAddedAt() {
        WatchDtos.FavoriteItem clean = service.normalizeFavorite(new WatchDtos.FavoriteItem(
                null, "src-a", null, "item-9", "繁花", "2023", "tv",
                "http://x/y.jpg", "第30集", "2026-11-28T12:00:00Z", null, false));
        assertThat(clean.key()).isEqualTo("src-a|item-9");
        // 来源名留空时用 sourceId 兜底，列表里至少不显示空白。
        assertThat(clean.sourceName()).isEqualTo("src-a");
        assertThat(clean.updatedAt()).isEqualTo(clean.addedAt());
    }
}
