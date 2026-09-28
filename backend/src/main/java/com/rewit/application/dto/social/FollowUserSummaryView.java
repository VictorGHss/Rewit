package com.rewit.application.dto.social;

import java.time.Instant;
import java.util.UUID;

/**
 * Visão resumida de um usuário em relacionamentos de seguidores na camada de aplicação.
 */
public record FollowUserSummaryView(
        UUID userId,
        String handle,
        String displayName,
        String avatarUrl,
        Instant followedAt
) {}
