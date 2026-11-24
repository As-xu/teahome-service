package com.teaplayer.teahome.admin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Read-only audit browser. */
@Controller
@Profile("admin")
public class AdminAuditController {

    private final JdbcClient jdbc;

    public AdminAuditController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/admin/audit")
    public String audit(@RequestParam(value = "action", defaultValue = "") String action,
                        @RequestParam(value = "actor", defaultValue = "") String actor,
                        @RequestParam(value = "result", defaultValue = "") String result,
                        Model model) {
        model.addAttribute("rows", jdbc.sql("""
                SELECT occurred_at, actor_type, actor_label, action, target_type, target_id,
                       result, reason, request_id, error_code
                  FROM teaplayer_audit_log
                 WHERE (:action = '' OR action LIKE '%' || :action || '%')
                   AND (:actor = '' OR coalesce(actor_label, '') LIKE '%' || :actor || '%')
                   AND (:result = '' OR result = :result)
                 ORDER BY id DESC LIMIT 200
                """)
                .param("action", action).param("actor", actor).param("result", result)
                .query().listOfRows());
        model.addAttribute("action", action);
        model.addAttribute("actor", actor);
        model.addAttribute("result", result);
        return "admin/audit";
    }
}
