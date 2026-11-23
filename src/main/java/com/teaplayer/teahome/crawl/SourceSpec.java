package com.teaplayer.teahome.crawl;

import java.util.HashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/** 源声明式规格的运行时视图（与客户端 PluginSpec 同构，只取 P1 需要的部分）。 */
public final class SourceSpec {

    private final Map<String, String> endpoints;
    private final Map<String, String> fields;
    private final String itemsPath;
    private final String groupSep;
    private final String episodeSep;
    private final String nameSep;

    private SourceSpec(
            Map<String, String> endpoints,
            Map<String, String> fields,
            String itemsPath,
            String groupSep,
            String episodeSep,
            String nameSep) {
        this.endpoints = Map.copyOf(endpoints);
        this.fields = Map.copyOf(fields);
        this.itemsPath = itemsPath;
        this.groupSep = groupSep;
        this.episodeSep = episodeSep;
        this.nameSep = nameSep;
    }

    public static SourceSpec parse(JsonNode spec) {
        Map<String, String> endpoints = new HashMap<>();
        JsonNode ep = spec.path("endpoints");
        ep.propertyNames().forEach(name -> endpoints.put(name, ep.path(name).asText("")));

        Map<String, String> fields = new HashMap<>();
        JsonNode f = spec.path("fields");
        f.propertyNames().forEach(name -> fields.put(name, f.path(name).asText("")));

        JsonNode play = spec.path("playUrl");
        return new SourceSpec(
                endpoints,
                fields,
                spec.path("itemsPath").asText(""),
                play.path("groupSep").asText("$$$"),
                play.path("episodeSep").asText("#"),
                play.path("nameSep").asText("$"));
    }

    public boolean hasEndpoint(String name) {
        return endpoints.containsKey(name) && !endpoints.get(name).isBlank();
    }

    public String url(String endpoint, Map<String, String> vars) {
        String template = endpoints.get(endpoint);
        if (template == null) {
            throw new IllegalArgumentException("规格缺少 endpoint: " + endpoint);
        }
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    /** 支持 `vod_class|type_name` 这种回退写法。 */
    public String field(JsonNode item, String name) {
        String mapping = fields.getOrDefault(name, "");
        if (mapping.isBlank()) {
            return "";
        }
        for (String key : mapping.split("\\|")) {
            String value = text(item, key.trim());
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    public static String text(JsonNode item, String key) {
        JsonNode node = item.get(key);
        if (node == null || node.isNull()) {
            return "";
        }
        String value = node.asText("");
        return value == null ? "" : value.trim();
    }

    public String itemsPath() {
        return itemsPath;
    }

    public String groupSep() {
        return groupSep;
    }

    public String episodeSep() {
        return episodeSep;
    }

    public String nameSep() {
        return nameSep;
    }
}
