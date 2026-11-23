package com.teaplayer.teahome.api.feed;

import java.util.List;

public final class FeedDtos {

    private FeedDtos() {
    }

    public record FeedResponse(
            List<Item> items,
            String nextCursor,
            boolean hasMore,
            String serverTime,
            String categoryRuleVersion,
            String schemaVersion) {
    }

    public record Item(
            String key,
            String title,
            Integer year,
            List<String> categoryCodes,
            String primaryCategory,
            String coverProxy,
            String coverKey,
            String remark,
            String updatedAt,
            String expiresAt,
            int lineCount,
            List<SourceRef> sources) {
    }

    public record SourceRef(String sourceId, String itemId, boolean primary) {
    }

    public record CategoriesResponse(List<CategoryCount> categories, String serverTime) {
    }

    public record CategoryCount(String key, String name, int count) {
    }
}
