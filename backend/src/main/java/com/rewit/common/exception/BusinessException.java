package com.rewit.common.exception;

import org.springframework.http.HttpStatus;

import java.time.Duration;

/**
 * Exceção base de regras de negócio da plataforma Rewit.
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final Duration retryAfter;

    public BusinessException(String message, HttpStatus status, String errorCode, Duration retryAfter) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.retryAfter = retryAfter;
    }

    public BusinessException(String message, HttpStatus status, String errorCode) {
        this(message, status, errorCode, null);
    }

    public BusinessException(String message, String errorCode) {
        this(message, HttpStatus.UNPROCESSABLE_CONTENT, errorCode);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
