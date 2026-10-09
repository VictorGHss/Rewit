package com.rewit.application.usecase;

import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * Validação de paginação das listagens de reivindicações (C9), com os códigos já usados nas demais listagens.
 */
final class PlaceClaimPagination {

    static final int MAX_PAGE_SIZE = 50;

    private PlaceClaimPagination() {
    }

    static void validate(int page, int size) {
        if (page < 0) {
            throw new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException("O tamanho da página deve estar entre 1 e 50", HttpStatus.BAD_REQUEST,
                    "INVALID_PAGE_SIZE");
        }
    }
}
