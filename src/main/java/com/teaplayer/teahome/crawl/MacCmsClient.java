package com.teaplayer.teahome.crawl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** MacCMS 接口客户端：列表、批量详情、allow_hosts 校验、重试与礼貌节流。 */
@Component
public class MacCmsClient {

    private static final Logger log = LoggerFactory.getLogger(MacCmsClient.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter VOD_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String USER_AGENT = "TeaHomeService/0.1 (+lan)";
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    private static final int ATTEMPTS = 3;

    private final ObjectMapper mapper;
    private final CrawlProperties props;
    private final com.teaplayer.teahome.config.ConfigService config;
    private final HttpClient http;

    public MacCmsClient(ObjectMapper mapper, CrawlProperties props,
                        com.teaplayer.teahome.config.ConfigService config) {
        this.mapper = mapper;
        this.props = props;
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public MacCmsPage list(SourceRow source, SourceSpec spec, int windowHours, int page) {
        Map<String, String> vars = Map.of(
                "page", Integer.toString(page),
                "typeId", "",
                "keyword", "");
        String url = source.baseUrl() + spec.url("list", vars);
        url = url + (url.contains("?") ? "&" : "?") + "h=" + windowHours;
        JsonNode root = getJson(url, source);
        int pageCount = root.path("pagecount").asInt(1);
        List<RawItem> items = parseItems(root, spec, Instant.now());
        return new MacCmsPage(items, page, pageCount, root.path("total").asInt(items.size()));
    }

    public Map<String, RawItem> details(SourceRow source, SourceSpec spec, List<String> ids) {
        return details(source, spec, ids, ATTEMPTS, Duration.ofSeconds(25));
    }

    public Map<String, RawItem> details(SourceRow source, SourceSpec spec, List<String> ids,
                                        int attempts, Duration timeout) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        String url = source.baseUrl() + spec.url("detail", Map.of(
                "ids", String.join(",", ids),
                "keyword", "",
                "page", ""));
        JsonNode root = getJson(url, source, attempts, timeout);
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

    public List<RawItem> search(SourceRow source, SourceSpec spec, String keyword) {
        return search(source, spec, keyword, Duration.ofSeconds(10));
    }

    public List<RawItem> search(SourceRow source, SourceSpec spec, String keyword, Duration timeout) {
        if (!spec.hasEndpoint("search")) {
            throw new IllegalStateException("源未配置 search endpoint");
        }
        String url = source.baseUrl() + spec.url("search", Map.of(
                "keyword", java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8),
                "page", "1"));
        JsonNode root = getJson(url, source, 1, timeout);
        return parseItems(root, spec, Instant.now());
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

    private JsonNode getJson(String url, SourceRow source) {
        return getJson(url, source, ATTEMPTS, Duration.ofSeconds(25));
    }

    private JsonNode getJson(String url, SourceRow source, int attempts, Duration timeout) {
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (host == null || !source.allows(host) || isPrivateHost(host)) {
            throw new IllegalStateException("上游主机不在 allow_hosts 内: " + host);
        }
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(timeout)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int code = response.statusCode();
                if (code < 200 || code >= 300) {
                    response.body().close();
                    if (code >= 500 && attempt < attempts) {
                        sleep(300L * attempt);
                        continue;
                    }
                    throw new IllegalStateException("上游 HTTP " + code + " host=" + host);
                }
                byte[] body = readLimited(response.body());
                JsonNode root = mapper.readTree(body);
                pause();
                return root;
            } catch (IOException e) {
                if (attempt < attempts) {
                    sleep(300L * attempt);
                    continue;
                }
                throw new IllegalStateException("上游请求失败 host=" + host + ": " + e.getClass().getSimpleName(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("请求被中断 host=" + host, e);
            }
        }
        throw new IllegalStateException("上游请求失败 host=" + host);
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buffer = new byte[64 * 1024];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) >= 0) {
                total += n;
                if (total > MAX_BODY_BYTES) {
                    throw new IOException("响应超过 " + MAX_BODY_BYTES + " 字节");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static boolean isPrivateHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(h) || h.endsWith(".local") || "::1".equals(h)
                || (h.contains(":") && (h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")))) {
            return true;
        }
        String[] parts = h.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            return a == 10 || a == 127 || a == 0
                    || (a == 192 && b == 168)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 169 && b == 254);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void pause() {
        int configured = config.intValue("crawl.request_interval_ms", props.getRequestIntervalMs());
        sleep(Math.max(0, configured));
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
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
