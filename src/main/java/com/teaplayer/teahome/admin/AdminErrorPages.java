package com.teaplayer.teahome.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 过滤器层的拒绝页面：过滤器在 DispatcherServlet 之前返回，走不到 ControllerAdvice，
 * 也不应落到 Spring 的 Whitelabel 白页，因此在过滤层直接写一小段可读 HTML。
 */
final class AdminErrorPages {

    private AdminErrorPages() {
    }

    static void write(HttpServletResponse response, int status, String reason, String hint) throws IOException {
        response.setStatus(status);
        response.setContentType("text/html;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                <title>操作未完成 · TeaHomeService</title>
                <style>body{margin:0;background:#f6f4ef;color:#23201c;font:15px/1.6 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif}
                main{max-width:640px;margin:12vh auto;padding:32px;background:#fffdf8;border:1px solid #e6e0d4;border-radius:12px}
                h1{margin:0 0 8px;font-size:22px}.eyebrow{margin:0 0 6px;color:#8a8377;letter-spacing:.12em;font-size:12px}
                .muted{color:#6f685d}.banner{margin:18px 0;padding:12px 14px;border-radius:8px;background:#fdecea;border:1px solid #f3c2bd;color:#8c2f26}
                a{color:#2f6f4f}</style></head><body><main>
                <p class="eyebrow">ADMIN</p><h1>操作未完成</h1>
                <p class="muted">请求在进入页面前被管理端拦截。</p>
                <div class="banner"><b>%d — %s</b></div>
                <p>%s</p>
                <p class="muted">需要重新登录请回 <a href="/admin/login">登录页</a>；查看现状请回 <a href="/admin">概览</a>。</p>
                </main></body></html>
                """.formatted(status, escape(reason), escape(hint)));
    }

    private static String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
