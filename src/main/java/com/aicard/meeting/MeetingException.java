package com.aicard.meeting;

import org.springframework.http.HttpStatus;

/** 会议设备接口异常：携带协议约定的 error_code / retryable，由 {@link MeetingExceptionHandler} 统一转 JSON。 */
public class MeetingException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final boolean retryable;

    public MeetingException(HttpStatus status, String errorCode, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public static MeetingException invalid(String message) {
        return new MeetingException(HttpStatus.BAD_REQUEST, "E_MEETING_INVALID_ARGUMENT", message, false);
    }

    public static MeetingException notFound() {
        return new MeetingException(HttpStatus.NOT_FOUND, "E_MEETING_NOT_FOUND", "meeting not found", false);
    }

    public static MeetingException state(String message) {
        return new MeetingException(HttpStatus.CONFLICT, "E_MEETING_STATE", message, false);
    }
}
