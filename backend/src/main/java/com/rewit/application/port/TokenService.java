package com.rewit.application.port;

import com.rewit.domain.enums.Role;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída da aplicação para geração, extração e hashing seguro de tokens de autenticação (JWT e Refresh).
 * Garante que Refresh Tokens sejam vinculados criptograficamente à conta do usuário (UUID) e ao instante exato
 * de expiração (epoch seconds), além de autenticados por HMAC-SHA256 com segredo externo ao banco.
 */
public interface TokenService {

    /**
     * Metadados criptograficamente vinculados ao refresh token bruto (autenticados indiretamente pelo HMAC-SHA256).
     */
    record RefreshTokenMetadata(UUID userId, Instant expiresAt) {
    }

    String generateAccessToken(UUID userId);

    String generateAccessToken(UUID userId, Role role);

    /**
     * Gera um refresh token canônico de 56 bytes contendo:
     * 16 bytes do UUID do usuário + 8 bytes da expiração em epoch seconds + 32 bytes aleatórios de CSPRNG,
     * codificados em Base64URL sem padding (resultado de exatamente 75 caracteres).
     */
    String generateRefreshToken(UUID userId, Instant expiresAt);

    /**
     * Calcula o hash criptográfico do refresh token via HMAC-SHA256 com a chave ativa (64 caracteres hexadecimais).
     */
    String hashRefreshToken(String rawRefreshToken);

    /**
     * Calcula o hash criptográfico do refresh token utilizando a chave anterior de transição, se configurada.
     */
    Optional<String> hashRefreshTokenWithPreviousKey(String rawRefreshToken);

    /**
     * Informa se há segredo anterior configurado para suporte à janela de rotação.
     */
    boolean hasPreviousRefreshTokenSecret();

    /**
     * Valida estritamente a representação canônica de 75 caracteres do refresh token e extrai o UUID e a expiração
     * embutidos. Rejeita tokens com comprimento incorreto, caracteres inválidos, padding ou representação não canônica.
     */
    RefreshTokenMetadata parseRefreshToken(String rawRefreshToken);

    default UUID extractUserIdFromRefreshToken(String rawRefreshToken) {
        return parseRefreshToken(rawRefreshToken).userId();
    }

    UUID extractUserIdFromAccessToken(String token);

    long getAccessTokenTtlSeconds();

    long getRefreshTokenTtlSeconds();
}
