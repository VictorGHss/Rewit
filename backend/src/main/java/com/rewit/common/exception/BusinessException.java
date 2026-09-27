package com.rewit.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Exceção base de regras de negócio da plataforma Rewit.
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public BusinessException(String message, HttpStatus status, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public BusinessException(String message, String errorCode) {
        this(message, HttpStatus.UNPROCESSABLE_ENTITY, errorCode);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
