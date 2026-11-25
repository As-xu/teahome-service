package com.teaplayer.teahome.admin;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.teaplayer.teahome.api.img.ImageCacheGc;
import com.teaplayer.teahome.ops.AuditRetentionJob;

/**
 * 维护页的渲染与手动触发：模板能真正渲染（Thymeleaf 语法错误会在此暴露），
 * 三个 POST 各自调用对应任务并写审计。
 */
@WebMvcTest(AdminMaintenanceController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("admin")
@Import({AdminModelAdvice.class, AdminErrorAdvice.class})
class AdminMaintenanceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JdbcClient jdbc;

    @MockitoBean
    private ImageCacheGc imageCacheGc;

    @MockitoBean
    private AuditRetentionJob auditRetention;

    @MockitoBean
    private AdminSessionService sessions;

    @MockitoBean
    private AdminAuditService audit;

    private AdminSessionInfo session;

    @BeforeEach
    void setUp() {
        session = new AdminSessionInfo(UUID.randomUUID(), 7L, "owner", "Owner", "owner", "hash",
                Instant.now().plusSeconds(3600), Instant.now(), Instant.now().plusSeconds(600));
    }

    @SuppressWarnings("unchecked")
    private void stubPageQueries() {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.ResultQuerySpec result = mock(JdbcClient.ResultQuerySpec.class);
        when(spec.param(anyString(), any())).thenReturn(spec);
        when(spec.query()).thenReturn(result);
        when(result.singleRow()).thenReturn(
                Map.of("ok_rows", 12, "other_rows", 1, "total_bytes", 3L * 1024 * 1024),
                Map.of("rows", 480, "oldest", Instant.now()),
                Map.of("total", 5, "active", 2));
        when(jdbc.sql(anyString())).thenReturn(spec);
    }

    @Test
    void rendersMaintenancePageWithCacheAuditAndSessionNumbers() throws Exception {
        stubPageQueries();
        when(imageCacheGc.ttlDays()).thenReturn(30);
        when(imageCacheGc.maxBytes()).thenReturn(20L * 1024 * 1024 * 1024);
        when(auditRetention.retentionDays()).thenReturn(180);

        mockMvc.perform(get("/admin/maintenance")
                        .requestAttr(AdminAttributes.SESSION, session)
                        .requestAttr(AdminAttributes.CSRF, "csrf-token-123"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/maintenance"))
                .andExpect(content().string(containsString("维护")))
                .andExpect(content().string(containsString("3.0 MiB")))
                .andExpect(content().string(containsString("180")))
                .andExpect(content().string(containsString("csrf-token-123")));
    }

    @Test
    void imageGcRunsAndRedirectsWithSavedMessage() throws Exception {
        when(imageCacheGc.runNow()).thenReturn(
                new ImageCacheGc.GcResult(12, 3, 1024 * 1024, 20L * 1024 * 1024 * 1024, 30));

        mockMvc.perform(post("/admin/maintenance/images/gc")
                        .requestAttr(AdminAttributes.SESSION, session)
                        .requestAttr(AdminAttributes.CSRF, "csrf-token-123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/maintenance?saved=*"));

        verify(audit).ok(eq(session), eq("maintenance.image_gc"), eq("image_cache"),
                isNull(), isNull(), eq("{\"deleted\":3}"), eq("manual image cache gc"), any());
    }

    @Test
    void auditPurgeAndSessionPurgeReportTheirCounts() throws Exception {
        when(auditRetention.runNow()).thenReturn(new AuditRetentionJob.PurgeResult(42, 180));
        when(sessions.purgeExpired()).thenReturn(6);

        mockMvc.perform(post("/admin/maintenance/audit/purge")
                        .requestAttr(AdminAttributes.SESSION, session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/maintenance?saved=*"));
        mockMvc.perform(post("/admin/maintenance/sessions/purge")
                        .requestAttr(AdminAttributes.SESSION, session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/maintenance?saved=*"));

        verify(audit).ok(eq(session), eq("maintenance.audit_purge"), eq("audit_log"),
                isNull(), isNull(), eq("{\"deleted\":42}"), eq("manual audit purge"), any());
        verify(audit).ok(eq(session), eq("maintenance.session_purge"), eq("admin_session"),
                isNull(), isNull(), eq("{\"deleted\":6}"), eq("manual session purge"), any());
    }

    @Test
    void postWithoutSudoIsRejected() throws Exception {
        AdminSessionInfo noSudo = new AdminSessionInfo(UUID.randomUUID(), 7L, "owner", "Owner", "owner",
                "hash", Instant.now().plusSeconds(3600), Instant.now(), null);

        mockMvc.perform(post("/admin/maintenance/images/gc")
                        .requestAttr(AdminAttributes.SESSION, noSudo))
                .andExpect(status().isForbidden())
                .andExpect(view().name("admin/error"))
                .andExpect(content().string(containsString("sudo required")));
    }
}
