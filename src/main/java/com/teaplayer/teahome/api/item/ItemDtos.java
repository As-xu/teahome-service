package com.teaplayer.teahome.api.item;

import java.util.List;

public final class ItemDtos {

    private ItemDtos() {
    }

    public record ItemDetail(
            String key,
            String title,
            Integer year,
            List<String> categoryCodes,
            String primaryCategory,
            String coverProxy,
            String coverKey,
            String remark,
            String origin,
            String updatedAt,
            String expiresAt,
            List<Line> lines,
            List<Sibling> siblings) {
    }

    public record Line(String from, String protocol, boolean primary, List<Episode> episodes) {
    }

    public record Episode(int index, String name, String url) {
    }

    public record Sibling(String sourceId, String itemId, boolean primary) {
    }
}
