package com.teaplayer.teahome.admin;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.teaplayer.teahome.config.PathGate;

/**
 * Admin edge guard: loopback peer, exact Host, session cookie and CSRF cookie binding.
 * Runs for /admin/** except static assets.
 */
@Component
@Profile("admin")
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class AdminAccessFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAccessFilter.class);

    public static final String SID_COOKIE = "ths_sid";
    public static final String CSRF_COOKIE = "ths_csrf";

    private final AdminSessionService sessions;

    public AdminAccessFilter(AdminSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = PathGate.path(request);
        if (path.startsWith("/admin/assets") || path.equals("/actuator/health")
                || path.equals("/health") || path.startsWith("/error")) {
            return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = PathGate.path(request);
        if (!hostAllowed(request.getHeader("Host"))) {
            log.warn("admin 拒绝 421：Host={} path={} remote={}", request.getHeader("Host"), path, request.getRemoteAddr());
            AdminErrorPages.write(response, 421, "Misdirected Request",
                    "管理端只接受 Host 为 127.0.0.1 或 localhost 的请求。请用 http://127.0.0.1:18081 访问（若通过 SSH 隧道，请确认隧道把本地 18081 转发到服务器的 127.0.0.1:18081）。");
            return;
        }
        if (!loopbackPeer(request.getRemoteAddr())) {
            log.warn("admin 拒绝 403：来源不是回环 remote={} Host={} path={}",
                    request.getRemoteAddr(), request.getHeader("Host"), path);
            AdminErrorPages.write(response, 403, "Forbidden",
                    "管理端只接受来自本机回环的请求。请确认是通过 SSH 隧道从本机 127.0.0.1 访问，而不是从局域网地址直连。");
            return;
        }

        String raw = cookie(request, SID_COOKIE);
        Optional<AdminSessionInfo> resolved = sessions.resolve(raw);
        boolean loginPath = path.equals("/admin/login");
        if (resolved.isEmpty()) {
            if (loginPath) {
                chain.doFilter(request, response);
                return;
            }
            response.sendRedirect("/admin/login");
            return;
        }
        AdminSessionInfo session = resolved.get();
        if (loginPath) {
            response.sendRedirect("/admin");
            return;
        }
        String csrfRaw = cookie(request, CSRF_COOKIE);
        if (csrfRaw == null || !AdminHashes.sha256Hex(csrfRaw).equals(session.csrfHash())) {
            clearCookie(response, SID_COOKIE);
            clearCookie(response, CSRF_COOKIE);
            response.sendRedirect("/admin/login");
            return;
        }
        request.setAttribute(AdminAttributes.SESSION, session);
        request.setAttribute(AdminAttributes.CSRF, csrfRaw);
        chain.doFilter(request, response);
    }

    static boolean hostAllowed(String host) {
        if (host == null) {
            return false;
        }
        String value = host.trim().toLowerCase();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            value = end > 0 ? value.substring(0, end + 1) : value;
        } else {
            int colon = value.indexOf(':');
            if (colon > 0) {
                value = value.substring(0, colon);
            }
        }
        return value.equals("127.0.0.1") || value.equals("localhost") || value.equals("[::1]");
    }

    static boolean loopbackPeer(String remoteAddr) {
        return "127.0.0.1".equals(remoteAddr)
                || "::1".equals(remoteAddr)
                || "0:0:0:0:0:0:0:1".equals(remoteAddr)
                || "::ffff:127.0.0.1".equals(remoteAddr);
    }

    static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    static void clearCookie(HttpServletResponse response, String name) {
        response.addHeader("Set-Cookie", name + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
    }
}
