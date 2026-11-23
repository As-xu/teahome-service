package com.teaplayer.teahome.api;

/** 业务异常：携带 HTTP 状态与稳定错误码，由 ApiExceptionHandler 统一转成错误 envelope。 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final Long retryAfterMs;

    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(int status, String code, String message, Long retryAfterMs) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterMs = retryAfterMs;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Long retryAfterMs() {
        return retryAfterMs;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(401, "unauthorized", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "not_found", message);
    }

    public static ApiException gone(String message) {
        return new ApiException(410, "gone", message);
    }

    public static ApiException tooMany(String message, long retryAfterMs) {
        return new ApiException(429, "rate_limited", message, retryAfterMs);
    }

    public static ApiException upstream(String message) {
        return new ApiException(502, "upstream_error", message);
    }
}
