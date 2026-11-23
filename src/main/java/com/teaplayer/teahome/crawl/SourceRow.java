package com.teaplayer.teahome.crawl;

import java.util.List;
import java.util.Locale;

import tools.jackson.databind.JsonNode;

/** 一个启用源。 */
public record SourceRow(String id, String name, String kind, String baseUrl, JsonNode spec,
                        int priority, List<String> allowHosts) {

    public SourceRow {
        allowHosts = allowHosts == null ? List.of() : List.copyOf(allowHosts);
    }

    public SourceSpec parsedSpec() {
        return SourceSpec.parse(spec);
    }

    public boolean isMacCms() {
        return "maccms".equals(kind);
    }

    /** allow_hosts 支持精确主机与 *.example.com 后缀匹配。 */
    public boolean allows(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (String raw : allowHosts) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String rule = raw.trim().toLowerCase(Locale.ROOT);
            if (rule.startsWith("*.")) {
                if (h.endsWith(rule.substring(1)) && h.length() > rule.length() - 1) {
                    return true;
                }
            } else if (h.equals(rule)) {
                return true;
            }
        }
        return false;
    }
}
