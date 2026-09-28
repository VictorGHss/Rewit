package com.rewit.presentation.dto.social;

import java.time.Instant;
import java.util.UUID;

/**
 * Representação pública resumida de usuário nas listagens de seguidores e seguidos.
 * Omite estritamente dados sensíveis como e-mail, senhas, tokens e credenciais.
 */
public record FollowUserSummaryResponse(
        UUID id,
        String handle,
        String displayName,
        String avatarUrl,
        Instant followedAt
) {}
