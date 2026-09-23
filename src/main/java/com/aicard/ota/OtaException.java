package com.aicard.ota;

import org.springframework.http.HttpStatus;

/** OTA 域异常：携带协议约定的 error_code 与 HTTP 状态码，由 {@link OtaExceptionHandler} 统一转 JSON。 */
public class OtaException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public OtaException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
