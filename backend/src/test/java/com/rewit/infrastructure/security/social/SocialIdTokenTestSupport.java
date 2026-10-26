package com.rewit.infrastructure.security.social;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Emissor de ID tokens de teste no formato dos provedores (RS256 com {@code kid}), assinados por uma chave RSA
 * gerada no próprio teste. Nenhuma credencial real de provedor é usada.
 */
public final class SocialIdTokenTestSupport {

    public static final String GOOGLE_ISSUER = "https://accounts.google.com";
    public static final String GOOGLE_CLIENT_ID = "test-web-client.apps.googleusercontent.com";
    public static final String APPLE_ISSUER = "https://appleid.apple.com";
    public static final String APPLE_CLIENT_ID = "app.rewit.mobile.test";

    private final RSAKey signingKey;

    public SocialIdTokenTestSupport() {
        this.signingKey = generateKey();
    }

    public static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** JWKS público correspondente à chave de assinatura. */
    public JWKSource<SecurityContext> jwkSource() {
        return new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
    }

    public RSAKey signingKey() {
        return signingKey;
    }

    /** Claims válidas de um ID token do Google, emitido agora e válido por uma hora. */
    public static JWTClaimsSet.Builder googleClaims(String subject, String email, String nonce, Instant now) {
        return new JWTClaimsSet.Builder()
                .issuer(GOOGLE_ISSUER)
                .audience(GOOGLE_CLIENT_ID)
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("nonce", nonce)
                .claim("email", email)
                .claim("email_verified", true)
                .claim("name", "Pessoa Teste");
    }

    /** Claims válidas de um identity token da Apple (sem nome; {@code email_verified} como string). */
    public static JWTClaimsSet.Builder appleClaims(String subject, String email, String nonce, Instant now) {
        return new JWTClaimsSet.Builder()
                .issuer(APPLE_ISSUER)
                .audience(APPLE_CLIENT_ID)
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .claim("nonce", nonce)
                .claim("email", email)
                .claim("email_verified", "true");
    }

    public String sign(JWTClaimsSet claims) {
        return sign(claims, signingKey);
    }

    public static String sign(JWTClaimsSet claims, RSAKey key) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Token HS256 com um segredo qualquer: um atacante não tem a chave privada do provedor. */
    public static String signHs256(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner("attacker-chosen-secret-with-at-least-32-bytes!!"));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String unsigned(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }
}
