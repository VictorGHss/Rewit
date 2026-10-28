package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.FeedCandidateRepository;
import com.rewit.domain.enums.InterestCategory;
import com.rewit.domain.feed.FeedCandidate;
import com.rewit.domain.feed.TargetCategoryMapper;
import com.rewit.infrastructure.persistence.entity.ReviewJpaEntity;
import com.rewit.infrastructure.persistence.entity.ReviewTargetJpaEntity;
import com.rewit.infrastructure.persistence.repository.RateableTargetJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewReactionJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewTargetJpaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.core.PropertyPath;
import org.springframework.data.core.TypedPropertyPath;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Adaptador de persistência para o Candidate Retrieval do Feed V2 (Step 24.3.2)
 * enriquecido com categorias canônicas para o ranking de afinidade por interesses (V40).
 *
 * <h2>Estratégia de retrieval (sem N+1)</h2>
 * <ol>
 *   <li><b>Query 1 — reviews elegíveis:</b> {@code findFeedV2Candidates} — join direto com
 *       {@code user_follows} retornando somente os campos necessários ao ranking.</li>
 *   <li><b>Query 2 — target primário:</b> {@code findByReviewIdIn} — busca em lote de todos os
 *       {@code ReviewTarget} das reviews retornadas. O target primário para diversidade e afinidade é
 *       determinado pelo menor {@code createdAt} (primeiro inserido) entre os targets de cada review.</li>
 *   <li><b>Query 3 — helpful count:</b> {@code countHelpfulByReviewIds} — agregação em lote
 *       via {@code GROUP BY review_id}, retornando {@code Map<reviewId, count>}.</li>
 *   <li><b>Query 4 — categorias dos alvos:</b> {@code findCategoriesByIds} — consulta em lote
 *       das categorias de estabelecimentos e produtos envolvidos via {@link RateableTargetJpaRepository}.</li>
 * </ol>
 * Total: <b>4 queries por retrieval</b>, independente da janela de candidatos. Zero N+1.
 */
@Component
public class FeedCandidateRepositoryAdapter implements FeedCandidateRepository {

    /** Índice da coluna reviewId no Object[] retornado por findFeedV2Candidates. */
    private static final int COL_REVIEW_ID = 0;
    /** Índice da coluna authorId (userId) no Object[]. */
    private static final int COL_AUTHOR_ID = 1;
    /** Índice da coluna contextPlaceId (nullable) no Object[]. */
    private static final int COL_CONTEXT_PLACE_ID = 2;
    /** Índice da coluna createdAt no Object[]. */
    private static final int COL_CREATED_AT = 3;
    /** Índice da coluna isVerifiedOnSite no Object[]. */
    private static final int COL_VERIFIED = 4;
    /** Índice da coluna isAnonymous no Object[]. */
    private static final int COL_ANONYMOUS = 5;

    private final ReviewJpaRepository reviewJpaRepository;
    private final ReviewTargetJpaRepository reviewTargetJpaRepository;
    private final ReviewReactionJpaRepository reviewReactionJpaRepository;
    private final RateableTargetJpaRepository rateableTargetJpaRepository;

    @Autowired
    public FeedCandidateRepositoryAdapter(
            ReviewJpaRepository reviewJpaRepository,
            ReviewTargetJpaRepository reviewTargetJpaRepository,
            ReviewReactionJpaRepository reviewReactionJpaRepository,
            RateableTargetJpaRepository rateableTargetJpaRepository) {
        this.reviewJpaRepository = Objects.requireNonNull(reviewJpaRepository, "reviewJpaRepository is required");
        this.reviewTargetJpaRepository = Objects.requireNonNull(reviewTargetJpaRepository, "reviewTargetJpaRepository is required");
        this.reviewReactionJpaRepository = Objects.requireNonNull(reviewReactionJpaRepository, "reviewReactionJpaRepository is required");
        this.rateableTargetJpaRepository = rateableTargetJpaRepository;
    }

    public FeedCandidateRepositoryAdapter(
            ReviewJpaRepository reviewJpaRepository,
            ReviewTargetJpaRepository reviewTargetJpaRepository,
            ReviewReactionJpaRepository reviewReactionJpaRepository) {
        this(reviewJpaRepository, reviewTargetJpaRepository, reviewReactionJpaRepository, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executa queries indexadas ao banco em lote sem N+1. Retorna lista imutável.
     */
    @Override
    @Transactional(readOnly = true)
    public List<FeedCandidate> retrieveCandidates(UUID requesterId, int limit) {
        if (requesterId == null || limit <= 0) {
            return List.of();
        }
        int effectiveLimit = Math.min(limit, CANDIDATE_WINDOW);

        TypedPropertyPath<ReviewJpaEntity, Instant> createdAtPath = PropertyPath.of((ReviewJpaEntity review) -> review.getCreatedAt());
        TypedPropertyPath<ReviewJpaEntity, UUID> idPath = PropertyPath.of((ReviewJpaEntity review) -> review.getId());

        Sort sort = Sort.by(
                Sort.Order.desc(createdAtPath),
                Sort.Order.asc(idPath)
        );
        PageRequest pageRequest = PageRequest.of(0, effectiveLimit, sort);
        List<Object[]> rows = reviewJpaRepository.findFeedV2Candidates(requesterId, pageRequest);

        if (rows.isEmpty()) {
            return List.of();
        }

        return buildCandidatesFromRows(rows, true);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executa busca de avaliações públicas elegíveis para descoberta quando a rede social
     * direta do usuário não produzir candidatos (Cold Start - Step 24.5.1).
     * Todos os candidatos retornam com {@code isDirectFollow = false}.
     */
    @Override
    @Transactional(readOnly = true)
    public List<FeedCandidate> retrieveDiscoveryCandidates(UUID requesterId, int limit) {
        if (requesterId == null || limit <= 0) {
            return List.of();
        }
        int effectiveLimit = Math.min(limit, CANDIDATE_WINDOW);

        TypedPropertyPath<ReviewJpaEntity, Instant> createdAtPath = PropertyPath.of((ReviewJpaEntity review) -> review.getCreatedAt());
        TypedPropertyPath<ReviewJpaEntity, UUID> idPath = PropertyPath.of((ReviewJpaEntity review) -> review.getId());

        Sort sort = Sort.by(
                Sort.Order.desc(createdAtPath),
                Sort.Order.asc(idPath)
        );
        PageRequest pageRequest = PageRequest.of(0, effectiveLimit, sort);
        List<Object[]> rows = reviewJpaRepository.findFeedV2DiscoveryCandidates(requesterId, pageRequest);

        if (rows.isEmpty()) {
            return List.of();
        }

        return buildCandidatesFromRows(rows, false);
    }

    /**
     * Monta a lista imutável de {@link FeedCandidate} a partir das linhas retornadas pelo banco,
     * executando as queries em lote para alvos primários, contagem de helpful e categorias de alvos.
     */
    private List<FeedCandidate> buildCandidatesFromRows(List<Object[]> rows, boolean isDirectFollow) {
        List<UUID> reviewIds = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            reviewIds.add((UUID) row[COL_REVIEW_ID]);
        }

        // Query 2 — target primário em lote (sem N+1)
        Map<UUID, UUID> primaryTargetByReview = buildPrimaryTargetMap(reviewIds);

        // Query 3 — helpful count em lote (sem N+1)
        Map<UUID, Long> helpfulCounts = buildHelpfulCountMap(reviewIds);

        // Query 4 — categorias dos alvos em lote (sem N+1)
        Set<UUID> targetIdsToResolve = new HashSet<>();
        for (Object[] row : rows) {
            UUID contextPlaceId = (UUID) row[COL_CONTEXT_PLACE_ID];
            if (contextPlaceId != null) {
                targetIdsToResolve.add(contextPlaceId);
            }
            UUID reviewId = (UUID) row[COL_REVIEW_ID];
            UUID primaryTarget = primaryTargetByReview.get(reviewId);
            if (primaryTarget != null) {
                targetIdsToResolve.add(primaryTarget);
            }
        }
        Map<UUID, String> categoryByTargetId = buildCategoryMap(targetIdsToResolve);

        // Montagem dos FeedCandidates
        List<FeedCandidate> candidates = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            UUID reviewId       = (UUID)    row[COL_REVIEW_ID];
            UUID authorId       = (UUID)    row[COL_AUTHOR_ID];
            UUID contextPlaceId = (UUID)    row[COL_CONTEXT_PLACE_ID]; // nullable
            Instant createdAt   = (Instant) row[COL_CREATED_AT];
            boolean verified    = (boolean) row[COL_VERIFIED];
            boolean anonymous   = (boolean) row[COL_ANONYMOUS];

            UUID diversityAuthorId = anonymous ? reviewId : authorId;
            UUID primaryTargetId = primaryTargetByReview.get(reviewId);
            UUID targetId = resolveTargetId(reviewId, contextPlaceId, primaryTargetByReview);
            int helpful   = helpfulCounts.getOrDefault(reviewId, 0L).intValue();

            String rawCategory = resolveRawCategory(contextPlaceId, primaryTargetId, categoryByTargetId);
            InterestCategory category = TargetCategoryMapper.map(rawCategory).orElse(null);

            candidates.add(new FeedCandidate(
                    reviewId,
                    diversityAuthorId,
                    targetId,
                    category,
                    createdAt,
                    verified,
                    helpful,
                    isDirectFollow
            ));
        }
        return Collections.unmodifiableList(candidates);
    }

    private Map<UUID, UUID> buildPrimaryTargetMap(List<UUID> reviewIds) {
        List<ReviewTargetJpaEntity> targets = reviewTargetJpaRepository.findByReviewIdIn(reviewIds);
        Map<UUID, ReviewTargetJpaEntity> primaryByReview = new HashMap<>(reviewIds.size());
        for (ReviewTargetJpaEntity t : targets) {
            ReviewTargetJpaEntity current = primaryByReview.get(t.getReviewId());
            if (current == null || isPrimaryOverCurrent(t, current)) {
                primaryByReview.put(t.getReviewId(), t);
            }
        }
        Map<UUID, UUID> result = new HashMap<>(primaryByReview.size());
        for (Map.Entry<UUID, ReviewTargetJpaEntity> entry : primaryByReview.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getTargetId());
        }
        return result;
    }

    private static boolean isPrimaryOverCurrent(ReviewTargetJpaEntity candidate, ReviewTargetJpaEntity current) {
        int cmp = candidate.getCreatedAt().compareTo(current.getCreatedAt());
        if (cmp != 0) {
            return cmp < 0;
        }
        return candidate.getTargetId().compareTo(current.getTargetId()) < 0;
    }

    private Map<UUID, Long> buildHelpfulCountMap(List<UUID> reviewIds) {
        List<Object[]> rows = reviewReactionJpaRepository.countHelpfulByReviewIds(reviewIds);
        Map<UUID, Long> result = new HashMap<>(rows.size());
        for (Object[] row : rows) {
            UUID reviewId = (UUID) row[0];
            Long count    = ((Number) row[1]).longValue();
            result.put(reviewId, count);
        }
        return result;
    }

    private Map<UUID, String> buildCategoryMap(Set<UUID> targetIds) {
        if (rateableTargetJpaRepository == null || targetIds == null || targetIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = rateableTargetJpaRepository.findCategoriesByIds(targetIds);
        Map<UUID, String> result = new HashMap<>(rows.size());
        for (Object[] row : rows) {
            UUID id = (UUID) row[0];
            String category = (String) row[1];
            if (category != null && !category.isBlank()) {
                result.put(id, category);
            }
        }
        return result;
    }

    private static String resolveRawCategory(
            UUID contextPlaceId,
            UUID primaryTargetId,
            Map<UUID, String> categoryByTargetId) {
        if (contextPlaceId != null) {
            String cat = categoryByTargetId.get(contextPlaceId);
            if (cat != null && !cat.isBlank()) {
                return cat;
            }
        }
        if (primaryTargetId != null) {
            String cat = categoryByTargetId.get(primaryTargetId);
            if (cat != null && !cat.isBlank()) {
                return cat;
            }
        }
        return null;
    }

    private static UUID resolveTargetId(
            UUID reviewId,
            UUID contextPlaceId,
            Map<UUID, UUID> primaryTargetByReview) {
        if (contextPlaceId != null) {
            return contextPlaceId;
        }
        UUID fromTarget = primaryTargetByReview.get(reviewId);
        if (fromTarget != null) {
            return fromTarget;
        }
        return reviewId;
    }
}
