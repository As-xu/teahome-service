package com.teaplayer.teahome.api.watch;

import java.util.List;
import java.util.Map;

/**
 * 收藏 / 观看进度跨端的线格式。
 *
 * <p>时间戳一律用 ISO-8601 字符串（`Instant.toString()` 的形状）：客户端
 * `DateTime.tryParse` 直接吃，省掉 Java 与 Dart 两边时区配置对齐的问题。
 *
 * <p>`deleted=true` 是墓碑：这条在别处被删了。客户端据此删本地那条，
 * 而不是因为「列表里没有」去猜——猜会把「还没推上去的新条目」一起删掉。
 */
public final class WatchDtos {

    private WatchDtos() {
    }

    /** 客户端可以不传 `deleted`（新建/更新都是如此）：缺席按 false 读。 */
    public static boolean deletedOf(Boolean deleted) {
        return deleted != null && deleted;
    }

    /** 一条观看进度。`episodes` 键是集号、值是 `[位置, 时长]`，与客户端落盘同形。 */
    public record ProgressItem(
            String key,
            String sourceId,
            String itemId,
            String title,
            Integer lineIndex,
            Integer episodeIndex,
            String episodeName,
            Long position,
            Long duration,
            Map<String, List<Long>> episodes,
            String updatedAt,
            // 装箱不是随手写的：Jackson 3 默认开着 FAIL_ON_NULL_FOR_PRIMITIVES，
            // 客户端不传 deleted 就会整条 400。null 当 false，见 deletedOf。
            Boolean deleted) {
    }

    public record ProgressList(List<ProgressItem> items, String serverTime) {
    }

    /** 一条收藏。字段与客户端 `FavoriteEntry` 一一对应。 */
    public record FavoriteItem(
            String key,
            String sourceId,
            String sourceName,
            String itemId,
            String title,
            String year,
            String category,
            String coverUrl,
            String remark,
            String addedAt,
            String updatedAt,
            Boolean deleted) {
    }

    public record FavoriteList(List<FavoriteItem> items, String serverTime) {
    }
}
