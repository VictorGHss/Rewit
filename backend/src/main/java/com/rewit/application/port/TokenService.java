package com.rewit.application.port;

import com.rewit.domain.enums.Role;

import java.util.UUID;

/**
 * Porta de saída da aplicação para geração, extração e hashing de tokens de autenticação (JWT e Refresh).
 */
public interface TokenService {

    String generateAccessToken(UUID userId);

    String generateAccessToken(UUID userId, Role role);

    String generateRefreshToken();

    String hashRefreshToken(String rawRefreshToken);

    UUID extractUserIdFromAccessToken(String token);

    long getAccessTokenTtlSeconds();

    long getRefreshTokenTtlSeconds();
}
