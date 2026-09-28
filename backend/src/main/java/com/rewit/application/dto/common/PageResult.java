package com.rewit.application.dto.common;

import java.util.List;

/**
 * Estrutura agnóstica para transporte de resultados paginados na camada de aplicação e portas.
 */
public record PageResult<T>(
        List<T> content,
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages,
        boolean isLast
) {
    public static <T> PageResult<T> of(List<T> content, int pageNumber, int pageSize, long totalElements) {
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) totalElements / pageSize) : 0;
        boolean isLast = totalPages == 0 || pageNumber >= totalPages - 1;
        return new PageResult<>(
                content != null ? content : List.of(),
                pageNumber,
                pageSize,
                totalElements,
                totalPages,
                isLast
        );
    }
}
