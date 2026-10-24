package com.airtribe.wallet.exception;

import com.airtribe.wallet.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WalletException.class)
    public ResponseEntity<ErrorResponse> handleWalletException(WalletException ex, HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        log.warn("WalletException [{}]: {} (request_id={})", ex.getCode(), ex.getMessage(), requestId);
        ErrorResponse body = ErrorResponse.of(ex.getCode(), ex.getMessage(), requestId);
        return ResponseEntity.status(ex.getHttpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        log.warn("Validation failed: {} (request_id={})", message, requestId);
        ErrorResponse body = ErrorResponse.of("VALIDATION_ERROR", message, requestId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(org.springframework.web.servlet.resource.NoResourceFoundException ex, HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        ErrorResponse body = ErrorResponse.of("RESOURCE_NOT_FOUND", ex.getMessage(), requestId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        log.error("Unhandled exception occurred (request_id={}): {}", requestId, ex.getMessage(), ex);
        ErrorResponse body = ErrorResponse.of("INTERNAL_SERVER_ERROR", "An unexpected error occurred. Reference: " + requestId, requestId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private String resolveRequestId(HttpServletRequest request) {
        String reqId = request.getHeader("X-Request-Id");
        if (reqId == null || reqId.isBlank()) {
            reqId = UUID.randomUUID().toString();
        }
        return reqId;
    }
}
