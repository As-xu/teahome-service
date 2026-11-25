package com.teaplayer.teahome.api.img;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.teaplayer.teahome.config.ConfigService;

/**
 * 封面抓取器的网络行为：重定向、content-type、大小与魔数都在真实 socket 上验证。
 *
 * <p>测试服务器只能绑定回环地址，因此注入一个把任意主机名映射到 127.0.0.1 的
 * {@link DnsResolver} 并把随机端口加入白名单；生产路径的公网地址校验由
 * {@code requirePublic} 的单元测试单独覆盖。</p>
 */
class ImageFetcherTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F'};

    private static HttpServer server;
    private static int port;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/ok.png", exchange -> respond(exchange, 200, "image/png", PNG));
        server.createContext("/ok.jpg", exchange -> respond(exchange, 200, "image/jpeg; charset=binary", JPEG));
        server.createContext("/chunked.png", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(PNG);
            }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ok.png");
            respond(exchange, 302, "text/plain", new byte[0]);
        });
        server.createContext("/html", exchange -> respond(exchange, 200, "text/html", PNG));
        server.createContext("/svg", exchange -> respond(exchange, 200, "image/svg+xml", "<svg/>".getBytes()));
        server.createContext("/garbage", exchange -> respond(exchange, 200, "image/png", "not an image".getBytes()));
        server.createContext("/big.png", exchange -> respond(exchange, 200, "image/png", new byte[70_000]));
        server.createContext("/empty", exchange -> respond(exchange, 200, "image/png", new byte[0]));
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static ImageFetcher fetcher(long maxBytes) {
        ConfigService config = mock(ConfigService.class);
        when(config.longValue(eq("image.max_bytes"), anyLong())).thenReturn(maxBytes);
        DnsResolver loopback = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        return new ImageFetcher(config, loopback, Set.of(80, 443, port));
    }

    private static String url(String path) {
        return "http://img.test:" + port + path;
    }

    @Test
    void fetchesPngAndComputesSha256() throws Exception {
        ImageFetcher.Fetched fetched = fetcher(5L * 1024 * 1024).fetch(url("/ok.png"));

        assertEquals("image/png", fetched.contentType());
        assertArrayEquals(PNG, fetched.bytes());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PNG)),
                fetched.sha256());
    }

    @Test
    void acceptsJpegWithCharsetParameterInContentType() {
        ImageFetcher.Fetched fetched = fetcher(5L * 1024 * 1024).fetch(url("/ok.jpg"));
        assertEquals("image/jpeg", fetched.contentType());
    }

    @Test
    void acceptsChunkedBodyWithoutContentLength() {
        assertEquals("image/png", fetcher(5L * 1024 * 1024).fetch(url("/chunked.png")).contentType());
    }

    @Test
    void refusesRedirects() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> fetcher(5L * 1024 * 1024).fetch(url("/redirect")));
        assertTrue(error.getMessage().contains("302"), error.getMessage());
    }

    @Test
    void refusesNonImageContentType() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> fetcher(5L * 1024 * 1024).fetch(url("/html")));
        assertTrue(error.getMessage().contains("content-type"), error.getMessage());
    }

    @Test
    void refusesSvgEvenWhenServerClaimsImage() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> fetcher(5L * 1024 * 1024).fetch(url("/svg")));
        assertTrue(error.getMessage().contains("魔数"), error.getMessage());
    }

    @Test
    void refusesBodyWithoutKnownMagic() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> fetcher(5L * 1024 * 1024).fetch(url("/garbage")));
        assertTrue(error.getMessage().contains("魔数"), error.getMessage());
    }

    @Test
    void refusesBodyOverConfiguredLimit() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> fetcher(64L * 1024).fetch(url("/big.png")));
        assertTrue(error.getMessage().contains("过大"), error.getMessage());
    }

    @Test
    void refusesEmptyBody() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> fetcher(5L * 1024 * 1024).fetch(url("/empty")));
        assertTrue(error.getMessage().contains("为空"), error.getMessage());
    }

    @Test
    void refusesUnsupportedSchemeAndPort() {
        ImageFetcher fetcher = fetcher(5L * 1024 * 1024);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> fetcher.fetch("ftp://img.test/ok.png")).getMessage().contains("协议"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> fetcher.fetch("http://img.test:8080/ok.png")).getMessage().contains("端口"));
    }

    @Test
    void publicResolverRefusesLoopbackPrivateAndLocalNames() {
        assertRefused("127.0.0.1");
        assertRefused("10.20.30.40");
        assertRefused("172.16.0.1");
        assertRefused("192.168.5.156");
        assertRefused("169.254.169.254");
        assertRefused("100.64.0.1");
        assertRefused("0.0.0.0");
        assertRefused("localhost");
        assertRefused("nas.local");
        assertRefused("::1");
        assertRefused("fd00::1");
        assertRefused("fe80::1");
    }

    @Test
    void publicResolverKeepsPublicAddresses() throws Exception {
        InetAddress[] addresses = ImageFetcher.requirePublic("8.8.8.8");
        assertEquals(1, addresses.length);
        assertEquals("8.8.8.8", addresses[0].getHostAddress());
    }

    @Test
    void pinnedResolverIsTheOnlyResolutionPoint() throws Exception {
        DnsResolver resolver = new ImageFetcher.PublicDnsResolver();
        assertEquals("example.com", resolver.resolveCanonicalHostname("example.com"));
        assertThrows(UnknownHostException.class, () -> resolver.resolve("192.168.1.1"));
    }

    private static void assertRefused(String host) {
        assertThrows(UnknownHostException.class, () -> ImageFetcher.requirePublic(host), host);
    }
}
