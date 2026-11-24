package com.teaplayer.teahome.admin;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.server.ResponseStatusException;

/** Injects the resolved session, CSRF token and current path into every admin template. */
@ControllerAdvice(annotations = Controller.class)
@Profile("admin")
public class AdminModelAdvice {

    @ModelAttribute("adminUser")
    public AdminSessionInfo adminUser(HttpServletRequest request) {
        return (AdminSessionInfo) request.getAttribute(AdminAttributes.SESSION);
    }

    @ModelAttribute("csrf")
    public String csrf(HttpServletRequest request) {
        return (String) request.getAttribute(AdminAttributes.CSRF);
    }

    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return com.teaplayer.teahome.config.PathGate.path(request);
    }

    static AdminSessionInfo requireSession(HttpServletRequest request) {
        AdminSessionInfo session = (AdminSessionInfo) request.getAttribute(AdminAttributes.SESSION);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return session;
    }

    static AdminSessionInfo requireWriter(HttpServletRequest request) {
        AdminSessionInfo session = requireSession(request);
        if (!session.canWrite()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "viewer is read-only");
        }
        return session;
    }

    static void requireSudo(AdminSessionInfo session) {
        if (!session.sudoValid(java.time.Instant.now())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "sudo required");
        }
    }

    static String safeNext(String next) {
        if (next == null || !next.startsWith("/admin") || next.startsWith("//")) {
            return "/admin";
        }
        return next;
    }
}
