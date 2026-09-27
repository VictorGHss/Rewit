package com.rewit.application.dto.auth;

import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;

import java.util.UUID;

public class AuthDtos {

    public record RegisterCommand(
            String email,
            String password,
            String handle,
            String displayName,
            String userAgent,
            String ipAddress
    ) {}

    public record LoginCommand(
            String email,
            String password,
            String userAgent,
            String ipAddress
    ) {}

    public record RefreshCommand(
            String refreshToken,
            String userAgent,
            String ipAddress
    ) {}

    public record LogoutCommand(
            String refreshToken,
            UUID userId
    ) {}

    public record AuthResult(
            User user,
            Profile profile,
            String accessToken,
            String refreshToken,
            long expiresInSeconds
    ) {}

    public record UserMeResult(
            User user,
            Profile profile
    ) {}
}
