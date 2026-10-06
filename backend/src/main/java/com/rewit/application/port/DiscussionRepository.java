package com.rewit.application.port;

import com.rewit.application.dto.common.PageResult;
import com.rewit.domain.model.ReviewDiscussion;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência para discussões e comentários de avaliações (Step 20.0 / Step 32.0).
 */
public interface DiscussionRepository {

    /**
     * Persiste uma discussão (criação ou atualização).
     */
    ReviewDiscussion save(ReviewDiscussion discussion);

    /**
     * Busca uma discussão por seu identificador único.
     */
    Optional<ReviewDiscussion> findById(UUID id);

    /**
     * Busca uma discussão por seu identificador único adquirindo lock pessimista de escrita (FOR UPDATE).
     */
    Optional<ReviewDiscussion> findByIdForUpdate(UUID id);

    /**
     * Busca discussões ativas (status = 'ACTIVE') de uma avaliação com paginação e ordenação determinística.
     * Ordenação requerida: created_at ASC, id ASC.
     */
    PageResult<ReviewDiscussion> findActiveByReviewId(UUID reviewId, int page, int size);
}
