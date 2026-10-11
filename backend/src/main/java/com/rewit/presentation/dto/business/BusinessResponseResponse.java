package com.rewit.presentation.dto.business;

import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseView;

import java.time.Instant;
import java.util.UUID;

/**
 * Representação pública de uma resposta oficial publicada (C11).
 *
 * <p>Identifica a empresa e o local representado. Não expõe o autor pessoal ({@code authorUserId}),
 * o documento fiscal da empresa ({@code taxId}) nem metadados privados de remoção ou auditoria.
 */
public record BusinessResponseResponse(
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
    public static BusinessResponseResponse fromView(BusinessResponseView view) {
        if (view == null) {
            return null;
        }
        return new BusinessResponseResponse(
                view.id(),
                view.reviewId(),
                view.discussionId(),
                view.businessAccountId(),
                view.corporateName(),
                view.placeId(),
                view.placeName(),
                view.content(),
                view.createdAt()
        );
    }
}
