package com.teaplayer.teahome.api;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e, HttpServletRequest request) {
        return build(e.status(), e.code(), e.getMessage(), e.retryAfterMs(), request);
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<Map<String, Object>> badRequest(Exception e, HttpServletRequest request) {
        return build(400, "bad_request", "请求参数不正确", null, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
        String requestId = requestId(request);
        log.error("请求异常 requestId={} path={}", requestId, request.getRequestURI(), e);
        return build(500, "internal_error", "服务器内部错误", null, request);
    }

    private ResponseEntity<Map<String, Object>> build(int status, String code, String message,
                                                      Long retryAfterMs, HttpServletRequest request) {
        String requestId = requestId(request);
        Map<String, Object> body = Map.of(
                "error", Map.of(
                        "code", code,
                        "message", message == null ? "" : message,
                        "requestId", requestId,
                        "serverTime", OffsetDateTime.now(ZONE).toString()),
                "retryAfterMs", retryAfterMs == null ? 0 : retryAfterMs);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .header("X-Request-Id", requestId);
        if (retryAfterMs != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, retryAfterMs / 1000)));
        }
        return builder.body(body);
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute("teahome.requestId");
        return value == null ? "-" : value.toString();
    }
}
