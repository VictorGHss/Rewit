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

/**
 * Busca global de places e products (Search V1, endurecida no C5.1).
 *
 * <p>Comparação sem acento e sem diferença de caixa via {@code rewit_search_normalize} (V22), a mesma expressão dos
 * índices trigram {@code idx_places_search_*} e {@code idx_products_search_*}: o planner resolve o filtro com
 * BitmapOr dos índices em vez de varrer as tabelas. {@code %}, {@code _} e {@code \} digitados são literais
 * (escapados, {@code ESCAPE '\'}). Ordem total: relevância, nome e id (único entre places e products, que são
 * {@code rateable_targets}).
 */
@Component
public class CatalogSearchRepositoryAdapter implements CatalogSearchRepository {

    private static final String PLACE_FILTER = """
            p.status = 'ACTIVE'
              AND (
                    rewit_search_normalize(p.name) LIKE rewit_search_normalize(:likePattern) ESCAPE '\\'
                    OR rewit_search_normalize(p.category) LIKE rewit_search_normalize(:likePattern) ESCAPE '\\'
                    OR rewit_search_normalize(p.city) LIKE rewit_search_normalize(:likePattern) ESCAPE '\\'
              )
            """;

    private static final String PRODUCT_FILTER = """
            pr.status = 'ACTIVE'
              AND (
                    rewit_search_normalize(pr.name) LIKE rewit_search_normalize(:likePattern) ESCAPE '\\'
                    OR rewit_search_normalize(pr.category) LIKE rewit_search_normalize(:likePattern) ESCAPE '\\'
              )
            """;

    private final EntityManager entityManager;

    public CatalogSearchRepositoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public PageResult<CatalogSearchResult> search(String query, int page, int size) {
        String normalizedQuery = query.trim();
        String likePattern = "%" + escapeLike(normalizedQuery) + "%";
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
                    GREATEST(
                        similarity(rewit_search_normalize(p.name), rewit_search_normalize(:query)),
                        similarity(COALESCE(rewit_search_normalize(p.category), ''), rewit_search_normalize(:query))
                    ) AS relevance
                FROM places p
                WHERE %s

                UNION ALL

                SELECT
                    pr.id AS id,
                    pr.name AS name,
                    NULL AS slug,
                    pr.category AS category,
                    'PRODUCT' AS target_type,
                    pr.status AS status,
                    GREATEST(
                        similarity(rewit_search_normalize(pr.name), rewit_search_normalize(:query)),
                        similarity(COALESCE(rewit_search_normalize(pr.category), ''), rewit_search_normalize(:query))
                    ) AS relevance
                FROM products pr
                WHERE %s
            ) ranked
            ORDER BY ranked.relevance DESC, ranked.name ASC, ranked.id ASC
            LIMIT :size OFFSET :offset
            """.formatted(PLACE_FILTER, PRODUCT_FILTER);

        Query nativeQuery = entityManager.createNativeQuery(sql);
        nativeQuery.setParameter("query", normalizedQuery);
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
                SELECT p.id FROM places p WHERE %s
                UNION ALL
                SELECT pr.id FROM products pr WHERE %s
            ) ranked
            """.formatted(PLACE_FILTER, PRODUCT_FILTER);

        Query countQuery = entityManager.createNativeQuery(countSql);
        countQuery.setParameter("likePattern", likePattern);
        Number total = (Number) countQuery.getSingleResult();

        return PageResult.of(content, page, size, total.longValue());
    }

    /** O texto digitado é literal no LIKE: escapa o próprio caractere de escape e os curingas. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
