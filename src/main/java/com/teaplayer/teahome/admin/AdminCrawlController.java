package com.teaplayer.teahome.admin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.concurrent.Executor;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.teaplayer.teahome.crawl.CrawlService;

/** Recent crawl runs, failures and a rate-limited manual trigger. */
@Controller
@Profile("admin")
public class AdminCrawlController {

    private final JdbcClient jdbc;
    private final CrawlService crawlService;
    private final Executor executor;
    private final AdminAuditService audit;

    public AdminCrawlController(JdbcClient jdbc, CrawlService crawlService,
                                @Qualifier("adminExecutor") Executor executor,
                                AdminAuditService audit) {
        this.jdbc = jdbc;
        this.crawlService = crawlService;
        this.executor = executor;
        this.audit = audit;
    }

    @GetMapping("/admin/crawl")
    public String list(@RequestParam(value = "saved", required = false) String saved,
                       @RequestParam(value = "error", required = false) String error,
                       Model model) {
        model.addAttribute("runs", jdbc.sql("""
                SELECT id, run_kind, status, window_from, window_to, source_total, source_ok,
                       source_failed, items_fetched, items_upserted, failures, duration_ms,
                       started_at, finished_at, triggered_by, error_code
                  FROM teaplayer_crawl_run ORDER BY id DESC LIMIT 60
                """).query().listOfRows());
        model.addAttribute("saved", saved);
        model.addAttribute("error", error);
        return "admin/crawl";
    }

    @PostMapping("/admin/crawl/trigger")
    public String trigger(HttpServletRequest request) {
        AdminSessionInfo session = AdminModelAdvice.requireWriter(request);
        AdminModelAdvice.requireSudo(session);
        OffsetDateTime last = jdbc.sql("SELECT max(started_at) FROM teaplayer_crawl_run WHERE run_kind = 'hourly'")
                .query(OffsetDateTime.class).optional().orElse(null);
        if (last != null && last.isAfter(OffsetDateTime.now().minusMinutes(10))) {
            return "redirect:/admin/crawl?error=" + enc("10 分钟内已触发过，请稍后");
        }
        try {
            executor.execute(() -> {
                try {
                    crawlService.run("hourly", "manual");
                } catch (RuntimeException ignored) {
                    // The run row records the failure; the console must not crash.
                }
            });
        } catch (RuntimeException rejected) {
            return "redirect:/admin/crawl?error=" + enc("抓取队列繁忙，请稍后重试");
        }
        audit.ok(session, "crawl.trigger", "crawl_run", null, null, null, "manual hourly", request);
        return "redirect:/admin/crawl?saved=" + enc("已触发 hourly，稍后刷新查看结果");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
