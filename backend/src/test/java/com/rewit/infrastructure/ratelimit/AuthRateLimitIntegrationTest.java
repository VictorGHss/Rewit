package com.rewit.infrastructure.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.AuthSessionRepository;
import com.rewit.application.port.PasswordHasher;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.TokenService;
import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.AuthSession;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;
import com.rewit.presentation.dto.auth.LoginRequest;
import com.rewit.presentation.dto.auth.RefreshRequest;
import com.rewit.presentation.dto.auth.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rate limiting de autenticação ponta a ponta: HTTP, Spring Security, AuthService, PostgreSQL e Redis reais.
 * Contexto próprio com limites pequenos e janelas de 2 segundos; usuários criados direto no banco para não
 * consumir o limite global de cadastro, exercitado só pelo teste de cadastro.
 */
@SpringBootTest(properties = {
        "rewit.rate-limit.auth.login.limit=3",
        "rewit.rate-limit.auth.login.window=PT2S",
        "rewit.rate-limit.auth.refresh.limit=2",
        "rewit.rate-limit.auth.refresh.window=PT2S",
        "rewit.rate-limit.auth.registration.limit=2",
        "rewit.rate-limit.auth.registration.window=PT2S"
})
@ActiveProfiles("local")
@DisplayName("Rate limiting de autenticação: HTTP com Redis real")
class AuthRateLimitIntegrationTest {

    private static final long WINDOW_WAIT_MILLIS = 2_300;
    private static final String PASSWORD = "Senha-Limite-123";

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProfileRepository profileRepository;
    @Autowired
    private AuthSessionRepository authSessionRepository;
    @Autowired
    private PasswordHasher passwordHasher;
    @Autowired
    private TokenService tokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("Login: e-mail inexistente e senha incorreta recebem 401 até o limite e depois 429, mesmo com a senha certa")
    void loginLimitAppliesToUnknownAndExistingIdentities() throws Exception {
        String email = createUser();
        String unknown = "ninguem-" + UUID.randomUUID() + "@rewit.com";

        for (int i = 0; i < 3; i++) {
            login(unknown, PASSWORD).andExpect(status().isUnauthorized())
                    .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
            login(email, "Senha-Errada-1").andExpect(status().isUnauthorized())
                    .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        expectTooManyRequests(login(unknown, PASSWORD), "Muitas tentativas de login. Tente novamente mais tarde.");
        expectTooManyRequests(login(email, PASSWORD), "Muitas tentativas de login. Tente novamente mais tarde.");
    }

    @Test
    @DisplayName("Login: sucessos não consomem o limite; após a janela, o login volta a funcionar")
    void successfulLoginsAreNotCountedAndWindowExpires() throws Exception {
        String email = createUser();
        for (int i = 0; i < 5; i++) {
            login(email, PASSWORD).andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
        }
        for (int i = 0; i < 3; i++) {
            login(email, "Senha-Errada-1").andExpect(status().isUnauthorized())
                    .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
        }
        expectTooManyRequests(login(email, PASSWORD), null);

        Thread.sleep(WINDOW_WAIT_MILLIS);

        login(email, PASSWORD).andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("Cadastro: acima do limite global recebe 429; após a janela, aceita novamente")
    void registrationLimitAndWindow() throws Exception {
        register().andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
        register().andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
        expectTooManyRequests(register(), "Muitas tentativas de cadastro. Tente novamente mais tarde.");

        Thread.sleep(WINDOW_WAIT_MILLIS);

        register().andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("Refresh: a rotação segue normal até o limite; o 429 não consome o token, que funciona após a janela")
    void refreshLimitKeepsRotationIntact() throws Exception {
        String email = createUser();
        String token = refreshTokenOf(login(email, PASSWORD));

        token = refreshTokenOf(refresh(token).andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER)));
        token = refreshTokenOf(refresh(token).andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER)));
        expectTooManyRequests(refresh(token), "Muitas renovações de sessão. Tente novamente mais tarde.");

        AuthSession current = authSessionRepository.findByTokenHash(tokenService.hashRefreshToken(token)).orElseThrow();
        assertTrue(current.isValid(), "o refresh limitado não revogou nem rotacionou a sessão");

        Thread.sleep(WINDOW_WAIT_MILLIS);

        refresh(token).andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("Refresh: com o limite esgotado, o reúso de token revogado ainda revoga todas as sessões (401, não 429)")
    void reuseDetectionStillWorksWithExhaustedLimit() throws Exception {
        String email = createUser();
        String revokedToken = refreshTokenOf(login(email, PASSWORD));
        AuthSession revoked = authSessionRepository.findByTokenHash(tokenService.hashRefreshToken(revokedToken)).orElseThrow();
        revoked.revoke();
        authSessionRepository.save(revoked);

        String token = refreshTokenOf(login(email, PASSWORD));
        token = refreshTokenOf(refresh(token).andExpect(status().isOk()));
        token = refreshTokenOf(refresh(token).andExpect(status().isOk()));
        expectTooManyRequests(refresh(token), null);

        refresh(revokedToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REVOKED"));

        AuthSession current = authSessionRepository.findByTokenHash(tokenService.hashRefreshToken(token)).orElseThrow();
        assertTrue(current.isRevoked(), "a revogação em massa do reúso foi persistida");
    }

    private String createUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.save(new User(null, "rl." + suffix + "@rewit.com", passwordHasher.hash(PASSWORD),
                AuthProvider.LOCAL, null));
        profileRepository.save(new Profile(null, user.getId(), "rl_" + suffix, "Rate Limit " + suffix, null, null));
        return user.getEmail();
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, password))));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))));
    }

    private ResultActions register() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest request = new RegisterRequest("reg." + suffix + "@rewit.com", PASSWORD, "reg_" + suffix, "Cadastro " + suffix);
        return mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private String refreshTokenOf(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("refreshToken").asText();
    }

    private static void expectTooManyRequests(ResultActions result, String detail) throws Exception {
        result.andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("^[1-9]\\d*$")))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.type").value("https://api.rewit.app/errors/rate_limit_exceeded"));
        if (detail != null) {
            result.andExpect(jsonPath("$.detail").value(detail));
        }
    }
}
