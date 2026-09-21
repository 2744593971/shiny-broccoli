package com.duli.security;

import com.duli.grace.result.GraceJSONResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class AccessExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<GraceJSONResult> handle(ResponseStatusException exception) {
        GraceJSONResult result = GraceJSONResult.errorMsg(exception.getReason());
        result.setStatus(exception.getStatus().value());
        return ResponseEntity.status(exception.getStatus()).body(result);
    }
}
