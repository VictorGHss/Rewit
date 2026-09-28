package com.rewit.infrastructure.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.rewit.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Hardening: Validação de Algoritmo e Segurança do JWT (Step 4.1)")
class JwtAlgorithmAndSecurityTest {

    private static final String STRONG_SECRET = "e8b5c92f1a4e7d0368a2bf5071de84c935fa782164de90cb15f7a23c4890ef1b";
    private static final String ISSUER = "rewit-api";
    private static final String AUDIENCE = "rewit-clients";

    private JwtTokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new JwtTokenService(STRONG_SECRET, ISSUER, AUDIENCE, 900, 2592000);
    }

    @Test
    @DisplayName("1. Token sem assinatura (alg=none / PlainJWT) deve ser sumariamente rejeitado")
    void shouldRejectTokenWithAlgorithmNone() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .audience(AUDIENCE)
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .build();

        PlainJWT plainJwt = new PlainJWT(claims);
        String tokenWithoutSignature = plainJwt.serialize();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                tokenService.extractUserIdFromAccessToken(tokenWithoutSignature));

        assertEquals("INVALID_TOKEN", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("alg=none") || ex.getMessage().contains("não assinado") || ex.getMessage().contains("malformado"));
    }

    @Test
    @DisplayName("2. Token assinado com algoritmo divergente de HS256 (ex: HS384) deve ser rejeitado")
    void shouldRejectTokenWithDifferentAlgorithm() throws Exception {
        UUID userId = UUID.randomUUID();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .audience(AUDIENCE)
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .build();

        // Assina propositalmente com HS384 em vez de HS256
        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS384), claims);
        JWSSigner signer = new MACSigner(STRONG_SECRET.getBytes(StandardCharsets.UTF_8));
        signedJWT.sign(signer);
        String tokenWithHs384 = signedJWT.serialize();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                tokenService.extractUserIdFromAccessToken(tokenWithHs384));

        assertEquals("INVALID_TOKEN", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Algoritmo de token inválido") || ex.getMessage().contains("HS256"));
    }

    @Test
    @DisplayName("3. Token com assinatura adulterada/inválida deve ser rejeitado")
    void shouldRejectTokenWithInvalidSignature() {
        UUID userId = UUID.randomUUID();
        String validToken = tokenService.generateAccessToken(userId);

        // Modifica deterministicamente o primeiro caractere da parte da assinatura (após o último ponto)
        int lastDotIndex = validToken.lastIndexOf('.');
        String prefix = validToken.substring(0, lastDotIndex + 1);
        String signature = validToken.substring(lastDotIndex + 1);
        char firstSigChar = signature.charAt(0);
        char alteredSigChar = (firstSigChar == 'A') ? 'B' : 'A';
        String corruptedToken = prefix + alteredSigChar + signature.substring(1);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                tokenService.extractUserIdFromAccessToken(corruptedToken));

        assertEquals("INVALID_TOKEN", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Assinatura de token inválida") || ex.getMessage().contains("malformado"));
    }

    @Test
    @DisplayName("4. Token expirado deve ser rejeitado com código TOKEN_EXPIRED")
    void shouldRejectExpiredToken() throws Exception {
        UUID userId = UUID.randomUUID();
        Instant past = Instant.now().minusSeconds(3600);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .audience(AUDIENCE)
                .issueTime(Date.from(past.minusSeconds(900)))
                .expirationTime(Date.from(past))
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        JWSSigner signer = new MACSigner(STRONG_SECRET.getBytes(StandardCharsets.UTF_8));
        signedJWT.sign(signer);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                tokenService.extractUserIdFromAccessToken(signedJWT.serialize()));

        assertEquals("TOKEN_EXPIRED", ex.getErrorCode());
    }

    @Test
    @DisplayName("5. Token com data futura 'not before' (nbf) não deve ser aceito")
    void shouldRejectTokenWithFutureNotBefore() throws Exception {
        UUID userId = UUID.randomUUID();
        Instant future = Instant.now().plusSeconds(600);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .audience(AUDIENCE)
                .issueTime(new Date())
                .notBeforeTime(Date.from(future))
                .expirationTime(Date.from(future.plusSeconds(900)))
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        JWSSigner signer = new MACSigner(STRONG_SECRET.getBytes(StandardCharsets.UTF_8));
        signedJWT.sign(signer);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                tokenService.extractUserIdFromAccessToken(signedJWT.serialize()));

        assertEquals("INVALID_TOKEN", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("nbf no futuro"));
    }

    @Test
    @DisplayName("6. Token legítimo deve ser validado e retornar o ID do usuário correto")
    void shouldAcceptValidToken() {
        UUID userId = UUID.randomUUID();
        String validToken = tokenService.generateAccessToken(userId);

        UUID extracted = tokenService.extractUserIdFromAccessToken(validToken);
        assertEquals(userId, extracted);
    }
}
