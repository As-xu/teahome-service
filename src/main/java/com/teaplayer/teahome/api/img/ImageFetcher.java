package com.teaplayer.teahome.api.img;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.teaplayer.teahome.config.ConfigService;

/**
 * 只抓取公网图片：禁止重定向、私网/环回地址、SVG，限制 5 MiB 与 10 秒。
 *
 * <p>SSRF 收敛靠 DNS 钉扎：解析只发生一次，校验通过的 {@link InetAddress} 数组直接交给
 * HttpClient 连接，后续不会再按主机名重新解析，因此 DNS 重绑定（第一次返回公网 IP、
 * 第二次返回内网 IP）无法把连接引向内网。HttpClient 5 的 {@link DnsResolver} 是唯一的
 * 解析入口，字面量 IP 也会走同一条 {@code resolve} 路径。</p>
 */
@Component
public class ImageFetcher {

    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int CAP_BYTES = 16 * 1024 * 1024;
    private static final int MIN_BYTES = 64 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    private static final String USER_AGENT = "TeaHomeService/0.1 (+lan)";

    private final ConfigService config;
    private final Set<Integer> allowedPorts;
    private final CloseableHttpClient http;

    @Autowired
    public ImageFetcher(ConfigService config) {
        this(config, new PublicDnsResolver(), Set.of(80, 443));
    }

    ImageFetcher(ConfigService config, DnsResolver resolver, Set<Integer> allowedPorts) {
        this.config = config;
        this.allowedPorts = Set.copyOf(allowedPorts);
        ConnectionConfig connection = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(CONNECT_TIMEOUT))
                .setSocketTimeout(Timeout.of(RESPONSE_TIMEOUT))
                .build();
        PoolingHttpClientConnectionManager manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setDefaultConnectionConfig(connection)
                .setMaxConnTotal(8)
                .setMaxConnPerRoute(4)
                .setConnectionTimeToLive(TimeValue.ofSeconds(60))
                .setValidateAfterInactivity(TimeValue.ofSeconds(10))
                .build();
        this.http = HttpClients.custom()
                .setConnectionManager(manager)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(3))
                        .setResponseTimeout(Timeout.of(RESPONSE_TIMEOUT))
                        .setRedirectsEnabled(false)
                        .build())
                .build();
    }

    public record Fetched(byte[] bytes, String contentType, String sha256) {
    }

    public Fetched fetch(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            throw new IllegalStateException("图片地址不合法");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalStateException("图片地址协议不支持");
        }
        int port = uri.getPort();
        if (port != -1 && !allowedPorts.contains(port)) {
            throw new IllegalStateException("图片地址端口不允许");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("图片地址缺少主机");
        }
        // 快速失败：明显的内网主机名不必等到解析；真正的地址校验在 PublicDnsResolver 里。
        if (blockedHostname(host)) {
            throw new IllegalStateException("图片地址不允许内网主机");
        }

        int maxBytes = (int) Math.max(MIN_BYTES,
                Math.min(config.longValue("image.max_bytes", MAX_BYTES), CAP_BYTES));

        HttpGet get = new HttpGet(uri);
        get.setHeader("User-Agent", USER_AGENT);
        get.setHeader("Accept", "image/avif,image/webp,image/png,image/jpeg,image/gif");
        try (CloseableHttpResponse response = http.execute(get)) {
            int status = response.getCode();
            if (status != 200) {
                throw new IllegalStateException("图片源返回 " + status);
            }
            Header header = response.getFirstHeader("Content-Type");
            String contentType = contentType(header == null ? "" : header.getValue());
            if (!contentType.startsWith("image/")) {
                throw new IllegalStateException("图片源 content-type 不是图片");
            }
            HttpEntity entity = response.getEntity();
            if (entity == null) {
                throw new IllegalStateException("图片源没有响应体");
            }
            byte[] bytes;
            try (InputStream in = entity.getContent()) {
                bytes = in.readNBytes(maxBytes + 1);
            }
            if (bytes.length == 0 || bytes.length > maxBytes) {
                throw new IllegalStateException("图片过大或为空");
            }
            String actual = sniff(bytes);
            if (actual == null) {
                throw new IllegalStateException("图片魔数不支持");
            }
            return new Fetched(bytes, actual, sha256(bytes));
        } catch (IOException e) {
            throw new IllegalStateException("图片下载失败: " + e.getClass().getSimpleName());
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

    private static boolean blockedHostname(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(lower) || lower.endsWith(".local");
    }

    /**
     * 公网解析器：解析、校验、返回同一组地址。校验失败直接抛 {@link UnknownHostException}，
     * 连接不会发生。
     */
    static final class PublicDnsResolver implements DnsResolver {

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            return requirePublic(host);
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            // 不做反向解析：连接与 TLS 校验始终使用请求里的主机名。
            return host;
        }
    }

    static InetAddress[] requirePublic(String host) throws UnknownHostException {
        if (host == null || host.isBlank()) {
            throw new UnknownHostException("图片地址缺少主机");
        }
        if (blockedHostname(host)) {
            throw new UnknownHostException("图片地址不允许内网主机");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new UnknownHostException("图片主机无法解析");
        }
        if (addresses.length == 0) {
            throw new UnknownHostException("图片主机无法解析");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new UnknownHostException("图片地址不允许内网地址");
            }
        }
        return addresses;
    }

    private static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        String text = address.getHostAddress().toLowerCase(Locale.ROOT);
        if (text.startsWith("fc") || text.startsWith("fd") || text.startsWith("fe80")) {
            return false;
        }
        byte[] raw = address.getAddress();
        return raw.length != 4 || !forbiddenV4(raw);
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
