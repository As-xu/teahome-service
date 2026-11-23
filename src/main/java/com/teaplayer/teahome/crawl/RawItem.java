package com.teaplayer.teahome.crawl;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

/** 一条源站条目的原始字段（列表与详情共用；详情填得更全）。 */
public record RawItem(
        String id,
        String title,
        String year,
        String category,
        String cover,
        String remark,
        String playFrom,
        String playUrl,
        String area,
        String lang,
        Instant vodTime,
        JsonNode raw) {
}
