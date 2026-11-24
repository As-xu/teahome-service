package com.teaplayer.teahome.admin;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Read-only overview of the service and its data window. */
@Controller
@Profile("admin")
public class AdminOverviewController {

    private final JdbcClient jdbc;

    public AdminOverviewController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/admin")
    public String overview(Model model) {
        Map<String, Object> counts = jdbc.sql("""
                SELECT (SELECT count(*) FROM teaplayer_source_item WHERE status = 'active' AND expires_at > now()) AS items,
                       (SELECT count(*) FROM teaplayer_title_group WHERE item_count > 0 AND expires_at > now()) AS groups,
                       (SELECT count(*) FROM teaplayer_source WHERE is_enabled AND deleted_at IS NULL) AS sources,
                       (SELECT count(*) FROM teaplayer_source_item WHERE expires_at < now()) AS expired,
                       (SELECT max(expires_at) FROM teaplayer_source_item) AS max_expiry,
                       (SELECT max(vod_time) FROM teaplayer_source_item) AS max_vod_time,
                       (SELECT count(*) FROM teaplayer_api_token WHERE status = 'active') AS tokens,
                       (SELECT count(*) FROM teaplayer_admin_user WHERE status = 'active') AS admins
                """).query().singleRow();
        model.addAttribute("counts", counts);
        model.addAttribute("sources", jdbc.sql("""
                SELECT id, name, kind, is_enabled, consecutive_failures, last_ok_at, last_error_code
                  FROM teaplayer_source WHERE deleted_at IS NULL ORDER BY priority, id
                """).query().listOfRows());
        model.addAttribute("runs", jdbc.sql("""
                SELECT id, run_kind, status, source_total, source_failed, items_upserted,
                       duration_ms, started_at
                  FROM teaplayer_crawl_run ORDER BY id DESC LIMIT 6
                """).query().listOfRows());
        model.addAttribute("audits", jdbc.sql("""
                SELECT occurred_at, actor_label, action, target_id, result
                  FROM teaplayer_audit_log ORDER BY id DESC LIMIT 6
                """).query().listOfRows());
        model.addAttribute("schemaVersion", jdbc.sql("""
                SELECT meta_value FROM teaplayer_service_meta WHERE meta_key = 'schema_version'
                """).query(String.class).optional().orElse("?"));
        model.addAttribute("dbSize", jdbc.sql("SELECT pg_size_pretty(pg_database_size(current_database()))")
                .query(String.class).single());
        return "admin/overview";
    }

    @SuppressWarnings("unused")
    private static List<Map<String, Object>> empty() {
        return List.of();
    }
}
