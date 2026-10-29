package com.aicard.diagnostic;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 仅处理 {@link DeviceLogException}，返回 {"error_code","message","retryable"}。 */
@RestControllerAdvice
public class DeviceLogExceptionHandler {

    public record ErrorBody(String errorCode, String message, boolean retryable) {}

    @ExceptionHandler(DeviceLogException.class)
    public ResponseEntity<ErrorBody> handle(DeviceLogException e) {
        return ResponseEntity.status(e.getStatus())
                .body(new ErrorBody(e.getErrorCode(), e.getMessage(), e.isRetryable()));
    }
}
