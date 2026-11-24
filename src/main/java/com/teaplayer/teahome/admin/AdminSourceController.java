package com.teaplayer.teahome.admin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Source switches; disabling is audited and requires sudo. */
@Controller
@Profile("admin")
public class AdminSourceController {

    private final JdbcClient jdbc;
    private final AdminAuditService audit;

    public AdminSourceController(JdbcClient jdbc, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/admin/sources")
    public String list(@RequestParam(value = "saved", required = false) String saved, Model model) {
        model.addAttribute("sources", jdbc.sql("""
                SELECT id, name, kind, base_url, is_enabled, priority, consecutive_failures,
                       last_ok_at, last_error_at, last_error_code, last_error_detail
                  FROM teaplayer_source WHERE deleted_at IS NULL ORDER BY priority, id
                """).query().listOfRows());
        model.addAttribute("saved", saved);
        return "admin/sources";
    }

    @PostMapping("/admin/sources/{id}/toggle")
    public String toggle(@PathVariable("id") String id,
                         @RequestParam("enabled") boolean enabled,
                         @RequestParam("reason") String reason,
                         HttpServletRequest request) {
        AdminSessionInfo session = AdminModelAdvice.requireWriter(request);
        AdminModelAdvice.requireSudo(session);
        if (reason == null || reason.isBlank()) {
            return "redirect:/admin/sources?saved=" + enc("reason required");
        }
        jdbc.sql("""
                UPDATE teaplayer_source
                   SET is_enabled = :enabled, updated_at = now(), revision = revision + 1
                 WHERE id = :id
                """).param("enabled", enabled).param("id", id).update();
        audit.ok(session, "source.toggle", "source", id,
                "{\"enabled\":" + (!enabled) + "}", "{\"enabled\":" + enabled + "}",
                reason, request);
        return "redirect:/admin/sources?saved=" + enc(id + " → " + (enabled ? "启用" : "停用"));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
