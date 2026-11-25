package com.teaplayer.teahome.admin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.teaplayer.teahome.api.img.ImageCacheGc;
import com.teaplayer.teahome.ops.AuditRetentionJob;

/** 运维维护入口：查看缓存/审计/会话体量，并手动触发一次清理。 */
@Controller
@Profile("admin")
public class AdminMaintenanceController {

    private final JdbcClient jdbc;
    private final ImageCacheGc imageCacheGc;
    private final AuditRetentionJob auditRetention;
    private final AdminSessionService sessions;
    private final AdminAuditService audit;

    public AdminMaintenanceController(JdbcClient jdbc, ImageCacheGc imageCacheGc,
                                      AuditRetentionJob auditRetention, AdminSessionService sessions,
                                      AdminAuditService audit) {
        this.jdbc = jdbc;
        this.imageCacheGc = imageCacheGc;
        this.auditRetention = auditRetention;
        this.sessions = sessions;
        this.audit = audit;
    }

    @GetMapping("/admin/maintenance")
    public String page(@RequestParam(value = "saved", required = false) String saved,
                       @RequestParam(value = "error", required = false) String error,
                       Model model) {
        Map<String, Object> images = images();
        model.addAttribute("images", images);
        model.addAttribute("imageBytesText", human(((Number) images.get("total_bytes")).longValue()));
        model.addAttribute("auditLog", jdbc.sql("""
                SELECT count(*) AS rows, min(occurred_at) AS oldest FROM teaplayer_audit_log
                """).query().singleRow());
        model.addAttribute("adminSessions", jdbc.sql("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE revoked_at IS NULL AND expires_at > now()) AS active
                  FROM teaplayer_admin_session
                """).query().singleRow());
        model.addAttribute("ttlDays", imageCacheGc.ttlDays());
        model.addAttribute("maxBytesText", human(imageCacheGc.maxBytes()));
        model.addAttribute("auditDays", auditRetention.retentionDays());
        model.addAttribute("saved", saved);
        model.addAttribute("error", error);
        return "admin/maintenance";
    }

    private Map<String, Object> images() {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'ok') AS ok_rows,
                       count(*) FILTER (WHERE status <> 'ok') AS other_rows,
                       COALESCE(sum(bytes) FILTER (WHERE status = 'ok'), 0) AS total_bytes,
                       min(fetched_at) FILTER (WHERE status = 'ok') AS oldest,
                       max(fetched_at) FILTER (WHERE status = 'ok') AS newest
                  FROM teaplayer_image_cache
                """).query().singleRow();
    }

    @PostMapping("/admin/maintenance/images/gc")
    public String runImageGc(HttpServletRequest request) {
        AdminSessionInfo session = AdminModelAdvice.requireWriter(request);
        AdminModelAdvice.requireSudo(session);
        try {
            ImageCacheGc.GcResult result = imageCacheGc.runNow();
            audit.ok(session, "maintenance.image_gc", "image_cache", null, null,
                    "{\"deleted\":" + result.deleted() + "}", "manual image cache gc", request);
            return "redirect:/admin/maintenance?saved=" + enc("封面缓存已清理：删除 " + result.deleted()
                    + " 项，剩余 " + human(result.remainingBytes()) + " / 上限 " + human(result.maxBytes()));
        } catch (RuntimeException e) {
            return "redirect:/admin/maintenance?error=" + enc("封面缓存清理失败：" + e.getClass().getSimpleName());
        }
    }

    @PostMapping("/admin/maintenance/audit/purge")
    public String runAuditPurge(HttpServletRequest request) {
        AdminSessionInfo session = AdminModelAdvice.requireWriter(request);
        AdminModelAdvice.requireSudo(session);
        try {
            AuditRetentionJob.PurgeResult result = auditRetention.runNow();
            audit.ok(session, "maintenance.audit_purge", "audit_log", null, null,
                    "{\"deleted\":" + result.deleted() + "}", "manual audit purge", request);
            return "redirect:/admin/maintenance?saved=" + enc("审计日志已清理：删除 " + result.deleted()
                    + " 行（保留 " + result.retentionDays() + " 天）");
        } catch (RuntimeException e) {
            return "redirect:/admin/maintenance?error=" + enc("审计日志清理失败：" + e.getClass().getSimpleName());
        }
    }

    @PostMapping("/admin/maintenance/sessions/purge")
    public String runSessionPurge(HttpServletRequest request) {
        AdminSessionInfo session = AdminModelAdvice.requireWriter(request);
        AdminModelAdvice.requireSudo(session);
        try {
            int deleted = sessions.purgeExpired();
            audit.ok(session, "maintenance.session_purge", "admin_session", null, null,
                    "{\"deleted\":" + deleted + "}", "manual session purge", request);
            return "redirect:/admin/maintenance?saved=" + enc("过期管理会话已清理：删除 " + deleted + " 条");
        } catch (RuntimeException e) {
            return "redirect:/admin/maintenance?error=" + enc("过期管理会话清理失败：" + e.getClass().getSimpleName());
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KiB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MiB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }
}
