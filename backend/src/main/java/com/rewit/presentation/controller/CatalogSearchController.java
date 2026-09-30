package com.rewit.presentation.controller;

import java.util.List;
import java.util.Objects;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rewit.application.dto.catalog.CatalogDtos.CatalogSearchResult;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.service.CatalogSearchService;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.SearchResultResponse;
import com.rewit.presentation.dto.common.PagedResponse;

@RestController
@RequestMapping("/api/v1")
public class CatalogSearchController {

    private final CatalogSearchService catalogSearchService;

    public CatalogSearchController(CatalogSearchService catalogSearchService) {
        this.catalogSearchService = Objects.requireNonNull(catalogSearchService, "CatalogSearchService must not be null");
    }

    @GetMapping("/search")
    public ResponseEntity<PagedResponse<SearchResultResponse>> search(
            @RequestParam("q") String query,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        PageResult<CatalogSearchResult> result = catalogSearchService.search(query, page, size);

        List<SearchResultResponse> content = result.content().stream()
                .map(SearchResultResponse::fromDomain)
                .toList();

        return ResponseEntity.ok(new PagedResponse<>(
                content,
                result.pageNumber(),
                result.pageSize(),
                result.totalElements(),
                result.totalPages(),
                result.isLast()
        ));
    }
}
