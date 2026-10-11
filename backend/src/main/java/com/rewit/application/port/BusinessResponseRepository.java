package com.rewit.application.port;

import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.domain.model.BusinessResponse;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência das respostas oficiais de empresas (business_responses, C11).
 */
public interface BusinessResponseRepository {

    /**
     * Grava e sincroniza com o banco, para que a violação dos índices de resposta ativa única
     * (uq_business_responses_review_active, uq_business_responses_discussion_active) e da FK comentário x avaliação
     * surja na chamada.
     */
    BusinessResponse save(BusinessResponse response);

    Optional<BusinessResponse> findById(UUID id);

    /** Trava a linha (FOR UPDATE) e recarrega o estado confirmado, mesmo se já carregada na transação. */
    Optional<BusinessResponse> findByIdForUpdate(UUID id);

    /**
     * Resposta ativa da empresa ao alvo, se houver.
     *
     * @param discussionId comentário raiz; {@code null} para a resposta direta à avaliação
     */
    Optional<BusinessResponse> findActive(UUID reviewId, UUID discussionId, UUID businessAccountId);

    /** Respostas ativas diretas à avaliação (sem comentário), em ordem cronológica. */
    PageResult<BusinessResponse> findActiveReviewResponses(UUID reviewId, int page, int size);

    /**
     * Página pública das respostas ativas diretas à avaliação, com empresa e local numa única consulta, em ordem
     * cronológica estável (created_at, id).
     */
    PageResult<BusinessResponseView> findActiveReviewResponseViews(UUID reviewId, int page, int size);

    /** Respostas ativas aos comentários informados, em ordem cronológica; para compor uma página de comentários. */
    List<BusinessResponse> findActiveDiscussionResponses(Collection<UUID> discussionIds);
}
