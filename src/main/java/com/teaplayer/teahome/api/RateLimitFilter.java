package com.teaplayer.teahome.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.web.filter.OncePerRequestFilter;

import com.teaplayer.teahome.auth.TokenAuthFilter;
import com.teaplayer.teahome.auth.TokenPrincipal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 读接口限流：search 2/s、img 120/min、其余 /v1 30/s，按 token（缺省按 IP）。 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        int limit;
        long periodMs;
        if (path.startsWith("/v1/search")) {
            limit = 2;
            periodMs = 1_000L;
        } else if (path.startsWith("/v1/img/")) {
            limit = 120;
            periodMs = 60_000L;
        } else {
            limit = 30;
            periodMs = 1_000L;
        }
        String who = key(request);
        Window window = windows.computeIfAbsent(who, k -> new Window());
        if (!window.allow(limit, periodMs)) {
            writeTooMany(request, response);
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

    private static void writeTooMany(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        response.setStatus(429);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.setHeader("Retry-After", "1");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("""
                {"error":{"code":"rate_limited","message":"请求过于频繁","requestId":"%s"},"retryAfterMs":1000}
                """.formatted(requestId == null ? "-" : requestId));
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
