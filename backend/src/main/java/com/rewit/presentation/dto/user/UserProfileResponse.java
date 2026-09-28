package com.rewit.presentation.dto.user;

import java.time.Instant;
import java.util.UUID;

/**
 * Representação pública e de conta do usuário autenticado para as APIs REST (/api/v1/me).
 * O DTO omite estritamente credenciais, hashes, tokens de sessão, IP e metadados de autenticação.
 */
public record UserProfileResponse(
        UUID id,
        String email,
        String handle,
        String displayName,
        String bio,
        String avatarUrl,
        boolean isVerified,
        boolean isAnonymousDefault,
        int reputationScore,
        Instant createdAt
) {}
