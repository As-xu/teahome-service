package com.teaplayer.teahome.admin;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

/**
 * 管理端的 4xx/5xx 说明页：把 ResponseStatusException 的 reason（如 "sudo required"、
 * "密码错误"）渲染成可读的中文页面，而不是 Spring 默认的 Whitelabel 白页。
 */
@ControllerAdvice
@Profile("admin")
public class AdminErrorAdvice {

    @ExceptionHandler(ResponseStatusException.class)
    public String handle(ResponseStatusException ex, Model model, HttpServletResponse response) {
        int status = ex.getStatusCode().value();
        response.setStatus(status);
        model.addAttribute("status", status);
        model.addAttribute("reason", ex.getReason() == null ? "" : ex.getReason());
        model.addAttribute("hint", hint(status, ex.getReason()));
        return "admin/error";
    }

    private static String hint(int status, String reason) {
        if (status == 429) {
            return "尝试次数过多，请稍后再试。";
        }
        if (status == 403 && "sudo required".equals(reason)) {
            return "这次操作需要提权：回到「概览」或「维护」页，在提示条里输入一次密码，即可获得 5 分钟 sudo。";
        }
        if (status == 403) {
            return "当前会话没有执行该操作的权限（viewer 只读、非 owner，或密码校验未通过）。";
        }
        return "可以返回上一页重试；若持续出现，请查看 admin 容器日志。";
    }
}
