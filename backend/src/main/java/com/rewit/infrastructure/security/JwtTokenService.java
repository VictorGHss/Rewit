package com.rewit.infrastructure.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.rewit.domain.enums.Role;
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
        JwtSecretValidator.validate(jwtSecret);
        this.secretKeyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
        this.audience = audience;
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

    @Override
    public String generateAccessToken(UUID userId) {
        return generateAccessToken(userId, Role.USER);
    }

    @Override
    public String generateAccessToken(UUID userId, Role role) {
        Objects.requireNonNull(userId, "userId cannot be null");
        Role effectiveRole = role != null ? role : Role.USER;
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(accessTokenTtlSeconds);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(userId.toString())
                .audience(audience)
                .claim("role", effectiveRole.name())
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
        JWTClaimsSet claims = validateAndGetClaims(token);
        String sub = claims.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new BusinessException("Identificador do sujeito ausente no token", "INVALID_TOKEN");
        }
        return UUID.fromString(sub);
    }

    public Role extractRoleFromAccessToken(String token) {
        JWTClaimsSet claims = validateAndGetClaims(token);
        Object roleClaim = claims.getClaim("role");
        if (roleClaim instanceof String roleStr && !roleStr.isBlank()) {
            try {
                return Role.valueOf(roleStr.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return Role.USER;
            }
        }
        return Role.USER;
    }

    private JWTClaimsSet validateAndGetClaims(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException("Token JWT não informado", "AUTHENTICATION_REQUIRED");
        }
        try {
            com.nimbusds.jwt.JWT parsedJwt = com.nimbusds.jwt.JWTParser.parse(token);
            if (!(parsedJwt instanceof SignedJWT signedJWT)) {
                throw new BusinessException("Token JWT não assinado (alg=none rejeitado)", "INVALID_TOKEN");
            }

            if (!JWSAlgorithm.HS256.equals(signedJWT.getHeader().getAlgorithm())) {
                throw new BusinessException("Algoritmo de token inválido. Apenas HS256 é aceito", "INVALID_TOKEN");
            }

            JWSVerifier verifier = new MACVerifier(secretKeyBytes);
            if (!signedJWT.verify(verifier)) {
                throw new BusinessException("Assinatura de token inválida", "INVALID_TOKEN");
            }

            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            Date now = new Date();

            Date expiry = claims.getExpirationTime();
            if (expiry == null || expiry.before(now)) {
                throw new BusinessException("Token JWT expirado", "TOKEN_EXPIRED");
            }

            Date notBefore = claims.getNotBeforeTime();
            if (notBefore != null && notBefore.after(now)) {
                throw new BusinessException("Token JWT ainda não é válido (nbf no futuro)", "INVALID_TOKEN");
            }

            if (!issuer.equals(claims.getIssuer())) {
                throw new BusinessException("Emissor do token inválido", "INVALID_TOKEN");
            }

            if (!claims.getAudience().contains(audience)) {
                throw new BusinessException("Destinatário do token inválido", "INVALID_TOKEN");
            }

            return claims;
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
