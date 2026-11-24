package com.teaplayer.teahome.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * 与 Spring MVC 完全一致的应用内路径规范化（解码 + 去除 ;matrix 参数）。
 * 所有安全/限流判断都必须走这里，禁止再直接比较原始 requestURI。
 */
public final class PathGate {

    private static final UrlPathHelper PATHS = UrlPathHelper.defaultInstance;

    private PathGate() {
    }

    public static String path(HttpServletRequest request) {
        String path = PATHS.getPathWithinApplication(request);
        return path == null || path.isEmpty() ? "/" : path;
    }

    public static boolean isV1(String path) {
        return path.equals("/v1") || path.startsWith("/v1/");
    }

    public static boolean isActuator(String path) {
        return path.equals("/actuator") || path.startsWith("/actuator/");
    }
}
