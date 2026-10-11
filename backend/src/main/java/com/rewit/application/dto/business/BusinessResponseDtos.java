package com.rewit.application.dto.business;

import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.BusinessResponse;
import com.rewit.domain.model.Place;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Comandos e modelos de aplicação das respostas oficiais de empresas (C11).
 *
 * <p>Os modelos públicos identificam a resposta pela empresa e pelo local representado; nunca carregam o autor
 * pessoal ({@code authorUserId}), o documento da empresa ({@code taxId}) nem metadados de remoção ou auditoria.
 */
public final class BusinessResponseDtos {

    private BusinessResponseDtos() {
    }

    /**
     * @param placeId local que a resposta representa, escolhido explicitamente: a mesma empresa pode controlar mais
     *                de um local associado à avaliação
     */
    public record PublishReviewBusinessResponseCommand(
            UUID actorUserId,
            UUID businessAccountId,
            UUID reviewId,
            UUID placeId,
            String content
    ) {
    }

    /** Resposta oficial publicada, na forma pública. */
    public record BusinessResponseView(
            UUID id,
            UUID reviewId,
            UUID businessAccountId,
            String corporateName,
            UUID placeId,
            String placeName,
            String content,
            Instant createdAt
    ) {
        public static BusinessResponseView of(BusinessResponse response, BusinessAccount account, Place place) {
            return new BusinessResponseView(response.getId(), response.getReviewId(), account.getId(),
                    account.getCorporateName(), place.getId(), place.getName(), response.getContent(),
                    response.getCreatedAt());
        }
    }

    /**
     * Contas do usuário autenticado habilitadas a responder à avaliação.
     *
     * @param respondable a avaliação aceita respostas oficiais (ACTIVE e PUBLIC); sem isso {@code accounts} é vazia
     */
    public record BusinessResponseEligibilityView(
            UUID reviewId,
            boolean respondable,
            List<EligibleBusinessAccount> accounts
    ) {
    }

    /**
     * Conta aprovada do usuário que controla ao menos um local associado à avaliação.
     *
     * @param places           locais que a conta pode representar nesta avaliação; o usuário escolhe um ao publicar
     * @param activeResponseId resposta ativa da conta à avaliação, se houver; enquanto existir, {@code canPublish} é
     *                         false (uma resposta ativa por empresa e avaliação)
     */
    public record EligibleBusinessAccount(
            UUID businessAccountId,
            String corporateName,
            List<EligiblePlace> places,
            UUID activeResponseId,
            boolean canPublish
    ) {
    }

    public record EligiblePlace(UUID placeId, String placeName) {
    }
}
