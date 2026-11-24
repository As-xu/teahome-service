package com.teaplayer.teahome.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.web.filter.OncePerRequestFilter;

import com.teaplayer.teahome.config.ConfigService;
import com.teaplayer.teahome.config.PathGate;
import com.teaplayer.teahome.auth.TokenAuthFilter;
import com.teaplayer.teahome.auth.TokenPrincipal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 读接口限流：search 2/s、img 120/min、其余 /v1 30/s，按 token（缺省按 IP）。 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final ConfigService config;

    public RateLimitFilter(ConfigService config) {
        this.config = config;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = PathGate.path(request);
        if (!PathGate.isV1(path)) {
            chain.doFilter(request, response);
            return;
        }
        String bucket;
        int limit;
        long periodMs;
        if (path.startsWith("/v1/search")) {
            bucket = "search";
            limit = config.intValue("api.rate_limit_search_per_sec", 2);
            periodMs = 1_000L;
        } else if (path.startsWith("/v1/img/")) {
            bucket = "img";
            limit = config.intValue("api.rate_limit_img_per_min", 120);
            periodMs = 60_000L;
        } else {
            bucket = "read";
            limit = config.intValue("api.rate_limit_read_per_sec", 30);
            periodMs = 1_000L;
        }
        if (windows.size() > 10_000) {
            windows.clear();
        }
        String who = key(request) + ":" + bucket;
        Window window = windows.computeIfAbsent(who, k -> new Window());
        if (!window.allow(limit, periodMs)) {
            writeTooMany(request, response, periodMs);
            return;
        }
        chain.doFilter(request, response);
    }

    private static String key(HttpServletRequest request) {
        Object principal = request.getAttribute(TokenAuthFilter.ATTRIBUTE);
        if (principal instanceof TokenPrincipal token) {
            return "token:" + token.id();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private static void writeTooMany(HttpServletRequest request, HttpServletResponse response, long periodMs)
            throws IOException {
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        long retryAfterMs = periodMs;
        response.setStatus(429);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.setHeader("Retry-After", Long.toString(Math.max(1L, (periodMs + 999L) / 1000L)));
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("""
                {"error":{"code":"rate_limited","message":"请求过于频繁","requestId":"%s","serverTime":"%s"},"retryAfterMs":%d}
                """.formatted(requestId == null ? "-" : requestId,
                java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Shanghai")), retryAfterMs));
    }

    private static final class Window {
        private final Deque<Long> hits = new ArrayDeque<>();

        synchronized boolean allow(int limit, long periodMs) {
            long now = System.currentTimeMillis();
            while (!hits.isEmpty() && now - hits.peekFirst() > periodMs) {
                hits.pollFirst();
            }
            if (hits.size() >= limit) {
                return false;
            }
            hits.addLast(now);
            return true;
        }
    }
}
