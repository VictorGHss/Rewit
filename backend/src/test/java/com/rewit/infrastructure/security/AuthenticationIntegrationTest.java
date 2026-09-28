package com.rewit.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.AuthSessionRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.TokenService;
import com.rewit.application.port.UserRepository;
import com.rewit.domain.model.AuthSession;
import com.rewit.domain.model.User;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração de Autenticação Local e Sessões (Step 4)")
class AuthenticationIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private TokenService tokenService;

    @Test
    @DisplayName("Fluxo completo: Register -> Login -> Me -> Refresh -> Logout -> Rejeição de token antigo")
    void shouldExecuteFullAuthenticationLifecycle() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "dev." + uniqueSuffix + "@rewit.com";
        String handle = "dev_" + uniqueSuffix;
        String password = "SenhaSegura@" + uniqueSuffix;

        // 1. REGISTER
        RegisterRequest registerRequest = new RegisterRequest(
                email,
                password,
                "@" + handle,
                "Desenvolvedor " + uniqueSuffix
        );

        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode registerNode = objectMapper.readTree(registerResult.getResponse().getContentAsString());
        assertNotNull(registerNode.get("accessToken").asText());
        String initialRefreshToken = registerNode.get("refreshToken").asText();
        assertNotNull(initialRefreshToken);
        assertEquals("Bearer", registerNode.get("tokenType").asText());
        assertEquals(email, registerNode.get("user").get("email").asText());
        assertEquals(handle, registerNode.get("user").get("handle").asText());

        // Validar no PostgreSQL real
        Optional<User> savedUserOpt = userRepository.findByEmail(email);
        assertTrue(savedUserOpt.isPresent());
        User savedUser = savedUserOpt.get();
        assertNotEquals(password, savedUser.getPasswordHash(), "A senha no banco nunca pode ser texto puro");
        assertTrue(savedUser.getPasswordHash().startsWith("$argon2id$"));
        assertTrue(profileRepository.existsByHandle(handle));

        String initialTokenHash = tokenService.hashRefreshToken(initialRefreshToken);
        assertTrue(authSessionRepository.findByTokenHash(initialTokenHash).isPresent(), "Sessão inicial deve existir no banco de dados");

        // 2. TENTAR REGISTRAR DUPLICADO (deve ser rejeitado)
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isUnprocessableContent());

        // 3. LOGIN COM SENHA CORRETA
        LoginRequest loginRequest = new LoginRequest(email.toUpperCase(), password);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode loginNode = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String loginAccessToken = loginNode.get("accessToken").asText();
        String loginRefreshToken = loginNode.get("refreshToken").asText();
        assertNotNull(loginAccessToken);
        assertNotNull(loginRefreshToken);

        // 4. LOGIN COM SENHA INCORRETA (deve ser rejeitado genericamente com 401)
        LoginRequest wrongLoginRequest = new LoginRequest(email, "senhaErrada123");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongLoginRequest)))
                .andExpect(status().isUnauthorized());

        // 5. GET /ME SEM TOKEN (deve falhar com 401)
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());

        // 6. GET /ME COM ACCESS TOKEN VÁLIDO (deve retornar dados públicos do perfil)
        MvcResult meResult = mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + loginAccessToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode meNode = objectMapper.readTree(meResult.getResponse().getContentAsString());
        assertEquals(email, meNode.get("email").asText());
        assertEquals(handle, meNode.get("handle").asText());
        assertEquals("Desenvolvedor " + uniqueSuffix, meNode.get("displayName").asText());
        assertFalse(meNode.get("isVerified").asBoolean());
        assertEquals(0, meNode.get("reputationScore").asInt());
        assertNull(meNode.get("passwordHash"), "passwordHash nunca deve ser exposto na API");

        // 7. REFRESH TOKEN (rotação)
        RefreshRequest refreshRequest = new RefreshRequest(loginRefreshToken);
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode refreshNode = objectMapper.readTree(refreshResult.getResponse().getContentAsString());
        String rotatedAccessToken = refreshNode.get("accessToken").asText();
        String rotatedRefreshToken = refreshNode.get("refreshToken").asText();

        assertNotNull(rotatedAccessToken);
        assertNotNull(rotatedRefreshToken);
        assertNotEquals(loginRefreshToken, rotatedRefreshToken, "O refresh token retornado deve ser novo");

        // 8. TENTAR REUTILIZAR REFRESH TOKEN ANTIGO (deve falhar com 401)
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isUnauthorized());

        // 9. LOGOUT (revogação de sessão)
        LogoutRequest logoutRequest = new LogoutRequest(rotatedRefreshToken);
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + rotatedAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutRequest)))
                .andExpect(status().isNoContent());

        // Validar no PostgreSQL que a sessão foi efetivamente revogada
        String rotatedHash = tokenService.hashRefreshToken(rotatedRefreshToken);
        Optional<AuthSession> revokedSession = authSessionRepository.findByTokenHash(rotatedHash);
        assertTrue(revokedSession.isPresent(), "A sessão deve existir no banco de dados");
        assertTrue(revokedSession.get().isRevoked(), "A sessão deve estar marcada como revogada após logout");

        // 10. TENTAR REFRESH APÓS LOGOUT (deve falhar)
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(rotatedRefreshToken))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Usuário soft-deleted deve ter autenticação e acesso a /me bloqueados")
    void shouldBlockAuthenticationAndAccessForSoftDeletedUser() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "deleted." + uniqueSuffix + "@rewit.com";
        String password = "SenhaValida@123";

        // Registrar
        RegisterRequest registerRequest = new RegisterRequest(
                email, password, "del_" + uniqueSuffix, "Deleted User"
        );
        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode node = objectMapper.readTree(registerResult.getResponse().getContentAsString());
        String accessToken = node.get("accessToken").asText();

        // Executar soft-delete
        User user = userRepository.findByEmail(email).orElseThrow();
        user.softDelete();
        userRepository.save(user);

        // Tentativa de login deve falhar com 401
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isUnauthorized());

        // Tentativa de acessar /me com o token pré-existente deve falhar
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }
}
