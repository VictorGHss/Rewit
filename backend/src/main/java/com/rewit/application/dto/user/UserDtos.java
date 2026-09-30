package com.rewit.application.dto.user;

import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;

import java.util.UUID;

/**
 * DTOs e Commands para operações de consulta e atualização de usuário e perfil na camada de aplicação.
 */
public class UserDtos {

    public record UpdateProfileCommand(
            UUID userId,
            String handle,
            String displayName,
            String bio,
            Boolean isAnonymousDefault
    ) {}

    public record UserProfileResult(
            User user,
            Profile profile
    ) {}

    public record ChangePasswordCommand(
            UUID userId,
            String currentPassword,
            String newPassword
    ) {}

    public record UserStatsView(
            long totalReviews,
            long verifiedReviewsCount,
            long followersCount,
            long followingCount,
            long helpfulVotesReceived
    ) {}

    public record PublicUserProfileView(
            UUID id,
            String handle,
            String displayName,
            String bio,
            String avatarUrl,
            UserStatsView stats,
            boolean isFollowing
    ) {}
}
