package com.teaplayer.teahome.admin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Editable hot configuration keys, with sudo + audit on writes. */
@Controller
@Profile("admin")
public class AdminConfigController {

    private final AdminConfigService configs;

    public AdminConfigController(AdminConfigService configs) {
        this.configs = configs;
    }

    @GetMapping("/admin/config")
    public String list(@RequestParam(value = "saved", required = false) String saved,
                       @RequestParam(value = "error", required = false) String error,
                       Model model) {
        model.addAttribute("rows", configs.list());
        model.addAttribute("saved", saved);
        model.addAttribute("error", error);
        return "admin/config";
    }

    @PostMapping("/admin/config/{key}")
    public String update(@PathVariable("key") String key,
                         @RequestParam("value") String value,
                         @RequestParam("reason") String reason,
                         @RequestParam(value = "expectedVersion", required = false) Long expectedVersion,
                         HttpServletRequest request) {
        AdminSessionInfo session = AdminModelAdvice.requireWriter(request);
        AdminModelAdvice.requireSudo(session);
        try {
            configs.update(key, value, reason, expectedVersion, session, request);
            return "redirect:/admin/config?saved=" + enc(key);
        } catch (RuntimeException e) {
            return "redirect:/admin/config?error=" + enc(e.getMessage());
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
