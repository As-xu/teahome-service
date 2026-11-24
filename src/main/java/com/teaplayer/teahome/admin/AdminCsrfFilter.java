package com.teaplayer.teahome.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.teaplayer.teahome.config.PathGate;

/** Double-submit CSRF plus strict Origin/Referer for every admin state change. */
@Component
@Profile("admin")
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
public class AdminCsrfFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = PathGate.path(request);
        return !path.startsWith("/admin") || path.startsWith("/admin/assets")
                || SAFE.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = PathGate.path(request);
        if (!originAllowed(request)) {
            response.sendError(403, "Bad Origin");
            return;
        }
        if (path.equals("/admin/login")) {
            chain.doFilter(request, response);
            return;
        }
        AdminSessionInfo session = (AdminSessionInfo) request.getAttribute(AdminAttributes.SESSION);
        String expected = (String) request.getAttribute(AdminAttributes.CSRF);
        String supplied = request.getParameter("_csrf");
        if (supplied == null || supplied.isBlank()) {
            supplied = request.getHeader("X-CSRF-Token");
        }
        if (session == null || expected == null || supplied == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(403, "CSRF token missing or invalid");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean originAllowed(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        String referer = request.getHeader("Referer");
        if (origin == null && referer == null) {
            return false;
        }
        String candidate = origin != null ? origin : referer;
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        String expectedPort = (port == 80 || port == 443) ? "" : ":" + port;
        String expected = scheme + "://" + host + expectedPort;
        if (candidate.equals(expected)) {
            return true;
        }
        // The SSH tunnel may make the browser use 127.0.0.1 while the connector sees localhost.
        return candidate.equals(scheme + "://127.0.0.1" + expectedPort)
                || candidate.equals(scheme + "://localhost" + expectedPort);
    }
}
