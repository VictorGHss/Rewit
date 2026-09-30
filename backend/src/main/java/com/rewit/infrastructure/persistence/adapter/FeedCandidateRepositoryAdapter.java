package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.FeedCandidateRepository;
import com.rewit.domain.feed.FeedCandidate;
import com.rewit.infrastructure.persistence.entity.ReviewJpaEntity;
import com.rewit.infrastructure.persistence.entity.ReviewTargetJpaEntity;
import com.rewit.infrastructure.persistence.repository.ReviewJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewReactionJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewTargetJpaRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Adaptador de persistência para o Candidate Retrieval do Feed V2 (Step 24.3.2).
 *
 * <h2>Estratégia de retrieval (sem N+1)</h2>
 * <ol>
 *   <li><b>Query 1 — reviews elegíveis:</b> {@code findFeedV2Candidates} — join direto com
 *       {@code user_follows} retornando somente os 5 campos necessários ao ranking.</li>
 *   <li><b>Query 2 — target primário:</b> {@code findByReviewIdIn} — busca em lote de todos os
 *       {@code ReviewTarget} das reviews retornadas. O target primário para diversidade é
 *       determinado pelo menor {@code createdAt} (primeiro inserido) entre os targets de cada review.</li>
 *   <li><b>Query 3 — helpful count:</b> {@code countHelpfulByReviewIds} — agregação em lote
 *       via {@code GROUP BY review_id}, retornando {@code Map<reviewId, count>}.</li>
 * </ol>
 * Total: <b>3 queries por retrieval</b>, independente da janela de candidatos. Zero N+1.
 *
 * <h2>targetId para diversidade</h2>
 * <p>{@link FeedCandidate#targetId()} é obrigatório (não-nulo). A estratégia adotada:</p>
 * <ol>
 *   <li>Se a review possui {@code contextPlaceId}, usar o {@code contextPlaceId} como targetId.
 *       Isso agrupa reviews do mesmo lugar para a regra de diversidade.</li>
 *   <li>Se {@code contextPlaceId} é nulo E a review possui pelo menos um {@code ReviewTarget},
 *       usar o {@code targetId} do target primário (menor {@code createdAt}).</li>
 *   <li>Se a review não possui {@code contextPlaceId} nem {@code ReviewTarget} (situação
 *       improvável dado que o domínio exige pelo menos 1 target), usar o próprio {@code reviewId}
 *       como sentinela. Isso garante que cada review sem contexto seja tratada como "target único"
 *       na diversidade, sem agrupamento artificial com outras.</li>
 * </ol>
 *
 * <h2>isDirectFollow</h2>
 * <p>Nesta etapa o retrieval usa exclusivamente autores do grafo social direto (follows diretos).
 * Por construção da query, {@code isDirectFollow = true} para todos os candidatos.</p>
 *
 * <h2>Reviews anônimas</h2>
 * <p>Reviews anônimas aparecem nos candidatos com o {@code authorId} interno preservado.
 * Esse ID existe apenas para as regras de diversidade (evitar run de mesmo autor) e nunca
 * é exposto externamente. A camada de hydration/projeção posterior é responsável por
 * ocultar a identidade quando {@code isAnonymous = true}.</p>
 *
 * <h2>Índice futuro recomendado</h2>
 * <p>Para cargas maiores, um índice composto em
 * {@code reviews(user_id, status, visibility, created_at DESC)} eliminaria a varredura
 * parcial após o join com {@code user_follows}. Não criado nesta etapa (sem migration).</p>
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

    private final ReviewJpaRepository reviewJpaRepository;
    private final ReviewTargetJpaRepository reviewTargetJpaRepository;
    private final ReviewReactionJpaRepository reviewReactionJpaRepository;

    public FeedCandidateRepositoryAdapter(
            ReviewJpaRepository reviewJpaRepository,
            ReviewTargetJpaRepository reviewTargetJpaRepository,
            ReviewReactionJpaRepository reviewReactionJpaRepository) {
        this.reviewJpaRepository = Objects.requireNonNull(reviewJpaRepository);
        this.reviewTargetJpaRepository = Objects.requireNonNull(reviewTargetJpaRepository);
        this.reviewReactionJpaRepository = Objects.requireNonNull(reviewReactionJpaRepository);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executa exatamente 3 queries ao banco. Retorna lista imutável.
     */
    @Override
    @Transactional(readOnly = true)
    public List<FeedCandidate> retrieveCandidates(UUID requesterId, int limit) {
        if (requesterId == null || limit <= 0) {
            return List.of();
        }
        int effectiveLimit = Math.min(limit, CANDIDATE_WINDOW);

        // ----------------------------------------------------------------
        // Query 1 — reviews elegíveis da rede social direta
        // Ordenação type-safe via TypedPropertyPath (Spring Data 4.1):
        //   createdAt DESC, id ASC
        // ----------------------------------------------------------------
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
     * executando as queries em lote para alvos primários e contagem de helpful.
     */
    private List<FeedCandidate> buildCandidatesFromRows(List<Object[]> rows, boolean isDirectFollow) {
        // Extrair IDs para as queries em lote
        List<UUID> reviewIds = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            reviewIds.add((UUID) row[COL_REVIEW_ID]);
        }

        // Query 2 — target primário em lote (sem N+1)
        Map<UUID, UUID> primaryTargetByReview = buildPrimaryTargetMap(reviewIds);

        // Query 3 — helpful count em lote (sem N+1)
        Map<UUID, Long> helpfulCounts = buildHelpfulCountMap(reviewIds);

        // Montagem dos FeedCandidates
        List<FeedCandidate> candidates = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            UUID reviewId       = (UUID)    row[COL_REVIEW_ID];
            UUID authorId       = (UUID)    row[COL_AUTHOR_ID];
            UUID contextPlaceId = (UUID)    row[COL_CONTEXT_PLACE_ID]; // nullable
            Instant createdAt   = (Instant) row[COL_CREATED_AT];
            boolean verified    = (boolean) row[COL_VERIFIED];

            UUID targetId = resolveTargetId(reviewId, contextPlaceId, primaryTargetByReview);
            int helpful   = helpfulCounts.getOrDefault(reviewId, 0L).intValue();

            candidates.add(new FeedCandidate(
                    reviewId,
                    authorId,
                    targetId,
                    createdAt,
                    verified,
                    helpful,
                    isDirectFollow
            ));
        }
        return Collections.unmodifiableList(candidates);
    }

    /**
     * Constrói mapa de {@code reviewId → targetId primário} via busca em lote.
     *
     * <p>O target primário é o de menor {@code createdAt} entre os targets da review.
     * Em caso de empate de {@code createdAt}, usa o menor UUID lexicográfico para
     * garantir determinismo.
     */
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

    /**
     * Retorna {@code true} se {@code candidate} deve substituir {@code current} como primário.
     * Critério: menor createdAt; em empate, menor UUID comparado lexicograficamente.
     */
    private static boolean isPrimaryOverCurrent(ReviewTargetJpaEntity candidate, ReviewTargetJpaEntity current) {
        int cmp = candidate.getCreatedAt().compareTo(current.getCreatedAt());
        if (cmp != 0) {
            return cmp < 0;
        }
        return candidate.getTargetId().compareTo(current.getTargetId()) < 0;
    }

    /**
     * Constrói mapa de {@code reviewId → helpfulCount} via agregação em lote.
     */
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

    /**
     * Resolve o {@code targetId} para diversidade segundo a estratégia documentada:
     * <ol>
     *   <li>contextPlaceId, se presente</li>
     *   <li>targetId do target primário, se disponível</li>
     *   <li>reviewId como sentinela (sem agrupamento artificial)</li>
     * </ol>
     */
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
        // Sentinela: reviewId garante unicidade sem agrupamento artificial
        return reviewId;
    }
}
