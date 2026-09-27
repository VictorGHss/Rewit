package com.rewit.application.port;

/**
 * Porta de aplicação para validação de identidade e tokens de autenticação externa.
 */
public interface IdentityProvider {

    record AuthUserData(
            String providerUserId,
            String email,
            String name,
            String avatarUrl
    ) {}

    AuthUserData verifyToken(String idToken);
}
