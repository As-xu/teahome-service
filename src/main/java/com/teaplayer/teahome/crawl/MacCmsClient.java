package com.teaplayer.teahome.crawl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** MacCMS 接口客户端：列表、批量详情、礼貌节流。 */
@Component
public class MacCmsClient {

    private static final Logger log = LoggerFactory.getLogger(MacCmsClient.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter VOD_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String USER_AGENT = "TeaHomeService/0.1 (+lan)";

    private final ObjectMapper mapper;
    private final CrawlProperties props;
    private final HttpClient http;

    public MacCmsClient(ObjectMapper mapper, CrawlProperties props) {
        this.mapper = mapper;
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public MacCmsPage list(SourceRow source, SourceSpec spec, int windowHours, int page) {
        Map<String, String> vars = Map.of(
                "page", Integer.toString(page),
                "typeId", "",
                "keyword", "");
        String url = source.baseUrl() + spec.url("list", vars);
        url = url + (url.contains("?") ? "&" : "?") + "h=" + windowHours;
        JsonNode root = getJson(url);
        int pageCount = root.path("pagecount").asInt(1);
        List<RawItem> items = parseItems(root, spec, Instant.now());
        return new MacCmsPage(items, page, pageCount, root.path("total").asInt(items.size()));
    }

    public Map<String, RawItem> details(SourceRow source, SourceSpec spec, List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        String url = source.baseUrl() + spec.url("detail", Map.of(
                "ids", String.join(",", ids),
                "keyword", "",
                "page", ""));
        JsonNode root = getJson(url);
        List<RawItem> items = parseItems(root, spec, Instant.now());
        Map<String, RawItem> out = new LinkedHashMap<>();
        for (RawItem item : items) {
            out.put(item.id(), item);
        }
        if (items.size() < ids.size()) {
            log.debug("详情返回不足 source={} want={} got={}", source.id(), ids.size(), items.size());
        }
        return out;
    }

    List<RawItem> parseItems(JsonNode root, SourceSpec spec, Instant fallbackNow) {
        JsonNode list = spec.itemsPath().isBlank() ? root : root.path(spec.itemsPath());
        if (!list.isArray()) {
            throw new IllegalStateException("响应缺少列表: " + spec.itemsPath());
        }
        List<RawItem> out = new ArrayList<>(list.size());
        for (JsonNode item : list) {
            String id = spec.field(item, "id");
            if (id.isBlank() || "0".equals(id)) {
                continue;
            }
            String title = spec.field(item, "title");
            if (title.isBlank()) {
                continue;
            }
            String typeName = SourceSpec.text(item, "type_name");
            out.add(new RawItem(
                    id,
                    title,
                    spec.field(item, "year"),
                    typeName.isBlank() ? spec.field(item, "category") : typeName,
                    spec.field(item, "cover"),
                    spec.field(item, "remark"),
                    spec.field(item, "playFrom"),
                    spec.field(item, "playUrl"),
                    SourceSpec.text(item, "vod_area"),
                    SourceSpec.text(item, "vod_lang"),
                    parseVodTime(SourceSpec.text(item, "vod_time"), fallbackNow),
                    item));
        }
        return out;
    }

    private JsonNode getJson(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " " + url);
            }
            JsonNode root = mapper.readTree(response.body());
            pause();
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("请求失败 " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求被中断 " + url, e);
        }
    }

    private void pause() {
        int ms = props.getRequestIntervalMs();
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static Instant parseVodTime(String text, Instant fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return LocalDateTime.parse(text.trim(), VOD_TIME).atZone(ZONE).toInstant();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
