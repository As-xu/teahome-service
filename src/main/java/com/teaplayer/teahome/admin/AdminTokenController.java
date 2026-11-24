package com.teaplayer.teahome.admin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** API tokens and console users. Secrets are shown exactly once. */
@Controller
@Profile("admin")
public class AdminTokenController {

    private final JdbcClient jdbc;
    private final AdminPasswordService passwords;
    private final AdminAuditService audit;
    private final SecureRandom random = new SecureRandom();

    public AdminTokenController(JdbcClient jdbc, AdminPasswordService passwords, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.audit = audit;
    }

    @GetMapping("/admin/tokens")
    public String list(@RequestParam(value = "saved", required = false) String saved,
                       @RequestParam(value = "error", required = false) String error,
                       Model model) {
        model.addAttribute("tokens", jdbc.sql("""
                SELECT id, label, token_prefix, scopes, status, created_by, created_at,
                       expires_at, last_used_at, revoked_at
                  FROM teaplayer_api_token ORDER BY id DESC
                """).query().listOfRows());
        model.addAttribute("users", jdbc.sql("""
                SELECT id, username, display_name, role, status, must_change_password,
                       last_login_at, created_at
                  FROM teaplayer_admin_user ORDER BY id
                """).query().listOfRows());
        model.addAttribute("saved", saved);
        model.addAttribute("error", error);
        return "admin/tokens";
    }

    @PostMapping("/admin/tokens")
    public String create(@RequestParam("label") String label,
                         @RequestParam(value = "read", defaultValue = "false") boolean read,
                         @RequestParam(value = "admin", defaultValue = "false") boolean admin,
                         @RequestParam(value = "expiresDays", defaultValue = "0") int expiresDays,
                         HttpServletRequest request, RedirectAttributes redirect) {
        AdminSessionInfo session = AdminModelAdvice.requireWriter(request);
        AdminModelAdvice.requireSudo(session);
        List<String> scopes = new ArrayList<>();
        if (read) {
            scopes.add("read");
        }
        if (admin) {
            scopes.add("admin");
        }
        if (scopes.isEmpty()) {
            return "redirect:/admin/tokens?error=" + enc("至少选择一个 scope");
        }
        String token = newToken();
        String prefix = token.substring(4, 12);
        jdbc.sql("""
                INSERT INTO teaplayer_api_token (label, token_hash, token_prefix, scopes,
                                                 created_by, expires_at)
                VALUES (:label, :hash, :prefix, CAST(:scopes AS text[]), :by,
                        CASE WHEN :days > 0 THEN now() + (:days || ' days')::interval ELSE NULL END)
                """)
                .param("label", label)
                .param("hash", AdminHashes.sha256Hex(token))
                .param("prefix", prefix)
                .param("scopes", "{" + String.join(",", scopes) + "}")
                .param("by", "admin:" + session.username())
                .param("days", expiresDays)
                .update();
        audit.ok(session, "token.create", "api_token", prefix, null, null, "scopes=" + scopes, request);
        redirect.addFlashAttribute("newToken", token);
        return "redirect:/admin/tokens?saved=" + enc("令牌已创建，只显示一次");
    }

    @PostMapping("/admin/tokens/{id}/revoke")
    public String revoke(@PathVariable("id") long id, HttpServletRequest request) {
        AdminSessionInfo session = AdminModelAdvice.requireWriter(request);
        AdminModelAdvice.requireSudo(session);
        jdbc.sql("""
                UPDATE teaplayer_api_token
                   SET status = 'revoked', revoked_at = now(), revoked_by = :by
                 WHERE id = :id AND status = 'active'
                """).param("by", "admin:" + session.username()).param("id", id).update();
        audit.ok(session, "token.revoke", "api_token", String.valueOf(id), null, null, null, request);
        return "redirect:/admin/tokens?saved=已吊销";
    }

    @PostMapping("/admin/users")
    public String createUser(@RequestParam("username") String username,
                             @RequestParam(value = "displayName", defaultValue = "") String displayName,
                             @RequestParam("role") String role,
                             @RequestParam("password") String password,
                             HttpServletRequest request) {
        AdminSessionInfo session = AdminModelAdvice.requireWriter(request);
        AdminModelAdvice.requireSudo(session);
        if (!role.equals("owner") && !role.equals("admin") && !role.equals("viewer")) {
            return "redirect:/admin/tokens?error=" + enc("角色不合法");
        }
        if (password.length() < 12) {
            return "redirect:/admin/tokens?error=" + enc("密码至少 12 位");
        }
        String hash = passwords.hash(password);
        try {
            jdbc.sql("""
                    INSERT INTO teaplayer_admin_user
                        (username, display_name, password_hash, role, status, must_change_password,
                         password_changed_at, created_by)
                    VALUES (:username, :display, :hash, :role, 'active', false, now(), :by)
                    """)
                    .param("username", username)
                    .param("display", displayName.isBlank() ? username : displayName)
                    .param("hash", hash)
                    .param("role", role)
                    .param("by", "admin:" + session.username())
                    .update();
        } catch (RuntimeException e) {
            return "redirect:/admin/tokens?error=" + enc("用户名不合法或已存在");
        }
        audit.ok(session, "admin_user.create", "admin_user", username, null, null, "role=" + role, request);
        return "redirect:/admin/tokens?saved=" + enc("已创建 " + username);
    }

    @PostMapping("/admin/users/{id}/password")
    public String resetPassword(@PathVariable("id") long id,
                                @RequestParam("password") String password,
                                HttpServletRequest request) {
        AdminSessionInfo session = AdminModelAdvice.requireWriter(request);
        AdminModelAdvice.requireSudo(session);
        if (password.length() < 12) {
            return "redirect:/admin/tokens?error=" + enc("密码至少 12 位");
        }
        jdbc.sql("""
                UPDATE teaplayer_admin_user
                   SET password_hash = :hash, must_change_password = false,
                       password_changed_at = now(), updated_at = now(), revision = revision + 1
                 WHERE id = :id
                """).param("hash", passwords.hash(password)).param("id", id).update();
        audit.ok(session, "admin_user.password.reset", "admin_user", String.valueOf(id),
                null, null, null, request);
        return "redirect:/admin/tokens?saved=密码已重置";
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String id = Long.toHexString(random.nextLong()).substring(0, 8);
        return "ths_" + id + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
