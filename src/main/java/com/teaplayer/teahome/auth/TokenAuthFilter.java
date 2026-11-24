package com.teaplayer.teahome.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.teaplayer.teahome.config.PathGate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /v1/** 的令牌闸门。除 /actuator/** 与错误页外，所有 /v1 请求必须带
 * X-TeaHome-Token（也接受 Authorization: Bearer），否则 401 JSON。
 */
public class TokenAuthFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = "teahome.token";
    private static final String TOKEN_HEADER = "X-TeaHome-Token";

    private static final int MAX_FAILURES_PER_TOKEN = 30;
    private static final int MAX_FAILURES_PER_IP = 120;
    private static final long WINDOW_MS = 60_000L;

    private final ApiTokenService tokenService;
    private final Map<String, long[]> tokenFailures = new ConcurrentHashMap<>();
    private final Map<String, long[]> ipFailures = new ConcurrentHashMap<>();

    public TokenAuthFilter(ApiTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 与 MVC 同一套规范化：/v1;x/... 与 /%76%31/... 都必须被鉴权。
        return !PathGate.isV1(PathGate.path(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String rawToken = extractToken(request);
        Optional<TokenPrincipal> principal = tokenService.authenticate(rawToken, request.getRemoteAddr());
        if (principal.isEmpty()) {
            Object requestId = request.getAttribute("teahome.requestId");
            String ip = request.getRemoteAddr();
            if (!allowFailure(ip, rawToken)) {
                response.setStatus(429);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                response.setHeader("Retry-After", "60");
                response.getWriter().write("""
                        {"error":{"code":"rate_limited","message":"too many failed attempts","requestId":"%s","serverTime":"%s"},"retryAfterMs":60000}
                        """.formatted(requestId == null ? "-" : requestId,
                        java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))));
                return;
            }
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "TeaHome-Token realm=\"teahome\"");
            response.getWriter().write("""
                    {"error":{"code":"unauthorized","message":"missing or invalid token","requestId":"%s","serverTime":"%s"},"retryAfterMs":0}
                    """.formatted(requestId == null ? "-" : requestId,
                    java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))));
            return;
        }
        if (rawToken != null && !rawToken.isBlank()) {
            tokenFailures.remove(fingerprint(rawToken));
        }
        request.setAttribute(ATTRIBUTE, principal.get());
        filterChain.doFilter(request, response);
    }

    private boolean allowFailure(String ip, String rawToken) {
        long now = System.currentTimeMillis();
        boolean tokenAllowed = true;
        if (rawToken != null && !rawToken.isBlank()) {
            tokenAllowed = hit(tokenFailures, fingerprint(rawToken), MAX_FAILURES_PER_TOKEN, now);
        }
        boolean ipAllowed = ip == null || ip.isBlank()
                || hit(ipFailures, ip, MAX_FAILURES_PER_IP, now);
        return tokenAllowed && ipAllowed;
    }

    private static boolean hit(Map<String, long[]> map, String key, int max, long now) {
        if (map.size() > 10_000) {
            map.entrySet().removeIf(e -> now - e.getValue()[1] > WINDOW_MS);
        }
        long[] state = map.computeIfAbsent(key, k -> new long[]{0L, now});
        synchronized (state) {
            if (now - state[1] > WINDOW_MS) {
                state[0] = 0L;
                state[1] = now;
            }
            state[0]++;
            return state[0] <= max;
        }
    }

    private static String fingerprint(String rawToken) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(rawToken.hashCode());
        }
    }

    private String extractToken(HttpServletRequest request) {
        String token = request.getHeader(TOKEN_HEADER);
        if (token != null && !token.isBlank()) {
            return token.trim();
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String bearer = authorization.substring(7).trim();
            return bearer.isEmpty() ? null : bearer;
        }
        return null;
    }
}
