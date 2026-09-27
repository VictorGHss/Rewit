package com.rewit.common.response;

import java.time.Instant;

/**
 * Envelope padronizado para respostas da API REST do Rewit.
 *
 * @param <T> Tipo de dado do payload
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        Instant timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, "Operação realizada com sucesso", Instant.now());
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, data, message, Instant.now());
    }
}
