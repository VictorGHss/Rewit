package com.rewit.presentation.dto.auth;

public record LogoutRequest(
        String refreshToken
) {}
