package com.teaplayer.teahome.api.search;

import java.util.List;

public final class SearchDtos {

    private SearchDtos() {
    }

    public record SearchResponse(
            String query,
            String category,
            List<Item> items,
            boolean partial,
            List<Failed> failed,
            String serverTime) {
    }

    public record Item(
            String key,
            String title,
            Integer year,
            List<String> categoryCodes,
            String primaryCategory,
            String remark,
            List<SourceRef> sources) {
    }

    public record SourceRef(String sourceId, String itemId) {
    }

    public record Failed(String sourceId, String code) {
    }
}
