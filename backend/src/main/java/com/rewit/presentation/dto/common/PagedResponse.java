package com.rewit.presentation.dto.common;

import java.util.List;

/**
 * Envelope padronizado para respostas paginadas da API REST do Rewit.
 */
public record PagedResponse<T>(
        List<T> content,
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages,
        boolean isLast
) {
    public static <T> PagedResponse<T> of(List<T> content, int pageNumber, int pageSize, long totalElements) {
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) totalElements / pageSize) : 0;
        boolean isLast = totalPages == 0 || pageNumber >= totalPages - 1;
        return new PagedResponse<>(
                content != null ? content : List.of(),
                pageNumber,
                pageSize,
                totalElements,
                totalPages,
                isLast
        );
    }
}
