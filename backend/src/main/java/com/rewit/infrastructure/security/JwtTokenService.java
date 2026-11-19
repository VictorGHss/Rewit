package com.rewit.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.rewit.application.port.TokenService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Serviço de infraestrutura para geração, assinatura, validação e hashing de tokens (JWT e Refresh).
 *
 * <p>Hardening de segurança:
 * <ul>
 *   <li>Access token: JWT assinado com HMAC-SHA256 (HS256) via {@code JWT_SECRET} decodificado pelo caminho canônico
 *       {@link SecuritySecretValidator#decodeSecret(String, String)}.</li>
 *   <li>Refresh token: 56 bytes (16 bytes UUID + 8 bytes expiresAt epoch seconds + 32 bytes CSPRNG) em Base64URL
 *       sem padding (exatamente 75 caracteres canônicos).</li>
 *   <li>Hash do refresh token: HMAC-SHA256 com segredo exclusivo {@code REFRESH_TOKEN_SECRET} (64 hexadecimais).</li>
 *   <li>Suporte fail-closed à rotação de chaves durante a janela de transição.</li>
 * </ul>
 */
@Service
public class JwtTokenService implements TokenService {

    public static final int REFRESH_TOKEN_RAW_BYTES = 56;
    public static final int REFRESH_TOKEN_ENCODED_LENGTH = 75;
    private static final Pattern BASE64URL_75_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{75}$");

    private final byte[] jwtSecretBytes;
    private final byte[] activeRefreshTokenSecretBytes;
    private final byte[] previousRefreshTokenSecretBytes;
    private final String issuer;
    private final String audience;
    private final long accessTokenTtlSeconds;
    private final long refreshTokenTtlSeconds;
    private final SecureRandom secureRandom = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public JwtTokenService(
            @Value("${rewit.security.jwt-secret}") String jwtSecret,
            @Value("${rewit.security.refresh-token-secret}") String refreshTokenSecret,
            @Value("${rewit.security.refresh-token-secret-previous:}") String previousRefreshTokenSecret,
            @Value("${rewit.security.jwt-issuer:rewit-api}") String issuer,
            @Value("${rewit.security.jwt-audience:rewit-clients}") String audience,
            @Value("${rewit.security.access-token-ttl-seconds:900}") long accessTokenTtlSeconds,
            @Value("${rewit.security.refresh-token-ttl-seconds:2592000}") long refreshTokenTtlSeconds
    ) {
        this.jwtSecretBytes = SecuritySecretValidator.decodeSecret("JWT_SECRET", jwtSecret);
        this.activeRefreshTokenSecretBytes = SecuritySecretValidator.decodeSecret("REFRESH_TOKEN_SECRET", refreshTokenSecret);
        SecuritySecretValidator.validateDistinctSecrets(
                "JWT_SECRET", this.jwtSecretBytes,
                "REFRESH_TOKEN_SECRET", this.activeRefreshTokenSecretBytes
        );

        if (previousRefreshTokenSecret != null && !previousRefreshTokenSecret.isBlank()) {
            this.previousRefreshTokenSecretBytes = SecuritySecretValidator.decodeSecret("REFRESH_TOKEN_SECRET_PREVIOUS", previousRefreshTokenSecret);
            SecuritySecretValidator.validateDistinctSecrets(
                    "JWT_SECRET", this.jwtSecretBytes,
                    "REFRESH_TOKEN_SECRET_PREVIOUS", this.previousRefreshTokenSecretBytes
            );
            SecuritySecretValidator.validateDistinctSecrets(
                    "REFRESH_TOKEN_SECRET", this.activeRefreshTokenSecretBytes,
                    "REFRESH_TOKEN_SECRET_PREVIOUS", this.previousRefreshTokenSecretBytes
            );
        } else {
            this.previousRefreshTokenSecretBytes = null;
        }

        this.issuer = issuer;
        this.audience = audience;
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

    public JwtTokenService(String jwtSecret, String refreshTokenSecret) {
        this(jwtSecret, refreshTokenSecret, null, "rewit-api", "rewit-clients", 900, 2592000);
    }

    public JwtTokenService(String jwtSecret, String refreshTokenSecret, String issuer, String audience,
                           long accessTokenTtlSeconds, long refreshTokenTtlSeconds) {
        this(jwtSecret, refreshTokenSecret, null, issuer, audience, accessTokenTtlSeconds, refreshTokenTtlSeconds);
    }

    public JwtTokenService(String jwtSecret, String issuer, String audience,
                           long accessTokenTtlSeconds, long refreshTokenTtlSeconds) {
        this(jwtSecret, "rewit_in_memory_test_refresh_entropy_v8m2q7x4z1w5p3l8n0y6!", null, issuer, audience, accessTokenTtlSeconds, refreshTokenTtlSeconds);
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
            JWSSigner signer = new MACSigner(jwtSecretBytes);
            signedJWT.sign(signer);
            return signedJWT.serialize();
        } catch (JOSEException e) {
            throw new BusinessException("Falha na geração do access token JWT", "TOKEN_GENERATION_FAILED");
        }
    }

    @Override
    public String generateRefreshToken(UUID userId, Instant expiresAt) {
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(expiresAt, "expiresAt cannot be null");

        ByteBuffer buffer = ByteBuffer.allocate(REFRESH_TOKEN_RAW_BYTES);
        buffer.putLong(userId.getMostSignificantBits());
        buffer.putLong(userId.getLeastSignificantBits());
        buffer.putLong(expiresAt.getEpochSecond());

        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        buffer.put(randomBytes);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    @Override
    public String hashRefreshToken(String rawRefreshToken) {
        validateAndDecodeRefreshToken(rawRefreshToken);
        byte[] hmac = computeHmac(activeRefreshTokenSecretBytes, rawRefreshToken);
        return HexFormat.of().formatHex(hmac);
    }

    @Override
    public Optional<String> hashRefreshTokenWithPreviousKey(String rawRefreshToken) {
        if (previousRefreshTokenSecretBytes == null) {
            return Optional.empty();
        }
        validateAndDecodeRefreshToken(rawRefreshToken);
        byte[] hmac = computeHmac(previousRefreshTokenSecretBytes, rawRefreshToken);
        return Optional.of(HexFormat.of().formatHex(hmac));
    }

    @Override
    public boolean hasPreviousRefreshTokenSecret() {
        return previousRefreshTokenSecretBytes != null;
    }

    @Override
    public RefreshTokenMetadata parseRefreshToken(String rawRefreshToken) {
        byte[] decoded = validateAndDecodeRefreshToken(rawRefreshToken);
        ByteBuffer buffer = ByteBuffer.wrap(decoded, 0, 24);
        long mostSig = buffer.getLong();
        long leastSig = buffer.getLong();
        long expiresAtEpochSeconds = buffer.getLong();
        return new RefreshTokenMetadata(new UUID(mostSig, leastSig), Instant.ofEpochSecond(expiresAtEpochSeconds));
    }

    private void validateRefreshTokenFormat(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank() || rawRefreshToken.length() != REFRESH_TOKEN_ENCODED_LENGTH) {
            throw new BusinessException("Refresh token inválido ou ausente", HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");
        }
        if (!BASE64URL_75_PATTERN.matcher(rawRefreshToken).matches()) {
            throw new BusinessException("Refresh token com formato inválido", HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");
        }
    }

    private byte[] validateAndDecodeRefreshToken(String rawRefreshToken) {
        validateRefreshTokenFormat(rawRefreshToken);
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(rawRefreshToken);
            if (decoded.length != REFRESH_TOKEN_RAW_BYTES) {
                throw new BusinessException("Refresh token inválido", HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");
            }
            // Assegura representação canônica estrita (sem bits de padding não zerados ou caracteres supérfluos)
            String reEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
            if (!reEncoded.equals(rawRefreshToken)) {
                throw new BusinessException("Refresh token com representação não canônica", HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Refresh token inválido", HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");
        }
    }

    private byte[] computeHmac(byte[] key, String rawRefreshToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(rawRefreshToken.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Algoritmo HmacSHA256 não disponível no ambiente", e);
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

            JWSVerifier verifier = new MACVerifier(jwtSecretBytes);
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

            List<String> aud = claims.getAudience();
            if (aud == null || !aud.contains(audience)) {
                throw new BusinessException("Audiência do token JWT inválida", "INVALID_TOKEN");
            }

            String iss = claims.getIssuer();
            if (iss == null || !iss.equals(issuer)) {
                throw new BusinessException("Emissor do token JWT inválido", "INVALID_TOKEN");
            }

            return claims;
        } catch (java.text.ParseException e) {
            throw new BusinessException("Formato de token JWT malformado", "INVALID_TOKEN");
        } catch (JOSEException e) {
            throw new BusinessException("Falha na validação criptográfica do token JWT", "INVALID_TOKEN");
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
}
