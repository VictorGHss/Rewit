package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.RankingRepository;
import com.rewit.domain.model.BrazilianState;
import com.rewit.domain.model.RankingItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Adaptador de persistência para execução de consultas analíticas dos Rankings V1 (ADR-016).
 * Utiliza SQL nativo com DENSE_RANK(), índices parciais e joins estritos para garantir 0 vazamento
 * de perfis opt-out, usuários inativos ou avaliações não públicas.
 */
@Component
public class RankingRepositoryAdapter implements RankingRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<RankingItem> findCheckInsRanking(
            Instant effectiveStart,
            Instant periodEnd,
            BrazilianState state,
            String city,
            Set<String> categories,
            int limit
    ) {
        Objects.requireNonNull(effectiveStart, "effectiveStart must not be null");
        Objects.requireNonNull(periodEnd, "periodEnd must not be null");
        Objects.requireNonNull(state, "state must not be null");

        StringBuilder sql = new StringBuilder("""
            SELECT
                DENSE_RANK() OVER (ORDER BY COUNT(c.id) DESC) AS rank_pos,
                p.user_id AS user_id,
                p.handle AS handle,
                p.display_name AS display_name,
                p.avatar_url AS avatar_url,
                COUNT(c.id) AS score,
                MAX(c.verified_at) AS last_contributed_at
            FROM check_ins c
            JOIN reviews r ON r.id = c.review_id
            JOIN places pl ON pl.id = c.place_id
            JOIN users u ON u.id = c.user_id
            JOIN profiles p ON p.user_id = u.id
            WHERE p.show_in_rankings = TRUE
              AND u.account_status = 'ACTIVE'
              AND c.status = 'VERIFIED'
              AND c.verified_at >= :effectiveStart
              AND c.verified_at < :periodEnd
              AND r.status = 'ACTIVE'
              AND r.visibility = 'PUBLIC'
              AND r.is_anonymous = FALSE
              AND UPPER(TRIM(pl.state)) = :state
        """);

        applyOptionalFilters(sql, city, categories);

        sql.append("""
            GROUP BY p.user_id, p.handle, p.display_name, p.avatar_url
            ORDER BY score DESC, p.user_id ASC
            LIMIT :limit
        """);

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("effectiveStart", effectiveStart);
        query.setParameter("periodEnd", periodEnd);
        query.setParameter("state", state.name());
        query.setParameter("limit", Math.max(1, limit));

        bindOptionalParameters(query, city, categories);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return mapRowsToRankingItems(rows);
    }

    @Override
    public List<RankingItem> findPlacesExploredRanking(
            Instant effectiveStart,
            Instant periodEnd,
            BrazilianState state,
            String city,
            Set<String> categories,
            int limit
    ) {
        Objects.requireNonNull(effectiveStart, "effectiveStart must not be null");
        Objects.requireNonNull(periodEnd, "periodEnd must not be null");
        Objects.requireNonNull(state, "state must not be null");

        StringBuilder sql = new StringBuilder("""
            SELECT
                DENSE_RANK() OVER (ORDER BY COUNT(DISTINCT r.context_place_id) DESC) AS rank_pos,
                p.user_id AS user_id,
                p.handle AS handle,
                p.display_name AS display_name,
                p.avatar_url AS avatar_url,
                COUNT(DISTINCT r.context_place_id) AS score,
                MAX(r.created_at) AS last_contributed_at
            FROM reviews r
            JOIN places pl ON pl.id = r.context_place_id
            JOIN users u ON u.id = r.user_id
            JOIN profiles p ON p.user_id = u.id
            WHERE p.show_in_rankings = TRUE
              AND u.account_status = 'ACTIVE'
              AND r.status = 'ACTIVE'
              AND r.visibility = 'PUBLIC'
              AND r.is_anonymous = FALSE
              AND r.context_place_id IS NOT NULL
              AND r.created_at >= :effectiveStart
              AND r.created_at < :periodEnd
              AND UPPER(TRIM(pl.state)) = :state
        """);

        applyOptionalFilters(sql, city, categories);

        sql.append("""
            GROUP BY p.user_id, p.handle, p.display_name, p.avatar_url
            ORDER BY score DESC, p.user_id ASC
            LIMIT :limit
        """);

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("effectiveStart", effectiveStart);
        query.setParameter("periodEnd", periodEnd);
        query.setParameter("state", state.name());
        query.setParameter("limit", Math.max(1, limit));

        bindOptionalParameters(query, city, categories);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return mapRowsToRankingItems(rows);
    }

    @Override
    public List<RankingItem> findHelpfulVotesRanking(
            Instant effectiveStart,
            Instant periodEnd,
            BrazilianState state,
            String city,
            Set<String> categories,
            int limit
    ) {
        Objects.requireNonNull(effectiveStart, "effectiveStart must not be null");
        Objects.requireNonNull(periodEnd, "periodEnd must not be null");
        Objects.requireNonNull(state, "state must not be null");

        StringBuilder sql = new StringBuilder("""
            SELECT
                DENSE_RANK() OVER (ORDER BY COUNT(rx.id) DESC) AS rank_pos,
                p.user_id AS user_id,
                p.handle AS handle,
                p.display_name AS display_name,
                p.avatar_url AS avatar_url,
                COUNT(rx.id) AS score,
                MAX(rx.created_at) AS last_contributed_at
            FROM review_reactions rx
            JOIN reviews r ON r.id = rx.review_id
            JOIN places pl ON pl.id = r.context_place_id
            JOIN users author_u ON author_u.id = r.user_id
            JOIN profiles p ON p.user_id = author_u.id
            JOIN users voter_u ON voter_u.id = rx.user_id
            WHERE p.show_in_rankings = TRUE
              AND author_u.account_status = 'ACTIVE'
              AND voter_u.account_status = 'ACTIVE'
              AND rx.user_id != r.user_id
              AND rx.reaction_type = 'HELPFUL'
              AND rx.created_at >= :effectiveStart
              AND rx.created_at < :periodEnd
              AND r.status = 'ACTIVE'
              AND r.visibility = 'PUBLIC'
              AND r.is_anonymous = FALSE
              AND UPPER(TRIM(pl.state)) = :state
        """);

        applyOptionalFilters(sql, city, categories);

        sql.append("""
            GROUP BY p.user_id, p.handle, p.display_name, p.avatar_url
            ORDER BY score DESC, p.user_id ASC
            LIMIT :limit
        """);

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("effectiveStart", effectiveStart);
        query.setParameter("periodEnd", periodEnd);
        query.setParameter("state", state.name());
        query.setParameter("limit", Math.max(1, limit));

        bindOptionalParameters(query, city, categories);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return mapRowsToRankingItems(rows);
    }

    private void applyOptionalFilters(StringBuilder sql, String city, Set<String> categories) {
        if (city != null && !city.isBlank()) {
            sql.append(" AND LOWER(TRIM(pl.city)) = LOWER(TRIM(:city)) ");
        }
        if (categories != null && !categories.isEmpty()) {
            sql.append(" AND LOWER(TRIM(pl.category)) IN (:categories) ");
        }
    }

    private void bindOptionalParameters(Query query, String city, Set<String> categories) {
        if (city != null && !city.isBlank()) {
            query.setParameter("city", city.trim());
        }
        if (categories != null && !categories.isEmpty()) {
            Set<String> normalizedCategories = categories.stream()
                    .map(c -> c.trim().toLowerCase())
                    .collect(Collectors.toSet());
            query.setParameter("categories", normalizedCategories);
        }
    }

    private List<RankingItem> mapRowsToRankingItems(List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<RankingItem> items = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            int rankPos = ((Number) row[0]).intValue();
            UUID userId = row[1] instanceof UUID u ? u : UUID.fromString(row[1].toString());
            String handle = (String) row[2];
            String displayName = (String) row[3];
            String avatarUrl = (String) row[4];
            long score = ((Number) row[5]).longValue();
            Instant lastContributedAt = parseInstant(row[6]);

            items.add(new RankingItem(
                    rankPos,
                    userId,
                    handle,
                    displayName,
                    avatarUrl,
                    score,
                    lastContributedAt
            ));
        }
        return items;
    }

    private Instant parseInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant inst) {
            return inst;
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        return Instant.parse(value.toString());
    }
}

