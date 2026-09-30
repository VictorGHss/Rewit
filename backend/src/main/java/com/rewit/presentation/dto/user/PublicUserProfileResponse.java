package com.rewit.presentation.dto.user;

import java.util.UUID;

/**
 * Representação pública do perfil de um usuário e suas estatísticas factuais (Step 18.0).
 * O DTO omite estritamente credenciais, e-mails, listas de votantes, histórico privado ou PII.
 */
public record PublicUserProfileResponse(
        UUID id,
        String handle,
        String displayName,
        String bio,
        String avatarUrl,
        UserStatsResponse stats,
        boolean isFollowing
) {
    public record UserStatsResponse(
            long totalReviews,
            long verifiedReviewsCount,
            long followersCount,
            long followingCount,
            long helpfulVotesReceived
    ) {}
}
