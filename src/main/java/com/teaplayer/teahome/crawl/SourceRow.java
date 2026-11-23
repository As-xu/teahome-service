package com.teaplayer.teahome.crawl;

import tools.jackson.databind.JsonNode;

/** 一个启用源。 */
public record SourceRow(String id, String name, String kind, String baseUrl, JsonNode spec, int priority) {

    public SourceSpec parsedSpec() {
        return SourceSpec.parse(spec);
    }

    public boolean isMacCms() {
        return "maccms".equals(kind);
    }
}
