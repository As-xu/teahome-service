package com.teaplayer.teahome.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

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

    private final ApiTokenService tokenService;

    public TokenAuthFilter(ApiTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 用解码后的路径判断，避免 /%76%31/... 这类编码绕过。
        String path = request.getRequestURI();
        if (path == null || path.isEmpty()) {
            path = request.getServletPath();
        }
        try {
            path = java.net.URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // 保留原始路径，交给后续鉴权/路由处理
        }
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return !(path.equals("/v1") || path.startsWith("/v1/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String rawToken = extractToken(request);
        Optional<TokenPrincipal> principal = tokenService.authenticate(rawToken, request.getRemoteAddr());
        if (principal.isEmpty()) {
            Object requestId = request.getAttribute("teahome.requestId");
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
        request.setAttribute(ATTRIBUTE, principal.get());
        filterChain.doFilter(request, response);
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
