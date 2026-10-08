package com.rewit.application.service;

import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.rewit.application.dto.catalog.CatalogDtos.CatalogSearchResult;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.CatalogSearchRepository;
import com.rewit.application.port.RateLimiter;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.common.exception.BusinessException;

@Service
public class CatalogSearchService {

    private final CatalogSearchRepository catalogSearchRepository;
    private final RateLimiter rateLimiter;

    public CatalogSearchService(CatalogSearchRepository catalogSearchRepository, RateLimiter rateLimiter) {
        this.catalogSearchRepository = Objects.requireNonNull(catalogSearchRepository, "CatalogSearchRepository must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "RateLimiter must not be null");
    }

    /**
     * @param requesterUserId usuário autenticado: sujeito do limite de buscas (SEARCH, por usuário)
     */
    public PageResult<CatalogSearchResult> search(UUID requesterUserId, String query, int page, int size) {
        Objects.requireNonNull(requesterUserId, "requesterUserId must not be null");
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

        // Depois da validação, como nas demais ações limitadas: requisição inválida não consome a janela
        rateLimiter.acquireOrThrow(RateLimitedAction.SEARCH, RateLimitSubject.ofUser(requesterUserId));

        return catalogSearchRepository.search(normalized, page, size);
    }
}
