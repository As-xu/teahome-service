package com.teaplayer.teahome.api.img;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.stereotype.Component;

/** 只抓取公网图片：禁止重定向、私网/环回地址、SVG，限制 5 MiB 与 10 秒。 */
@Component
public class ImageFetcher {

    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public record Fetched(byte[] bytes, String contentType, String sha256) {
    }

    public Fetched fetch(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            throw new IllegalStateException("图片地址不合法");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("图片地址协议不支持");
        }
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) {
            throw new IllegalStateException("图片地址端口不允许");
        }
        requirePublicHost(uri.getHost());

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", "TeaHomeService/0.1 (+lan)")
                .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/gif")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("图片源返回 " + response.statusCode());
                }
                String contentType = contentType(response.headers().firstValue("content-type").orElse(""));
                if (!contentType.startsWith("image/")) {
                    throw new IllegalStateException("图片源 content-type 不是图片");
                }
                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                    throw new IllegalStateException("图片过大或为空");
                }
                String actual = sniff(bytes);
                if (actual == null) {
                    throw new IllegalStateException("图片魔数不支持");
                }
                return new Fetched(bytes, actual, sha256(bytes));
            }
        } catch (IOException e) {
            throw new IllegalStateException("图片下载失败: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("图片下载被中断");
        }
    }

    private static String contentType(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        int semi = lower.indexOf(';');
        return semi >= 0 ? lower.substring(0, semi).trim() : lower.trim();
    }

    /** 用文件头决定真实类型，忽略源站声明的 content-type。 */
    private static String sniff(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == '8') {
            return "image/gif";
        }
        return null;
    }

    private static void requirePublicHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("图片地址缺少主机");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(lower) || lower.endsWith(".local")) {
            throw new IllegalStateException("图片地址不允许内网主机");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new IllegalStateException("图片地址不允许内网地址");
                }
                String text = address.getHostAddress().toLowerCase(Locale.ROOT);
                if (text.startsWith("fc") || text.startsWith("fd") || text.startsWith("fe80")) {
                    throw new IllegalStateException("图片地址不允许内网地址");
                }
                byte[] raw = address.getAddress();
                if (raw.length == 4 && forbiddenV4(raw)) {
                    throw new IllegalStateException("图片地址不允许内网地址");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("图片主机无法解析");
        }
    }

    private static boolean forbiddenV4(byte[] ip) {
        int a = ip[0] & 0xFF;
        int b = ip[1] & 0xFF;
        return a == 0
                || (a == 100 && b >= 64 && b <= 127)
                || (a == 198 && (b == 18 || b == 19))
                || (a == 192 && b == 0 && (ip[2] & 0xFF) == 0)
                || a >= 240;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 SHA-256", e);
        }
    }
}
