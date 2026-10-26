package com.rewit.infrastructure.security.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.ExternalIdentityVerifier;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.SocialAuthService;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.User;
import com.rewit.presentation.dto.auth.LoginRequest;
import com.rewit.presentation.dto.auth.LogoutRequest;
import com.rewit.presentation.dto.auth.RefreshRequest;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.auth.SocialLoginRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.rewit.infrastructure.security.social.SocialIdTokenTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Login social de ponta a ponta no PostgreSQL e no Redis reais de teste: o nonce é emitido e consumido no Redis, a
 * conta e a sessão são gravadas no banco. Só o JWKS do provedor é substituído por uma chave gerada no teste; a
 * validação (assinatura, emissor, audiência, validade e nonce) é a mesma de produção.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Login social (Google/Apple): integração HTTP, Redis e PostgreSQL")
class SocialAuthenticationIntegrationTest {

    @TestConfiguration
    static class TestProviderKeys {

        @Bean
        SocialIdTokenTestSupport socialIdTokenTestSupport() {
            return new SocialIdTokenTestSupport();
        }

        @Bean
        @Primary
        ExternalIdentityVerifier testExternalIdentityVerifier(SocialIdTokenTestSupport tokens) {
            return new OidcExternalIdentityVerifier(Map.of(
                    AuthProvider.GOOGLE, new OidcIdTokenVerifier(AuthProvider.GOOGLE, Set.of(GOOGLE_ISSUER),
                            Set.of(GOOGLE_CLIENT_ID), tokens.jwkSource(), Clock.systemUTC()),
                    AuthProvider.APPLE, new OidcIdTokenVerifier(AuthProvider.APPLE, Set.of(APPLE_ISSUER),
                            Set.of(APPLE_CLIENT_ID), tokens.jwkSource(), Clock.systemUTC())));
        }
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private SocialIdTokenTestSupport tokens;

    @Autowired
    private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("Google: nonce -> conta federada criada -> /me -> refresh -> logout; segundo login usa a mesma conta")
    void googleFullLifecycle() throws Exception {
        String subject = "google-" + UUID.randomUUID();
        String email = "social." + UUID.randomUUID().toString().substring(0, 8) + "@gmail.com";

        JsonNode first = json(googleLogin(subject, email).andExpect(status().isOk()));
        String userId = first.get("user").get("id").asText();
        assertEquals(email, first.get("user").get("email").asText());
        assertTrue(first.get("user").get("handle").asText().matches("^usuario_[a-z0-9]{10}$"));
        assertEquals("Pessoa Teste", first.get("user").get("displayName").asText());

        User saved = userRepository.findByAuthProviderAndProviderUserId(AuthProvider.GOOGLE, subject).orElseThrow();
        assertEquals(UUID.fromString(userId), saved.getId());
        assertNull(saved.getPasswordHash(), "Conta federada não tem senha");

        String accessToken = first.get("accessToken").asText();
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId));

        JsonNode refreshed = json(mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(first.get("refreshToken").asText()))))
                .andExpect(status().isOk()));
        String refreshToken = refreshed.get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + refreshed.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LogoutRequest(refreshToken))))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
                .andExpect(status().isUnauthorized());

        // Mesmo subject com outro e-mail no token: autentica a conta vinculada, sem criar outra
        JsonNode second = json(googleLogin(subject, "outro." + email).andExpect(status().isOk()));
        assertEquals(userId, second.get("user").get("id").asText());
        assertEquals(email, second.get("user").get("email").asText());
    }

    @Test
    @DisplayName("Nonce é de uso único, inclusive quando a primeira tentativa falha na validação do token")
    void nonceIsSingleUse() throws Exception {
        String nonce = issueNonce();
        Instant now = Instant.now();
        String wrongAudience = tokens.sign(googleClaims("sub-" + UUID.randomUUID(), "a@gmail.com",
                SocialAuthService.hashNonce(nonce), now).audience("outro-app").build());
        String valid = tokens.sign(googleClaims("sub-" + UUID.randomUUID(), "b." + UUID.randomUUID() + "@gmail.com",
                SocialAuthService.hashNonce(nonce), now).build());

        socialLogin("google", wrongAudience, nonce)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SOCIAL_TOKEN"));
        socialLogin("google", valid, nonce)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SOCIAL_NONCE"));
    }

    @Test
    @DisplayName("Nonce inexistente (não emitido pelo Rewit) é recusado")
    void nonceNotIssuedByRewit() throws Exception {
        String forgedNonce = "A".repeat(43);
        String idToken = tokens.sign(googleClaims("sub", "x@gmail.com", SocialAuthService.hashNonce(forgedNonce), Instant.now()).build());

        socialLogin("google", idToken, forgedNonce)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SOCIAL_NONCE"));
    }

    @Test
    @DisplayName("E-mail de conta local existente: 409, sem vinculação silenciosa; login local da conta segue igual")
    void socialEmailMatchingLocalAccount() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "local." + suffix + "@rewit.com";
        String password = "SenhaSegura@" + suffix;
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, password, "local_" + suffix, "Local"))))
                .andExpect(status().isCreated());

        String subject = "google-" + UUID.randomUUID();
        googleLogin(subject, email)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOCIAL_ACCOUNT_EMAIL_CONFLICT"));

        assertTrue(userRepository.findByAuthProviderAndProviderUserId(AuthProvider.GOOGLE, subject).isEmpty());
        User local = userRepository.findByEmail(email).orElseThrow();
        assertEquals(AuthProvider.LOCAL, local.getAuthProvider());
        assertNull(local.getProviderUserId(), "A conta local não recebe a identidade social");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Outra identidade com o e-mail de uma conta social existente não é vinculada a ela")
    void emailOfAnotherSocialAccount() throws Exception {
        String email = "dono." + UUID.randomUUID().toString().substring(0, 8) + "@gmail.com";
        googleLogin("google-" + UUID.randomUUID(), email).andExpect(status().isOk());

        appleLogin("apple-" + UUID.randomUUID(), email)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOCIAL_ACCOUNT_EMAIL_CONFLICT"));
        googleLogin("google-" + UUID.randomUUID(), email)
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Apple: cria a conta; suspensa, a identidade não recebe sessão; senha local não autentica")
    void appleAccountStates() throws Exception {
        String subject = "001122." + UUID.randomUUID().toString().replace("-", "") + ".0001";
        String email = UUID.randomUUID().toString().substring(0, 10) + "@privaterelay.appleid.com";

        JsonNode created = json(appleLogin(subject, email).andExpect(status().isOk()));
        assertEquals("Usuário Rewit", created.get("user").get("displayName").asText());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, "qualquerSenha123"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        User user = userRepository.findByAuthProviderAndProviderUserId(AuthProvider.APPLE, subject).orElseThrow();
        user.suspend();
        userRepository.save(user);

        appleLogin(subject, email)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    @DisplayName("Token Google apresentado no endpoint da Apple é recusado")
    void providerMismatch() throws Exception {
        String nonce = issueNonce();
        String googleToken = tokens.sign(googleClaims("sub", "x@gmail.com", SocialAuthService.hashNonce(nonce), Instant.now()).build());

        socialLogin("apple", googleToken, nonce)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SOCIAL_TOKEN"));
    }

    @Test
    @DisplayName("Corpo sem ID token é recusado na validação (400) e campos extras de identidade são ignorados")
    void requestValidation() throws Exception {
        mockMvc.perform(post("/api/v1/auth/social/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nonce\":\"" + "A".repeat(43) + "\"}"))
                .andExpect(status().isBadRequest());

        String nonce = issueNonce();
        String subject = "google-" + UUID.randomUUID();
        String email = "real." + UUID.randomUUID().toString().substring(0, 8) + "@gmail.com";
        String idToken = tokens.sign(googleClaims(subject, email, SocialAuthService.hashNonce(nonce), Instant.now()).build());
        String body = objectMapper.writeValueAsString(Map.of(
                "idToken", idToken, "nonce", nonce,
                "email", "admin@rewit.com", "userId", UUID.randomUUID().toString(), "role", "ADMIN", "provider", "LOCAL"));

        mockMvc.perform(post("/api/v1/auth/social/google").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email));
        User user = userRepository.findByAuthProviderAndProviderUserId(AuthProvider.GOOGLE, subject).orElseThrow();
        assertEquals(com.rewit.domain.enums.Role.USER, user.getRole());
    }

    @Test
    @DisplayName("Retorno do fluxo web da Apple é repassado ao app Android só com os parâmetros conhecidos, codificados")
    void appleAndroidCallback() throws Exception {
        String location = mockMvc.perform(post("/api/v1/auth/apple/callback")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("code", "c0de")
                        .param("id_token", "a.b.c")
                        .param("state", "s t&x=y")
                        .param("user", "{\"name\":{\"firstName\":\"Ana\"}}")
                        .param("extra", "descartado"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getHeader("Location");

        assertNotNull(location);
        assertTrue(location.startsWith("intent://callback?code=c0de&id_token=a.b.c&state=s+t%26x%3Dy&user=%7B"), location);
        assertTrue(location.endsWith("#Intent;package=app.rewit.mobile;scheme=signinwithapple;end"), location);
        assertFalse(location.contains("extra"));

        mockMvc.perform(post("/api/v1/auth/apple/callback")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("code", "1").param("code", "2"))
                .andExpect(status().isBadRequest());
    }

    private ResultActions googleLogin(String subject, String email) throws Exception {
        String nonce = issueNonce();
        return socialLogin("google", tokens.sign(
                googleClaims(subject, email, SocialAuthService.hashNonce(nonce), Instant.now()).build()), nonce);
    }

    private ResultActions appleLogin(String subject, String email) throws Exception {
        String nonce = issueNonce();
        return socialLogin("apple", tokens.sign(
                appleClaims(subject, email, SocialAuthService.hashNonce(nonce), Instant.now()).build()), nonce);
    }

    private ResultActions socialLogin(String provider, String idToken, String nonce) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/social/" + provider)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SocialLoginRequest(idToken, nonce))));
    }

    private String issueNonce() throws Exception {
        JsonNode node = json(mockMvc.perform(post("/api/v1/auth/social/nonce"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")));
        assertEquals(600, node.get("expiresIn").asLong());
        return node.get("nonce").asText();
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
