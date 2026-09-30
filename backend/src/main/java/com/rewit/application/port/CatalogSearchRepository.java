package com.rewit.application.port;

import com.rewit.application.dto.catalog.CatalogDtos.CatalogSearchResult;
import com.rewit.application.dto.common.PageResult;

public interface CatalogSearchRepository {
    PageResult<CatalogSearchResult> search(String query, int page, int size);
}
