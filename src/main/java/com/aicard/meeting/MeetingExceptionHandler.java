package com.aicard.meeting;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 仅处理 {@link MeetingException}，返回协议约定格式 {"error_code","message","retryable"}。 */
@RestControllerAdvice
public class MeetingExceptionHandler {

    public record ErrorBody(String errorCode, String message, boolean retryable) {}

    @ExceptionHandler(MeetingException.class)
    public ResponseEntity<ErrorBody> handle(MeetingException e) {
        return ResponseEntity.status(e.getStatus())
                .body(new ErrorBody(e.getErrorCode(), e.getMessage(), e.isRetryable()));
    }
}
