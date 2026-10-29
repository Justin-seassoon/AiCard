package com.aicard.diagnostic;

import org.springframework.http.HttpStatus;

/** 设备诊断日志异常：按协议约定返回 error_code / retryable。 */
public class DeviceLogException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final boolean retryable;

    public DeviceLogException(HttpStatus status, String errorCode, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public HttpStatus getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public boolean isRetryable() { return retryable; }

    public static DeviceLogException auth() {
        return new DeviceLogException(HttpStatus.UNAUTHORIZED, "E_DEVICE_LOG_AUTH",
                "device credential invalid", false);
    }

    public static DeviceLogException tooLarge(String message) {
        return new DeviceLogException(HttpStatus.PAYLOAD_TOO_LARGE, "E_DEVICE_LOG_TOO_LARGE",
                message, false);
    }

    public static DeviceLogException invalid(String message) {
        return new DeviceLogException(HttpStatus.BAD_REQUEST, "E_DEVICE_LOG_INVALID_ARGUMENT",
                message, false);
    }
}
