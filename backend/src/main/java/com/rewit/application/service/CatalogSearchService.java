package com.rewit.application.service;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.rewit.application.dto.catalog.CatalogDtos.CatalogSearchResult;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.CatalogSearchRepository;
import com.rewit.common.exception.BusinessException;

@Service
public class CatalogSearchService {

    private final CatalogSearchRepository catalogSearchRepository;

    public CatalogSearchService(CatalogSearchRepository catalogSearchRepository) {
        this.catalogSearchRepository = Objects.requireNonNull(catalogSearchRepository, "CatalogSearchRepository must not be null");
    }

    public PageResult<CatalogSearchResult> search(String query, int page, int size) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) {
            throw new BusinessException("O termo da busca é obrigatório", HttpStatus.BAD_REQUEST, "INVALID_SEARCH_QUERY");
        }
        if (page < 0) {
            throw new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        if (size < 1 || size > 50) {
            throw new BusinessException("O tamanho da página deve estar entre 1 e 50", HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE");
        }

        return catalogSearchRepository.search(normalized, page, size);
    }
}
