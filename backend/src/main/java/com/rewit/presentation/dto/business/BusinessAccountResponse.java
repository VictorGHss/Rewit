package com.rewit.presentation.dto.business;

import com.rewit.application.dto.business.BusinessDtos.BusinessAccountView;
import com.rewit.domain.enums.VerificationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Representação HTTP de uma conta comercial (C9).
 * Nunca expõe o userId do administrador da conta.
 */
public record BusinessAccountResponse(
        UUID id,
        String corporateName,
        String taxId,
        VerificationStatus verificationStatus,
        String planTier,
        Instant createdAt,
        Instant updatedAt
) {
    public static BusinessAccountResponse fromView(BusinessAccountView view) {
        return new BusinessAccountResponse(
                view.id(),
                view.corporateName(),
                view.taxId(),
                view.verificationStatus(),
                view.planTier(),
                view.createdAt(),
                view.updatedAt()
        );
    }
}
