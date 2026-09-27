package com.rewit.presentation.dto.auth;

import java.util.UUID;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserSummary user
) {
    public record UserSummary(
            UUID id,
            String email,
            String handle,
            String displayName
    ) {}
}
