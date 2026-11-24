package com.teaplayer.teahome.admin;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Append-only audit writer for console actions. */
@Service
@Profile("admin")
public class AdminAuditService {

    private final JdbcClient jdbc;

    public AdminAuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void ok(AdminSessionInfo actor, String action, String targetType, String targetId,
                   String beforeJson, String afterJson, String reason, HttpServletRequest request) {
        record(actor, action, targetType, targetId, "ok", beforeJson, afterJson, reason, request, null);
    }

    public void denied(String username, String action, String targetType, String targetId,
                       String reason, HttpServletRequest request, String errorCode) {
        jdbc.sql("""
                INSERT INTO teaplayer_audit_log
                    (actor_type, actor_id, actor_label, action, target_type, target_id,
                     result, reason, request_id, ip, user_agent, error_code)
                VALUES ('admin', :actorId, :label, :action, :targetType, :targetId,
                        'denied', :reason, :requestId, CAST(:ip AS inet), :ua, :errorCode)
                """)
                .param("actorId", username == null ? "anonymous" : "user:" + username)
                .param("label", username)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("reason", reason)
                .param("requestId", (String) request.getAttribute(AdminAttributes.REQUEST_ID))
                .param("ip", request.getRemoteAddr())
                .param("ua", truncate(request.getHeader("User-Agent"), 300))
                .param("errorCode", errorCode)
                .update();
    }

    private void record(AdminSessionInfo actor, String action, String targetType, String targetId,
                        String result, String beforeJson, String afterJson, String reason,
                        HttpServletRequest request, String errorCode) {
        jdbc.sql("""
                INSERT INTO teaplayer_audit_log
                    (actor_type, actor_id, actor_label, action, target_type, target_id,
                     result, "before", "after", changed_keys, reason, request_id, ip, user_agent, error_code)
                VALUES ('admin', :actorId, :label, :action, :targetType, CAST(:targetId AS text), :result,
                        CAST(:before AS jsonb), CAST(:after AS jsonb),
                        CASE WHEN CAST(:targetId AS text) IS NULL THEN NULL
                             ELSE ARRAY[CAST(:targetId AS text)]::text[] END,
                        :reason, :requestId, CAST(:ip AS inet), :ua, :errorCode)
                """)
                .param("actorId", "user:" + actor.userId())
                .param("label", actor.username())
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("result", result)
                .param("before", beforeJson)
                .param("after", afterJson)
                .param("reason", reason)
                .param("requestId", (String) request.getAttribute(AdminAttributes.REQUEST_ID))
                .param("ip", request.getRemoteAddr())
                .param("ua", truncate(request.getHeader("User-Agent"), 300))
                .param("errorCode", errorCode)
                .update();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
