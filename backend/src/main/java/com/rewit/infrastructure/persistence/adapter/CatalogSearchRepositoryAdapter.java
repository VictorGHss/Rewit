package com.rewit.infrastructure.persistence.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.rewit.application.dto.catalog.CatalogDtos.CatalogSearchResult;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.CatalogSearchRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

@Component
public class CatalogSearchRepositoryAdapter implements CatalogSearchRepository {

    private final EntityManager entityManager;

    public CatalogSearchRepositoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public PageResult<CatalogSearchResult> search(String query, int page, int size) {
        String normalizedQuery = query.trim();
        String lowerQuery = normalizedQuery.toLowerCase();
        String likePattern = "%" + lowerQuery + "%";
        int offset = page * size;

        String sql = """
            SELECT * FROM (
                SELECT
                    p.id AS id,
                    p.name AS name,
                    p.slug AS slug,
                    p.category AS category,
                    'PLACE' AS target_type,
                    p.status AS status,
                    GREATEST(similarity(lower(p.name), :query), similarity(lower(COALESCE(p.category, '')), :query)) AS relevance
                FROM places p
                WHERE p.status = 'ACTIVE'
                  AND (
                        lower(p.name) LIKE :likePattern
                        OR lower(COALESCE(p.category, '')) LIKE :likePattern
                        OR lower(COALESCE(p.city, '')) LIKE :likePattern
                  )

                UNION ALL

                SELECT
                    pr.id AS id,
                    pr.name AS name,
                    NULL AS slug,
                    pr.category AS category,
                    'PRODUCT' AS target_type,
                    pr.status AS status,
                    GREATEST(similarity(lower(pr.name), :query), similarity(lower(COALESCE(pr.category, '')), :query)) AS relevance
                FROM products pr
                WHERE pr.status = 'ACTIVE'
                  AND (
                        lower(pr.name) LIKE :likePattern
                        OR lower(COALESCE(pr.category, '')) LIKE :likePattern
                  )
            ) ranked
            ORDER BY ranked.relevance DESC, ranked.name ASC
            LIMIT :size OFFSET :offset
            """;

        Query nativeQuery = entityManager.createNativeQuery(sql);
        nativeQuery.setParameter("query", lowerQuery);
        nativeQuery.setParameter("likePattern", likePattern);
        nativeQuery.setParameter("size", size);
        nativeQuery.setParameter("offset", offset);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = nativeQuery.getResultList();

        List<CatalogSearchResult> content = new ArrayList<>();
        for (Object[] row : rows) {
            content.add(new CatalogSearchResult(
                    UUID.fromString(row[0].toString()),
                    row[1] != null ? row[1].toString() : null,
                    row[2] != null ? row[2].toString() : null,
                    row[3] != null ? row[3].toString() : null,
                    row[4] != null ? row[4].toString() : null,
                    row[5] != null ? row[5].toString() : null
            ));
        }

        String countSql = """
            SELECT COUNT(*) FROM (
                SELECT p.id
                FROM places p
                WHERE p.status = 'ACTIVE'
                  AND (
                        lower(p.name) LIKE :likePattern
                        OR lower(COALESCE(p.category, '')) LIKE :likePattern
                        OR lower(COALESCE(p.city, '')) LIKE :likePattern
                  )

                UNION ALL

                SELECT pr.id
                FROM products pr
                WHERE pr.status = 'ACTIVE'
                  AND (
                        lower(pr.name) LIKE :likePattern
                        OR lower(COALESCE(pr.category, '')) LIKE :likePattern
                  )
            ) ranked
            """;

        Query countQuery = entityManager.createNativeQuery(countSql);
        countQuery.setParameter("likePattern", likePattern);
        Number total = (Number) countQuery.getSingleResult();

        return PageResult.of(content, page, size, total.longValue());
    }
}
