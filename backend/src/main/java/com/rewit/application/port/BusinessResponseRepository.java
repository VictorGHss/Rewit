package com.rewit.application.port;

import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseView;
import com.rewit.application.dto.business.BusinessResponseDtos.RemovedBusinessResponseFilter;
import com.rewit.application.dto.business.BusinessResponseDtos.RemovedBusinessResponseRecord;
import com.rewit.application.dto.common.PageResult;
import com.rewit.domain.model.BusinessResponse;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência das respostas oficiais de empresas (business_responses, C11).
 *
 * <p>As listas de respostas ativas ({@code findActive*Responses*}) devolvem só as de representação elegível agora
 * (decisão B, critérios de {@code BusinessRepresentationPolicy}); a inelegível fica oculta sem mudar de estado e volta
 * a aparecer quando a representação volta a ser elegível. {@link #findActive} não filtra: serve à unicidade, e uma
 * resposta oculta continua ocupando a vaga da empresa no alvo.
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
     * Resposta ativa da empresa ao alvo, se houver, elegível ou não: é a vaga da unicidade.
     *
     * @param discussionId comentário raiz; {@code null} para a resposta direta à avaliação
     */
    Optional<BusinessResponse> findActive(UUID reviewId, UUID discussionId, UUID businessAccountId);

    /** Respostas ativas e elegíveis diretas à avaliação (sem comentário), em ordem cronológica. */
    PageResult<BusinessResponse> findActiveReviewResponses(UUID reviewId, int page, int size);

    /**
     * Página pública das respostas ativas e elegíveis diretas à avaliação, com empresa e local numa única consulta,
     * em ordem cronológica estável (created_at, id); a contagem é do mesmo conjunto.
     */
    PageResult<BusinessResponseView> findActiveReviewResponseViews(UUID reviewId, int page, int size);

    /** Página pública das respostas ativas e elegíveis a um comentário, na mesma forma e ordem (created_at, id). */
    PageResult<BusinessResponseView> findActiveDiscussionResponseViews(UUID discussionId, int page, int size);

    /**
     * Respostas REMOVED para a moderação, das removidas mais recentemente para as mais antigas (removed_at DESC,
     * id DESC), numa única consulta com empresa, local, última decisão de remoção da moderação e existência de outra
     * resposta ativa da empresa no mesmo alvo.
     */
    PageResult<RemovedBusinessResponseRecord> findRemovedForModeration(RemovedBusinessResponseFilter filter, int page,
                                                                        int size);

    /**
     * Respostas ativas e elegíveis aos comentários informados, em ordem cronológica; para compor uma página de
     * comentários.
     */
    List<BusinessResponse> findActiveDiscussionResponses(Collection<UUID> discussionIds);
}
