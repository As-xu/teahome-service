package com.teaplayer.teahome.api;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e, HttpServletRequest request) {
        return build(404, "not_found", "资源不存在", null, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException e,
                                                               HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> response = build(405, "method_not_allowed", "请求方法不支持", null, request);
        return ResponseEntity.status(405)
                .headers(response.getHeaders())
                .allow(e.getSupportedHttpMethods() == null ? new org.springframework.http.HttpMethod[0]
                        : e.getSupportedHttpMethods().toArray(new org.springframework.http.HttpMethod[0]))
                .body(response.getBody());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> unsupportedMedia(HttpMediaTypeNotSupportedException e,
                                                               HttpServletRequest request) {
        return build(415, "unsupported_media_type", "不支持的请求类型", null, request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Map<String, Object>> notAcceptable(HttpMediaTypeNotAcceptableException e,
                                                            HttpServletRequest request) {
        return build(406, "not_acceptable", "无法满足 Accept", null, request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException e, HttpServletRequest request) {
        int status = e.getStatusCode().value();
        String code = switch (status) {
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 429 -> "too_many_requests";
            default -> "error";
        };
        return build(status, code, e.getReason(), null, request);
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
