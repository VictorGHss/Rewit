package com.rewit.application.dto.auth;

import com.rewit.domain.enums.AuthProvider;
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

    /** Login social: o ID token do provedor e o nonce emitido pelo Rewit (em claro), nada além disso do cliente. */
    public record SocialLoginCommand(
            AuthProvider provider,
            String idToken,
            String nonce,
            String userAgent,
            String ipAddress
    ) {}

    public record SocialNonceResult(
            String nonce,
            long expiresInSeconds
    ) {}

    /**
     * Identidade externa extraída de um ID token verificado. {@code subject} é o identificador estável no provedor;
     * {@code email} e {@code name} podem faltar.
     */
    public record ExternalIdentity(
            AuthProvider provider,
            String subject,
            String email,
            boolean emailVerified,
            String name
    ) {}
}
