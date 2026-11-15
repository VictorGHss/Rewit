package com.rewit.infrastructure.security.social;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.rewit.application.dto.auth.AuthDtos.ExternalIdentity;
import com.rewit.application.service.SocialAuthService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.Set;

import static com.rewit.infrastructure.security.social.SocialIdTokenTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Login social: validação do ID token OpenID Connect")
class OidcIdTokenVerifierTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final String NONCE_HASH = SocialAuthService.hashNonce("nonce-da-tentativa");

    private SocialIdTokenTestSupport tokens;
    private OidcIdTokenVerifier google;
    private OidcIdTokenVerifier apple;

    @BeforeEach
    void setUp() {
        tokens = new SocialIdTokenTestSupport();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        google = new OidcIdTokenVerifier(AuthProvider.GOOGLE, Set.of(GOOGLE_ISSUER, "accounts.google.com"),
                Set.of(GOOGLE_CLIENT_ID), tokens.jwkSource(), clock);
        apple = new OidcIdTokenVerifier(AuthProvider.APPLE, Set.of(APPLE_ISSUER),
                Set.of(APPLE_CLIENT_ID), tokens.jwkSource(), clock);
    }

    @Test
    @DisplayName("Token Google válido devolve subject, e-mail verificado e nome das claims")
    void validGoogleToken() {
        String idToken = tokens.sign(googleClaims("google-sub-1", "pessoa@gmail.com", NONCE_HASH, NOW).build());

        ExternalIdentity identity = google.verify(idToken, NONCE_HASH);

        assertEquals(AuthProvider.GOOGLE, identity.provider());
        assertEquals("google-sub-1", identity.subject());
        assertEquals("pessoa@gmail.com", identity.email());
        assertTrue(identity.emailVerified());
        assertEquals("Pessoa Teste", identity.name());
    }

    @Test
    @DisplayName("Emissor alternativo do Google (sem https) é aceito")
    void alternativeGoogleIssuer() {
        String idToken = tokens.sign(googleClaims("google-sub-1", "pessoa@gmail.com", NONCE_HASH, NOW)
                .issuer("accounts.google.com").build());

        assertEquals("google-sub-1", google.verify(idToken, NONCE_HASH).subject());
    }

    @Test
    @DisplayName("Token Apple válido aceita email_verified como string")
    void validAppleToken() {
        String idToken = tokens.sign(appleClaims("001234.abcd.0001", "relay@privaterelay.appleid.com", NONCE_HASH, NOW).build());

        ExternalIdentity identity = apple.verify(idToken, NONCE_HASH);

        assertEquals(AuthProvider.APPLE, identity.provider());
        assertEquals("001234.abcd.0001", identity.subject());
        assertTrue(identity.emailVerified());
        assertNull(identity.name());
    }

    @Test
    @DisplayName("email_verified falso ou ausente não é tratado como verificado")
    void unverifiedEmail() {
        String falseClaim = tokens.sign(appleClaims("s", "a@b.com", NONCE_HASH, NOW).claim("email_verified", "false").build());
        String missingClaim = tokens.sign(googleClaims("s", "a@b.com", NONCE_HASH, NOW).claim("email_verified", null).build());

        assertFalse(apple.verify(falseClaim, NONCE_HASH).emailVerified());
        assertFalse(google.verify(missingClaim, NONCE_HASH).emailVerified());
    }

    @Test
    @DisplayName("Assinatura de outra chave (mesmo kid inexistente no JWKS) é recusada")
    void signatureFromAnotherKey() {
        String forged = SocialIdTokenTestSupport.sign(googleClaims("s", "a@b.com", NONCE_HASH, NOW).build(),
                SocialIdTokenTestSupport.generateKey());

        assertInvalidToken(() -> google.verify(forged, NONCE_HASH));
    }

    @Test
    @DisplayName("Token adulterado depois da assinatura é recusado")
    void tamperedPayload() {
        String valid = tokens.sign(googleClaims("vitima", "a@b.com", NONCE_HASH, NOW).build());
        String[] parts = valid.split("\\.");
        String otherPayload = tokens.sign(googleClaims("atacante", "a@b.com", NONCE_HASH, NOW).build()).split("\\.")[1];

        assertInvalidToken(() -> google.verify(parts[0] + "." + otherPayload + "." + parts[2], NONCE_HASH));
    }

    @Test
    @DisplayName("Tokens sem assinatura (alg=none) ou HS256 são recusados")
    void unsignedAndSymmetricTokens() {
        JWTClaimsSet claims = googleClaims("s", "a@b.com", NONCE_HASH, NOW).build();

        assertInvalidToken(() -> google.verify(SocialIdTokenTestSupport.unsigned(claims), NONCE_HASH));
        assertInvalidToken(() -> google.verify(SocialIdTokenTestSupport.signHs256(claims), NONCE_HASH));
    }

    @Test
    @DisplayName("Token expirado (além da tolerância de 60 s) é recusado; dentro da tolerância, aceito")
    void expiredToken() {
        String expired = tokens.sign(googleClaims("s", "a@b.com", NONCE_HASH, NOW.minusSeconds(7200))
                .expirationTime(Date.from(NOW.minusSeconds(61))).build());
        String withinSkew = tokens.sign(googleClaims("s", "a@b.com", NONCE_HASH, NOW.minusSeconds(3600))
                .expirationTime(Date.from(NOW.minusSeconds(30))).build());

        assertInvalidToken(() -> google.verify(expired, NONCE_HASH));
        assertEquals("s", google.verify(withinSkew, NONCE_HASH).subject());
    }

    @Test
    @DisplayName("Token emitido no futuro ou ainda não válido (nbf) é recusado")
    void notYetValidToken() {
        String futureIat = tokens.sign(googleClaims("s", "a@b.com", NONCE_HASH, NOW.plusSeconds(300)).build());
        String futureNbf = tokens.sign(googleClaims("s", "a@b.com", NONCE_HASH, NOW)
                .notBeforeTime(Date.from(NOW.plusSeconds(300))).build());

        assertInvalidToken(() -> google.verify(futureIat, NONCE_HASH));
        assertInvalidToken(() -> google.verify(futureNbf, NONCE_HASH));
    }

    @Test
    @DisplayName("Audiência de outro aplicativo é recusada")
    void wrongAudience() {
        String otherApp = tokens.sign(googleClaims("s", "a@b.com", NONCE_HASH, NOW)
                .audience("outro-app.apps.googleusercontent.com").build());
        String appleTokenOnGoogle = tokens.sign(appleClaims("s", "a@b.com", NONCE_HASH, NOW).issuer(GOOGLE_ISSUER).build());

        assertInvalidToken(() -> google.verify(otherApp, NONCE_HASH));
        assertInvalidToken(() -> google.verify(appleTokenOnGoogle, NONCE_HASH));
    }

    @Test
    @DisplayName("Emissor diferente é recusado, inclusive token Google apresentado como Apple")
    void wrongIssuer() {
        String fakeIssuer = tokens.sign(googleClaims("s", "a@b.com", NONCE_HASH, NOW).issuer("https://evil.example.com").build());
        String googleAsApple = tokens.sign(googleClaims("s", "a@b.com", NONCE_HASH, NOW).audience(APPLE_CLIENT_ID).build());

        assertInvalidToken(() -> google.verify(fakeIssuer, NONCE_HASH));
        assertInvalidToken(() -> apple.verify(googleAsApple, NONCE_HASH));
    }

    @Test
    @DisplayName("Nonce divergente ou ausente é recusado")
    void nonceMismatch() {
        String otherNonce = tokens.sign(googleClaims("s", "a@b.com", SocialAuthService.hashNonce("outra"), NOW).build());
        String rawInsteadOfHash = tokens.sign(googleClaims("s", "a@b.com", "nonce-da-tentativa", NOW).build());
        String noNonce = tokens.sign(googleClaims("s", "a@b.com", null, NOW).build());

        assertInvalidToken(() -> google.verify(otherNonce, NONCE_HASH));
        assertInvalidToken(() -> google.verify(rawInsteadOfHash, NONCE_HASH));
        assertInvalidToken(() -> google.verify(noNonce, NONCE_HASH));
    }

    @Test
    @DisplayName("Claims obrigatórias ausentes ou inválidas (sub, exp, iat) recusam o token")
    void missingRequiredClaims() {
        String noSubject = tokens.sign(googleClaims(null, "a@b.com", NONCE_HASH, NOW).build());
        String noExpiration = tokens.sign(googleClaims("s", "a@b.com", NONCE_HASH, NOW).expirationTime(null).build());
        String noIssuedAt = tokens.sign(googleClaims("s", "a@b.com", NONCE_HASH, NOW).issueTime(null).build());
        String oversizedSubject = tokens.sign(googleClaims("x".repeat(129), "a@b.com", NONCE_HASH, NOW).build());

        assertInvalidToken(() -> google.verify(noSubject, NONCE_HASH));
        assertInvalidToken(() -> google.verify(noExpiration, NONCE_HASH));
        assertInvalidToken(() -> google.verify(noIssuedAt, NONCE_HASH));
        assertInvalidToken(() -> google.verify(oversizedSubject, NONCE_HASH));
    }

    @Test
    @DisplayName("Texto que não é JWT é recusado como credencial inválida")
    void malformedToken() {
        assertInvalidToken(() -> google.verify("nao-e-um-jwt", NONCE_HASH));
        assertInvalidToken(() -> google.verify(" ", NONCE_HASH));
    }

    @Test
    @DisplayName("JWKS indisponível responde 503 SOCIAL_PROVIDER_UNAVAILABLE, sem aceitar o token")
    void jwksUnavailable() {
        JWKSource<SecurityContext> failing = (selector, context) -> {
            throw new KeySourceException("rede indisponível");
        };
        OidcIdTokenVerifier offline = new OidcIdTokenVerifier(AuthProvider.GOOGLE, Set.of(GOOGLE_ISSUER),
                Set.of(GOOGLE_CLIENT_ID), failing, Clock.fixed(NOW, ZoneOffset.UTC));
        String idToken = tokens.sign(googleClaims("s", "a@b.com", NONCE_HASH, NOW).build());

        BusinessException ex = assertThrows(BusinessException.class, () -> offline.verify(idToken, NONCE_HASH));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("SOCIAL_PROVIDER_UNAVAILABLE", ex.getErrorCode());
    }

    @Test
    @DisplayName("Adapter da porta recusa provedor desligado e despacha para o verificador do provedor")
    void adapterDispatch() {
        OidcExternalIdentityVerifier adapter = new OidcExternalIdentityVerifier(Map.of(AuthProvider.GOOGLE, google));
        String idToken = tokens.sign(googleClaims("s", "a@b.com", NONCE_HASH, NOW).build());

        assertTrue(adapter.isEnabled(AuthProvider.GOOGLE));
        assertFalse(adapter.isEnabled(AuthProvider.APPLE));
        assertFalse(adapter.isEnabled(AuthProvider.LOCAL));
        assertEquals("s", adapter.verify(AuthProvider.GOOGLE, idToken, NONCE_HASH).subject());
        BusinessException ex = assertThrows(BusinessException.class, () -> adapter.verify(AuthProvider.APPLE, idToken, NONCE_HASH));
        assertEquals("SOCIAL_PROVIDER_UNAVAILABLE", ex.getErrorCode());
        assertThrows(IllegalArgumentException.class,
                () -> new OidcExternalIdentityVerifier(Map.of(AuthProvider.LOCAL, google)));
    }

    @Test
    @DisplayName("Configuração sem client IDs desliga o provedor; jwks-uri sem https é recusada")
    void properties() {
        SocialAuthProperties properties = new SocialAuthProperties();
        assertFalse(properties.getGoogle().isEnabled());
        properties.getGoogle().setClientIds(java.util.List.of(" ", ""));
        assertFalse(properties.getGoogle().isEnabled());
        properties.validate();

        properties.getApple().setClientIds(java.util.List.of(" app.rewit.mobile ", "app.rewit.mobile"));
        assertEquals(java.util.List.of("app.rewit.mobile"), properties.getApple().configuredClientIds());
        properties.getApple().setJwksUri("http://appleid.apple.com/auth/keys");
        assertThrows(IllegalStateException.class, properties::validate);

        properties.getApple().setJwksUri("https://appleid.apple.com/auth/keys");
        properties.setAppleAndroidPackage("pacote inválido");
        assertThrows(IllegalStateException.class, properties::validate);
    }

    private static void assertInvalidToken(org.junit.jupiter.api.function.Executable executable) {
        BusinessException ex = assertThrows(BusinessException.class, executable);
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        assertEquals("INVALID_SOCIAL_TOKEN", ex.getErrorCode());
    }
}
