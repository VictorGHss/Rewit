package com.rewit.application.port;

import com.rewit.application.dto.common.PageResult;
import com.rewit.domain.model.ReviewDiscussion;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /*
     * Thread por leitor (C3 D2). Uma resposta é visível ao leitor se estiver ACTIVE ou se for UNDER_REVIEW de
     * autoria dele. Uma raiz é listada se estiver ACTIVE, se for UNDER_REVIEW de autoria do leitor, ou se estiver
     * REMOVED com pelo menos uma resposta visível (tombstone). Ordem: created_at ASC, id ASC.
     */

    /** Raízes da thread visíveis ao leitor, paginadas. */
    PageResult<ReviewDiscussion> findThreadRootsVisibleTo(UUID reviewId, UUID viewerId, int page, int size);

    /** Primeiras {@code limitPerRoot} respostas visíveis de cada raiz, numa única consulta. */
    List<ReviewDiscussion> findFirstRepliesVisibleTo(Collection<UUID> rootIds, UUID viewerId, int limitPerRoot);

    /** Total de respostas visíveis por raiz; raízes sem respostas visíveis não aparecem no mapa. */
    Map<UUID, Long> countRepliesVisibleTo(Collection<UUID> rootIds, UUID viewerId);

    /** Respostas visíveis de uma raiz, paginadas. */
    PageResult<ReviewDiscussion> findRepliesVisibleTo(UUID rootId, UUID viewerId, int page, int size);
}
