package com.rewit.application.port;

import java.util.UUID;

/**
 * Porta de saída da aplicação para geração, extração e hashing de tokens de autenticação (JWT e Refresh).
 */
public interface TokenService {

    String generateAccessToken(UUID userId);

    String generateRefreshToken();

    String hashRefreshToken(String rawRefreshToken);

    UUID extractUserIdFromAccessToken(String token);

    long getAccessTokenTtlSeconds();

    long getRefreshTokenTtlSeconds();
}
