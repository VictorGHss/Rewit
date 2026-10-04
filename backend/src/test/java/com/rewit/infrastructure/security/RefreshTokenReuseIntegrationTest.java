package com.rewit.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.TokenService;
import com.rewit.presentation.dto.auth.LoginRequest;
import com.rewit.presentation.dto.auth.LogoutRequest;
import com.rewit.presentation.dto.auth.RefreshRequest;
import com.rewit.presentation.dto.auth.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Reúso de refresh token com PostgreSQL real (Step 29.1): a revogação em massa precisa estar persistida
 * depois do 401, e a janela de rotação concorrente de 10 segundos continua sem revogar nada.
 * A saída da janela é feita envelhecendo o {@code revoked_at} persistido, sem esperar em tempo real.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração: reúso de refresh token persiste a revogação das sessões (Step 29.1)")
class RefreshTokenReuseIntegrationTest {

    private static final String PASSWORD = "Password123!";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TokenService tokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("Reúso após rotação, fora da janela: 401 e todas as sessões do usuário revogadas no banco")
    void reuseAfterRotationRevokesEverySessionPersistently() throws Exception {
        String email = uniqueEmail();
        String tokenA = register(email).get("refreshToken").asText();
        String tokenC = login(email);
        String tokenD = login(email);
        String tokenB = body(refresh(tokenA)).get("refreshToken").asText();
        ageRevocation(tokenA);

        MvcResult reuse = refresh(tokenA);

        assertEquals(401, reuse.getResponse().getStatus());
        assertEquals("REFRESH_TOKEN_REVOKED", body(reuse).get("code").asText());
        assertEquals(4, sessionCount(email));
        assertEquals(0, activeSessionCount(email), "a revogação em massa precisa sobreviver ao 401");
        assertTrue(isRevoked(tokenA));
        assertNotNull(replacedBy(tokenA), "a sessão reutilizada continua apontando para sua substituta");
        for (String other : List.of(tokenB, tokenC, tokenD)) {
            MvcResult attempt = refresh(other);
            assertEquals(401, attempt.getResponse().getStatus());
            assertEquals("REFRESH_TOKEN_REVOKED", body(attempt).get("code").asText());
        }
    }

    @Test
    @DisplayName("Reúso de token encerrado por logout: revoga as demais sessões, que deixam de renovar")
    void reuseOfLoggedOutTokenRevokesOtherSessions() throws Exception {
        String email = uniqueEmail();
        JsonNode registered = register(email);
        String loggedOut = registered.get("refreshToken").asText();
        String other = login(email);
        mockMvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + registered.get("accessToken").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LogoutRequest(loggedOut))));
        assertEquals(1, activeSessionCount(email));

        MvcResult reuse = refresh(loggedOut);

        assertEquals(401, reuse.getResponse().getStatus());
        assertEquals("REFRESH_TOKEN_REVOKED", body(reuse).get("code").asText());
        assertEquals(0, activeSessionCount(email));
        assertEquals(401, refresh(other).getResponse().getStatus());
    }

    @Test
    @DisplayName("Rotação concorrente recente (janela de 10 s): 401 sem revogar as demais sessões")
    void recentRotationKeepsOtherSessionsActive() throws Exception {
        String email = uniqueEmail();
        String tokenA = register(email).get("refreshToken").asText();
        String other = login(email);
        String tokenB = body(refresh(tokenA)).get("refreshToken").asText();

        MvcResult concurrentRetry = refresh(tokenA);

        assertEquals(401, concurrentRetry.getResponse().getStatus());
        assertEquals("REFRESH_TOKEN_REVOKED", body(concurrentRetry).get("code").asText());
        assertEquals(2, activeSessionCount(email), "a substituta B e a outra sessão continuam ativas");
        assertEquals(200, refresh(tokenB).getResponse().getStatus());
        assertEquals(200, refresh(other).getResponse().getStatus());
    }

    @Test
    @DisplayName("Reúso repetido do mesmo token revogado é estável: sempre 401 e as sessões seguem revogadas")
    void repeatedReuseIsStable() throws Exception {
        String email = uniqueEmail();
        String tokenA = register(email).get("refreshToken").asText();
        login(email);
        refresh(tokenA);
        ageRevocation(tokenA);

        for (int attempt = 0; attempt < 3; attempt++) {
            MvcResult reuse = refresh(tokenA);
            assertEquals(401, reuse.getResponse().getStatus());
            assertEquals("REFRESH_TOKEN_REVOKED", body(reuse).get("code").asText());
        }
        assertEquals(0, activeSessionCount(email));
    }

    @Test
    @DisplayName("Token inexistente e token expirado mantêm seus erros e não revogam as demais sessões")
    void unknownAndExpiredTokensDoNotRevokeSessions() throws Exception {
        String email = uniqueEmail();
        String expiring = register(email).get("refreshToken").asText();
        String other = login(email);
        jdbcTemplate.update("UPDATE auth_sessions SET expires_at = NOW() - INTERVAL '1 minute' WHERE token_hash = ?",
                tokenService.hashRefreshToken(expiring));

        MvcResult unknown = refresh("token-que-nunca-existiu-" + UUID.randomUUID());
        MvcResult expired = refresh(expiring);

        assertEquals(401, unknown.getResponse().getStatus());
        assertEquals("INVALID_REFRESH_TOKEN", body(unknown).get("code").asText());
        assertEquals(401, expired.getResponse().getStatus());
        assertEquals("REFRESH_TOKEN_EXPIRED", body(expired).get("code").asText());
        assertFalse(isRevoked(other));
        assertEquals(200, refresh(other).getResponse().getStatus());
    }

    // Leva a revogação para fora da janela de rotação concorrente sem esperar em tempo real
    private void ageRevocation(String refreshToken) {
        int updated = jdbcTemplate.update(
                "UPDATE auth_sessions SET revoked_at = NOW() - INTERVAL '1 hour' WHERE token_hash = ? AND revoked_at IS NOT NULL",
                tokenService.hashRefreshToken(refreshToken));
        assertEquals(1, updated);
    }

    private int sessionCount(String email) {
        return count("SELECT COUNT(*) FROM auth_sessions s JOIN users u ON u.id = s.user_id WHERE u.email = ?", email);
    }

    private int activeSessionCount(String email) {
        return count("SELECT COUNT(*) FROM auth_sessions s JOIN users u ON u.id = s.user_id "
                + "WHERE u.email = ? AND s.revoked_at IS NULL", email);
    }

    private boolean isRevoked(String refreshToken) {
        return count("SELECT COUNT(*) FROM auth_sessions WHERE token_hash = ? AND revoked_at IS NOT NULL",
                tokenService.hashRefreshToken(refreshToken)) == 1;
    }

    private UUID replacedBy(String refreshToken) {
        return jdbcTemplate.queryForObject("SELECT replaced_by_session_id FROM auth_sessions WHERE token_hash = ?",
                UUID.class, tokenService.hashRefreshToken(refreshToken));
    }

    private int count(String sql, Object argument) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, argument);
        return value == null ? 0 : value;
    }

    private JsonNode register(String email) throws Exception {
        String handle = "reuse_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest(email, PASSWORD, handle, "Reuse Test"))))
                .andReturn();
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return body(result);
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus());
        return body(result).get("refreshToken").asText();
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
                .andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String uniqueEmail() {
        return "reuse_" + UUID.randomUUID().toString().substring(0, 8) + "@rewit.test";
    }
}
