package com.rewit.infrastructure.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.rewit.application.port.TokenService;
import com.rewit.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * Serviço de infraestrutura para geração, assinatura, validação e hashing de tokens (JWT e Refresh).
 * Utiliza HMAC-SHA256 (HS256) com segredo simétrico forte e Nimbus JOSE JWT.
 */
@Service
public class JwtTokenService implements TokenService {

    private final byte[] secretKeyBytes;
    private final String issuer;
    private final String audience;
    private final long accessTokenTtlSeconds;
    private final long refreshTokenTtlSeconds;
    private final SecureRandom secureRandom = new SecureRandom();

    public JwtTokenService(
            @Value("${rewit.security.jwt-secret}") String jwtSecret,
            @Value("${rewit.security.jwt-issuer:rewit-api}") String issuer,
            @Value("${rewit.security.jwt-audience:rewit-clients}") String audience,
            @Value("${rewit.security.access-token-ttl-seconds:900}") long accessTokenTtlSeconds,
            @Value("${rewit.security.refresh-token-ttl-seconds:2592000}") long refreshTokenTtlSeconds
    ) {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT_SECRET deve possuir no mínimo 32 bytes (256 bits) para HMAC-SHA256");
        }
        this.secretKeyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
        this.audience = audience;
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

    @Override
    public String generateAccessToken(UUID userId) {
        Objects.requireNonNull(userId, "userId cannot be null");
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(accessTokenTtlSeconds);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(userId.toString())
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .jwtID(UUID.randomUUID().toString())
                .build();

        try {
            SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
            JWSSigner signer = new MACSigner(secretKeyBytes);
            signedJWT.sign(signer);
            return signedJWT.serialize();
        } catch (JOSEException e) {
            throw new BusinessException("Falha na geração do access token JWT", "TOKEN_GENERATION_FAILED");
        }
    }

    @Override
    public String generateRefreshToken() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    @Override
    public String hashRefreshToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException("Refresh token inválido ou ausente", "INVALID_REFRESH_TOKEN");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawRefreshToken.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algoritmo SHA-256 não disponível no ambiente", e);
        }
    }

    @Override
    public UUID extractUserIdFromAccessToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException("Token JWT não informado", "AUTHENTICATION_REQUIRED");
        }
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            JWSVerifier verifier = new MACVerifier(secretKeyBytes);
            if (!signedJWT.verify(verifier)) {
                throw new BusinessException("Assinatura de token inválida", "INVALID_TOKEN");
            }

            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            Date expiry = claims.getExpirationTime();
            if (expiry == null || expiry.before(new Date())) {
                throw new BusinessException("Token JWT expirado", "TOKEN_EXPIRED");
            }

            if (!issuer.equals(claims.getIssuer())) {
                throw new BusinessException("Emissor do token inválido", "INVALID_TOKEN");
            }

            if (!claims.getAudience().contains(audience)) {
                throw new BusinessException("Destinatário do token inválido", "INVALID_TOKEN");
            }

            String sub = claims.getSubject();
            if (sub == null || sub.isBlank()) {
                throw new BusinessException("Identificador do sujeito ausente no token", "INVALID_TOKEN");
            }
            return UUID.fromString(sub);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException("Token JWT malformado ou inválido", "INVALID_TOKEN");
        }
    }

    @Override
    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    @Override
    public long getRefreshTokenTtlSeconds() {
        return refreshTokenTtlSeconds;
    }

    public byte[] getSecretKeyBytes() {
        return secretKeyBytes.clone();
    }

    public String getIssuer() {
        return issuer;
    }

    public String getAudience() {
        return audience;
    }
}
