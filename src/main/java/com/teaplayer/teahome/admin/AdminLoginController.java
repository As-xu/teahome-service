package com.teaplayer.teahome.admin;

import java.time.OffsetDateTime;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Login, logout and short-lived sudo re-authentication. */
@Controller
@Profile("admin")
public class AdminLoginController {

    private final JdbcClient jdbc;
    private final AdminSessionService sessions;
    private final AdminPasswordService passwords;
    private final AdminLoginThrottle throttle;
    private final AdminAuditService audit;
    private final AdminProperties props;

    public AdminLoginController(JdbcClient jdbc, AdminSessionService sessions,
                                AdminPasswordService passwords, AdminLoginThrottle throttle,
                                AdminAuditService audit, AdminProperties props) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.passwords = passwords;
        this.throttle = throttle;
        this.audit = audit;
        this.props = props;
    }

    @GetMapping("/admin/login")
    public String login(Model model) {
        long users = jdbc.sql("SELECT count(*) FROM teaplayer_admin_user").query(Long.class).single();
        model.addAttribute("userCount", users);
        return "admin/login";
    }

    @PostMapping("/admin/login")
    public String doLogin(@RequestParam("username") String username,
                          @RequestParam("password") String password,
                          HttpServletRequest request,
                          HttpServletResponse response,
                          Model model) {
        String ip = request.getRemoteAddr();
        long users = jdbc.sql("SELECT count(*) FROM teaplayer_admin_user").query(Long.class).single();
        if (!throttle.allowed(ip)) {
            model.addAttribute("userCount", users);
            model.addAttribute("error", "尝试次数过多，请 60 秒后再试");
            response.setStatus(429);
            return "admin/login";
        }
        Optional<UserRow> user = jdbc.sql("""
                SELECT id, username, display_name, password_hash, role, status
                  FROM teaplayer_admin_user
                 WHERE username = :username
                """).param("username", username)
                .query((rs, row) -> new UserRow(rs.getLong("id"), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("password_hash"),
                        rs.getString("role"), rs.getString("status")))
                .optional();
        boolean ok = user.isPresent() && "active".equals(user.get().status())
                && passwords.matches(password, user.get().hash());
        if (!ok) {
            throttle.failure(ip);
            audit.denied(username, "admin.login", "admin_user", username, "bad credentials", request, "bad_credentials");
            model.addAttribute("userCount", users);
            model.addAttribute("error", "用户名或密码错误");
            response.setStatus(401);
            return "admin/login";
        }
        UserRow row = user.get();
        AdminSessionService.Issued issued = sessions.issue(row.id(), ip,
                truncate(request.getHeader("User-Agent"), 300));
        setCookie(response, AdminAccessFilter.SID_COOKIE, issued.token(),
                props.getAbsoluteHours() * 3600);
        setCookie(response, AdminAccessFilter.CSRF_COOKIE, issued.csrfToken(),
                props.getAbsoluteHours() * 3600);
        jdbc.sql("""
                UPDATE teaplayer_admin_user
                   SET last_login_at = now(), last_login_ip = CAST(:ip AS inet)
                 WHERE id = :id
                """).param("ip", ip).param("id", row.id()).update();
        throttle.success(ip);
        audit.ok(issued.info(), "admin.login", "admin_user", row.username(), null, null,
                "password login", request);
        return "redirect:/admin";
    }

    @PostMapping("/admin/logout")
    public String logout(HttpServletRequest request, HttpServletResponse response) {
        AdminSessionInfo session = AdminModelAdvice.requireSession(request);
        sessions.revokeByRaw(AdminAccessFilter.cookie(request, AdminAccessFilter.SID_COOKIE));
        audit.ok(session, "admin.logout", "admin_user", session.username(), null, null, null, request);
        AdminAccessFilter.clearCookie(response, AdminAccessFilter.SID_COOKIE);
        AdminAccessFilter.clearCookie(response, AdminAccessFilter.CSRF_COOKIE);
        return "redirect:/admin/login";
    }

    @PostMapping("/admin/sudo")
    public String sudo(@RequestParam(value = "next", required = false) String next,
                       @RequestParam("password") String password,
                       HttpServletRequest request) {
        AdminSessionInfo session = AdminModelAdvice.requireSession(request);
        UserRow row = jdbc.sql("SELECT id, password_hash FROM teaplayer_admin_user WHERE id = :id")
                .param("id", session.userId())
                .query((rs, n) -> new UserRow(rs.getLong("id"), null, null,
                        rs.getString("password_hash"), null, null))
                .single();
        if (!passwords.matches(password, row.hash())) {
            audit.denied(session.username(), "admin.sudo", "admin_user", session.username(),
                    "bad password", request, "bad_credentials");
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "密码错误");
        }
        sessions.markSudo(session.id());
        audit.ok(session, "admin.sudo", "admin_user", session.username(), null, null, "sudo granted", request);
        return "redirect:" + AdminModelAdvice.safeNext(next);
    }

    private static void setCookie(HttpServletResponse response, String name, String value, int maxAge) {
        response.addHeader("Set-Cookie", name + "=" + value + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAge);
    }

    private record UserRow(long id, String username, String displayName, String hash,
                           String role, String status) {
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
