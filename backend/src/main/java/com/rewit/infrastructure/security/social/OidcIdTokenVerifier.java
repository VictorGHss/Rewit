package com.rewit.infrastructure.security.social;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.rewit.application.dto.auth.AuthDtos.ExternalIdentity;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Set;

/**
 * Validação do ID token OpenID Connect de um provedor: assinatura RS256 com as chaves públicas publicadas pelo
 * provedor (JWKS), emissor, audiência (client IDs do Rewit no provedor), validade ({@code exp}, {@code iat} e
 * {@code nbf}, com tolerância de 60 s), {@code sub} e o nonce da tentativa.
 *
 * <p>Tokens sem assinatura, cifrados ou assinados com outro algoritmo são recusados. Os erros não carregam o token
 * nem suas claims; o log registra apenas o provedor e a classe do erro.
 */
public class OidcIdTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(OidcIdTokenVerifier.class);

    static final int MAX_CLOCK_SKEW_SECONDS = 60;
    // Tamanho de users.provider_user_id
    private static final int MAX_SUBJECT_LENGTH = 128;

    private final AuthProvider provider;
    private final Set<String> issuers;
    private final ConfigurableJWTProcessor<SecurityContext> processor;
    private final Clock clock;

    public OidcIdTokenVerifier(AuthProvider provider, Set<String> issuers, Set<String> audiences,
                               JWKSource<SecurityContext> jwkSource, Clock clock) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.issuers = Set.copyOf(Objects.requireNonNull(issuers, "issuers must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (this.issuers.isEmpty() || audiences == null || audiences.isEmpty()) {
            throw new IllegalArgumentException("Emissor e audiência são obrigatórios para " + provider);
        }

        DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier = new DefaultJWTClaimsVerifier<>(
                Set.copyOf(audiences), null, Set.of("iss", "sub", "aud", "exp", "iat", "nonce"), null) {
            @Override
            protected Date currentTime() {
                return Date.from(OidcIdTokenVerifier.this.clock.instant());
            }
        };
        claimsVerifier.setMaxClockSkew(MAX_CLOCK_SKEW_SECONDS);

        DefaultJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
        jwtProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                Objects.requireNonNull(jwkSource, "jwkSource must not be null")));
        jwtProcessor.setJWTClaimsSetVerifier(claimsVerifier);
        this.processor = jwtProcessor;
    }

    public ExternalIdentity verify(String idToken, String expectedNonce) {
        if (idToken == null || idToken.isBlank() || expectedNonce == null || expectedNonce.isBlank()) {
            throw invalidToken(null);
        }

        JWTClaimsSet claims;
        try {
            claims = processor.process(idToken, null);
        } catch (KeySourceException e) {
            log.warn("Chaves públicas do provedor {} indisponíveis: {}", provider, e.getClass().getSimpleName());
            throw new BusinessException("Não foi possível validar a credencial do provedor agora. Tente novamente.",
                    HttpStatus.SERVICE_UNAVAILABLE, "SOCIAL_PROVIDER_UNAVAILABLE");
        } catch (ParseException | BadJOSEException | JOSEException e) {
            throw invalidToken(e);
        }

        if (claims.getIssuer() == null || !issuers.contains(claims.getIssuer())) {
            throw invalidToken(null);
        }
        Date issuedAt = claims.getIssueTime();
        if (issuedAt.toInstant().isAfter(Instant.now(clock).plusSeconds(MAX_CLOCK_SKEW_SECONDS))) {
            throw invalidToken(null);
        }

        try {
            String nonce = claims.getStringClaim("nonce");
            if (nonce == null || !MessageDigest.isEqual(
                    nonce.getBytes(StandardCharsets.UTF_8), expectedNonce.getBytes(StandardCharsets.UTF_8))) {
                throw invalidToken(null);
            }

            String subject = claims.getSubject();
            if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH) {
                throw invalidToken(null);
            }

            return new ExternalIdentity(
                    provider,
                    subject,
                    claims.getStringClaim("email"),
                    isEmailVerified(claims.getClaim("email_verified")),
                    claims.getStringClaim("name")
            );
        } catch (ParseException e) {
            throw invalidToken(e);
        }
    }

    // Google publica a claim como booleano; a Apple, como booleano ou como a string "true"/"false"
    private static boolean isEmailVerified(Object claim) {
        if (claim instanceof Boolean verified) {
            return verified;
        }
        return claim instanceof String verified && Boolean.parseBoolean(verified);
    }

    private BusinessException invalidToken(Exception cause) {
        if (cause != null) {
            log.debug("ID token do provedor {} recusado: {}", provider, cause.getClass().getSimpleName());
        }
        return new BusinessException("Credencial do provedor inválida", HttpStatus.UNAUTHORIZED, "INVALID_SOCIAL_TOKEN");
    }
}
