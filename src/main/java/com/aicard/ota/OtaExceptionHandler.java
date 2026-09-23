package com.aicard.ota;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 仅处理 {@link OtaException}，返回协议约定格式 {"error_code","message"}；不影响其它接口的默认异常行为。 */
@RestControllerAdvice
public class OtaExceptionHandler {

    public record ErrorBody(String errorCode, String message) {}

    @ExceptionHandler(OtaException.class)
    public ResponseEntity<ErrorBody> handle(OtaException e) {
        return ResponseEntity.status(e.getStatus()).body(new ErrorBody(e.getErrorCode(), e.getMessage()));
    }
}
