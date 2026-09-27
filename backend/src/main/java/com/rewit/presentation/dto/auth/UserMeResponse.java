package com.rewit.presentation.dto.auth;

import java.time.Instant;
import java.util.UUID;

public record UserMeResponse(
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
