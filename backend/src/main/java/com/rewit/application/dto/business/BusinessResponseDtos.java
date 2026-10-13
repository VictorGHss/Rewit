package com.rewit.application.dto.business;

import com.rewit.domain.enums.BusinessResponseModerationAction;
import com.rewit.domain.enums.BusinessResponseRemovalSource;
import com.rewit.domain.enums.BusinessResponseStatus;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.BusinessResponse;
import com.rewit.domain.model.BusinessResponseModerationAuditLog;
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

    /**
     * @param placeId local que a resposta representa, escolhido explicitamente (ver
     *                {@link PublishReviewBusinessResponseCommand})
     */
    public record PublishDiscussionBusinessResponseCommand(
            UUID actorUserId,
            UUID businessAccountId,
            UUID discussionId,
            UUID placeId,
            String content
    ) {
    }

    /**
     * Resposta oficial publicada, na forma pública.
     *
     * @param discussionId comentário raiz respondido; {@code null} quando a resposta é direta à avaliação
     */
    public record BusinessResponseView(
            UUID id,
            UUID reviewId,
            UUID discussionId,
            UUID businessAccountId,
            String corporateName,
            UUID placeId,
            String placeName,
            String content,
            Instant createdAt
    ) {
        public static BusinessResponseView of(BusinessResponse response, BusinessAccount account, Place place) {
            return new BusinessResponseView(response.getId(), response.getReviewId(), response.getDiscussionId(),
                    account.getId(), account.getCorporateName(), place.getId(), place.getName(), response.getContent(),
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

    /**
     * Decisão administrativa sobre uma resposta oficial.
     *
     * @param moderatorUserId identidade autenticada do moderador, nunca um valor escolhido pelo cliente
     * @param now             instante da decisão; {@code null} usa o relógio atual
     */
    public record ModerateBusinessResponseCommand(
            UUID responseId,
            UUID moderatorUserId,
            BusinessResponseModerationAction action,
            String reasonCode,
            String justification,
            Instant now
    ) {
    }

    /**
     * Resultado administrativo da moderação: a decisão auditada e o estado resultante da resposta. Sem o autor
     * pessoal da resposta nem o documento da empresa.
     */
    public record BusinessResponseModerationResult(
            UUID auditLogId,
            UUID responseId,
            UUID reviewId,
            UUID discussionId,
            UUID businessAccountId,
            UUID placeId,
            BusinessResponseModerationAction action,
            String reasonCode,
            String justification,
            BusinessResponseStatus previousStatus,
            BusinessResponseStatus newStatus,
            BusinessResponseRemovalSource removedBy,
            Instant moderatedAt
    ) {
        public static BusinessResponseModerationResult of(BusinessResponseModerationAuditLog auditLog,
                                                          BusinessResponse response) {
            return new BusinessResponseModerationResult(auditLog.getId(), response.getId(), response.getReviewId(),
                    response.getDiscussionId(), response.getBusinessAccountId(), response.getPlaceId(),
                    auditLog.getAction(), auditLog.getReasonCode(), auditLog.getJustification(),
                    auditLog.getPreviousStatus(), auditLog.getNewStatus(), response.getRemovedBy(),
                    auditLog.getCreatedAt());
        }
    }

    /**
     * Filtros opcionais da consulta administrativa de respostas removidas; {@code null} não filtra.
     *
     * @param discussionId comentário respondido; não distingue respostas diretas à avaliação (use {@code reviewId})
     */
    public record RemovedBusinessResponseFilter(
            UUID reviewId,
            UUID discussionId,
            UUID businessAccountId,
            UUID placeId,
            BusinessResponseRemovalSource removedBy
    ) {
        public static RemovedBusinessResponseFilter none() {
            return new RemovedBusinessResponseFilter(null, null, null, null, null);
        }
    }

    /**
     * Resposta removida como a persistência a lê, com o contexto da última decisão de remoção da moderação (nulo
     * quando a remoção foi da empresa) e se a empresa tem hoje outra resposta ativa no mesmo alvo.
     */
    public record RemovedBusinessResponseRecord(
            UUID id,
            UUID reviewId,
            UUID discussionId,
            UUID businessAccountId,
            String corporateName,
            UUID placeId,
            String placeName,
            String content,
            Instant createdAt,
            BusinessResponseRemovalSource removedBy,
            Instant removedAt,
            boolean otherActiveResponse,
            String moderationReasonCode,
            String moderationJustification,
            Instant moderatedAt
    ) {
    }

    /**
     * Resposta removida na consulta administrativa. Sem o autor pessoal nem o documento da empresa.
     *
     * @param restorable          a restauração passaria agora: removida pela moderação
     *                            ({@link BusinessResponse#isRestorableByModeration}) e sem outra resposta ativa da
     *                            empresa no alvo. É uma indicação; a moderação revalida tudo com a resposta travada
     * @param otherActiveResponse a empresa publicou outra resposta ativa no mesmo alvo depois desta
     */
    public record AdminRemovedBusinessResponseView(
            UUID id,
            UUID reviewId,
            UUID discussionId,
            UUID businessAccountId,
            String corporateName,
            UUID placeId,
            String placeName,
            String content,
            Instant createdAt,
            BusinessResponseRemovalSource removedBy,
            Instant removedAt,
            boolean restorable,
            boolean otherActiveResponse,
            String moderationReasonCode,
            String moderationJustification,
            Instant moderatedAt
    ) {
        public static AdminRemovedBusinessResponseView of(RemovedBusinessResponseRecord record) {
            boolean restorable = BusinessResponse.isRestorableByModeration(BusinessResponseStatus.REMOVED, record.removedBy())
                    && !record.otherActiveResponse();
            return new AdminRemovedBusinessResponseView(record.id(), record.reviewId(), record.discussionId(),
                    record.businessAccountId(), record.corporateName(), record.placeId(), record.placeName(),
                    record.content(), record.createdAt(), record.removedBy(), record.removedAt(), restorable,
                    record.otherActiveResponse(), record.moderationReasonCode(), record.moderationJustification(),
                    record.moderatedAt());
        }
    }
}
