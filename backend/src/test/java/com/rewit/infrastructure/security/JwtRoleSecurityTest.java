package com.rewit.infrastructure.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.rewit.config.SecurityConfig;
import com.rewit.domain.enums.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Hardening: Propagação de Role no JWT e Segurança (Step 26.1)")
class JwtRoleSecurityTest {

    private static final String STRONG_SECRET = "e8b5c92f1a4e7d0368a2bf5071de84c935fa782164de90cb15f7a23c4890ef1b";
    private static final String ISSUER = "rewit-api";
    private static final String AUDIENCE = "rewit-clients";

    private JwtTokenService tokenService;
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @BeforeEach
    void setUp() {
        tokenService = new JwtTokenService(STRONG_SECRET, ISSUER, AUDIENCE, 900, 2592000);
        SecurityConfig securityConfig = new SecurityConfig(STRONG_SECRET, ISSUER, AUDIENCE);
        jwtAuthenticationConverter = securityConfig.jwtAuthenticationConverter();
    }

    @Test
    @DisplayName("Token de USER deve conter a claim role com valor USER e authority ROLE_USER")
    void shouldGenerateAndConvertUserRoleToken() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = tokenService.generateAccessToken(userId, Role.USER);

        SignedJWT signedJWT = SignedJWT.parse(token);
        JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

        assertEquals(userId.toString(), claims.getSubject());
        assertEquals("USER", claims.getStringClaim("role"));
        assertEquals(Role.USER, tokenService.extractRoleFromAccessToken(token));

        // Testar conversão do Spring Security
        Jwt jwt = createSpringSecurityJwt(userId, "USER");
        AbstractAuthenticationToken authentication = jwtAuthenticationConverter.convert(jwt);
        assertNotNull(authentication);
        assertEquals(userId.toString(), authentication.getName());
        assertTrue(authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(a -> a.equals("ROLE_USER")));
    }

    @Test
    @DisplayName("Token de MODERATOR deve conter a claim role com valor MODERATOR e authority ROLE_MODERATOR")
    void shouldGenerateAndConvertModeratorRoleToken() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = tokenService.generateAccessToken(userId, Role.MODERATOR);

        SignedJWT signedJWT = SignedJWT.parse(token);
        JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

        assertEquals(userId.toString(), claims.getSubject());
        assertEquals("MODERATOR", claims.getStringClaim("role"));
        assertEquals(Role.MODERATOR, tokenService.extractRoleFromAccessToken(token));

        // Testar conversão do Spring Security
        Jwt jwt = createSpringSecurityJwt(userId, "MODERATOR");
        AbstractAuthenticationToken authentication = jwtAuthenticationConverter.convert(jwt);
        assertNotNull(authentication);
        assertEquals(userId.toString(), authentication.getName());
        assertTrue(authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(a -> a.equals("ROLE_MODERATOR")));
    }

    @Test
    @DisplayName("Token de ADMIN deve conter a claim role com valor ADMIN e authority ROLE_ADMIN")
    void shouldGenerateAndConvertAdminRoleToken() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = tokenService.generateAccessToken(userId, Role.ADMIN);

        SignedJWT signedJWT = SignedJWT.parse(token);
        JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

        assertEquals(userId.toString(), claims.getSubject());
        assertEquals("ADMIN", claims.getStringClaim("role"));
        assertEquals(Role.ADMIN, tokenService.extractRoleFromAccessToken(token));

        // Testar conversão do Spring Security
        Jwt jwt = createSpringSecurityJwt(userId, "ADMIN");
        AbstractAuthenticationToken authentication = jwtAuthenticationConverter.convert(jwt);
        assertNotNull(authentication);
        assertEquals(userId.toString(), authentication.getName());
        assertTrue(authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(a -> a.equals("ROLE_ADMIN")));
    }

    @Test
    @DisplayName("generateAccessToken sem role deve aplicar fallback transparente para Role.USER")
    void shouldFallbackToRoleUserWhenNotSpecified() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = tokenService.generateAccessToken(userId);

        SignedJWT signedJWT = SignedJWT.parse(token);
        JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

        assertEquals(userId.toString(), claims.getSubject());
        assertEquals("USER", claims.getStringClaim("role"));
        assertEquals(Role.USER, tokenService.extractRoleFromAccessToken(token));
    }

    @Test
    @DisplayName("Converter JWT deve aplicar fallback para ROLE_USER se a claim role estiver ausente ou inválida")
    void shouldFallbackToRoleUserInConverterWhenClaimMissingOrInvalid() {
        UUID userId = UUID.randomUUID();

        // 1. Claim ausente
        Jwt jwtWithoutRole = Jwt.withTokenValue("mock-token")
                .header("alg", "HS256")
                .subject(userId.toString())
                .issuer(ISSUER)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();

        AbstractAuthenticationToken auth1 = jwtAuthenticationConverter.convert(jwtWithoutRole);
        assertNotNull(auth1);
        assertTrue(auth1.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(a -> a.equals("ROLE_USER")));

        // 2. Claim inválida
        Jwt jwtInvalidRole = Jwt.withTokenValue("mock-token")
                .header("alg", "HS256")
                .subject(userId.toString())
                .claim("role", "SUPER_GOD_ROLE")
                .issuer(ISSUER)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();

        AbstractAuthenticationToken auth2 = jwtAuthenticationConverter.convert(jwtInvalidRole);
        assertNotNull(auth2);
        assertTrue(auth2.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(a -> a.equals("ROLE_USER")));
    }

    private Jwt createSpringSecurityJwt(UUID userId, String role) {
        return Jwt.withTokenValue("mock-token")
                .header("alg", "HS256")
                .subject(userId.toString())
                .issuer(ISSUER)
                .claim("role", role)
                .claims(claims -> claims.putAll(Map.of(
                        "aud", AUDIENCE,
                        "jti", UUID.randomUUID().toString()
                )))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }
}
