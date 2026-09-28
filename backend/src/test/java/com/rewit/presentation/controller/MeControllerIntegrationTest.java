package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.user.UpdateProfileRequest;
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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração da API de Usuário e Perfil /api/v1/me (Step 5)")
class MeControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private record TestUserCredentials(String email, String handle, String password, String accessToken, UUID userId) {}

    private TestUserCredentials registerNewUser(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "." + suffix + "@rewit.com";
        String handle = prefix + "_" + suffix;
        String password = "Password@" + suffix;

        RegisterRequest req = new RegisterRequest(email, password, "@" + handle, "Nome " + suffix);
        MvcResult res = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        String token = node.get("accessToken").asText();
        UUID id = UUID.fromString(node.get("user").get("id").asText());
        return new TestUserCredentials(email, handle, password, token, id);
    }

    @Test
    @DisplayName("1. GET /api/v1/me autenticado deve retornar os dados corretos do usuário e perfil")
    void shouldReturnAuthenticatedUserData() throws Exception {
        TestUserCredentials credentials = registerNewUser("me_get");

        MvcResult result = mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + credentials.accessToken()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(credentials.userId().toString(), node.get("id").asText());
        assertEquals(credentials.email(), node.get("email").asText());
        assertEquals(credentials.handle(), node.get("handle").asText());
        assertTrue(node.get("displayName").asText().startsWith("Nome "));
        assertFalse(node.get("isAnonymousDefault").asBoolean());
        assertEquals(0, node.get("reputationScore").asInt());
        assertNotNull(node.get("createdAt").asText());
    }

    @Test
    @DisplayName("2. GET /api/v1/me sem autenticação deve retornar 401 Unauthorized")
    void shouldReturn401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("3. PATCH /api/v1/me/profile autenticado deve atualizar o próprio perfil com sucesso")
    void shouldUpdateProfileSuccessfully() throws Exception {
        TestUserCredentials credentials = registerNewUser("me_patch");

        UpdateProfileRequest updateReq = new UpdateProfileRequest(
                null,
                "Nome Atualizado",
                "Bio atualizada para teste",
                true
        );

        MvcResult result = mockMvc.perform(patch("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + credentials.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("Nome Atualizado", node.get("displayName").asText());
        assertEquals("Bio atualizada para teste", node.get("bio").asText());
        assertTrue(node.get("isAnonymousDefault").asBoolean());
        assertEquals(credentials.handle(), node.get("handle").asText()); // handle inalterado
    }

    @Test
    @DisplayName("4 e 5. O JWT determina o usuário alvo; não há como escolher outro usuário (Anti-IDOR)")
    void shouldPreventIdorAndDetermineTargetFromJwtOnly() throws Exception {
        TestUserCredentials userA = registerNewUser("user_a");
        TestUserCredentials userB = registerNewUser("user_b");

        // Usuário A envia payload malicioso contendo identificadores do Usuário B
        String maliciousPayload = """
                {
                    "userId": "%s",
                    "id": "%s",
                    "displayName": "Invasor de B"
                }
                """.formatted(userB.userId(), userB.userId());

        mockMvc.perform(patch("/api/v1/me/profile?userId=" + userB.userId())
                        .header("Authorization", "Bearer " + userA.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousPayload))
                .andExpect(status().isOk());

        // Confere que Usuário A teve seu display name alterado
        Profile profileA = profileRepository.findByUserId(userA.userId()).orElseThrow();
        assertEquals("Invasor de B", profileA.getDisplayName());

        // Confere que Usuário B permaneceu 100% intacto
        Profile profileB = profileRepository.findByUserId(userB.userId()).orElseThrow();
        assertNotEquals("Invasor de B", profileB.getDisplayName());
    }

    @Test
    @DisplayName("6. Atualização para handle disponível funciona perfeitamente")
    void shouldUpdateAvailableHandleSuccessfully() throws Exception {
        TestUserCredentials credentials = registerNewUser("handle_avail");
        String newHandle = "novo_hd_" + UUID.randomUUID().toString().substring(0, 6);

        UpdateProfileRequest updateReq = new UpdateProfileRequest(
                newHandle,
                null,
                null,
                null
        );

        MvcResult result = mockMvc.perform(patch("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + credentials.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(newHandle, node.get("handle").asText());
    }

    @Test
    @DisplayName("7. Handle é normalizado conforme a regra existente (trim, lowercase, remoção de @)")
    void shouldNormalizeHandleWhenUpdating() throws Exception {
        TestUserCredentials credentials = registerNewUser("norm_handle");
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String rawHandle = "   @Norm_Handle_" + suffix + "   ";
        String expectedNormalized = "norm_handle_" + suffix.toLowerCase();

        UpdateProfileRequest updateReq = new UpdateProfileRequest(
                rawHandle,
                null,
                null,
                null
        );

        MvcResult result = mockMvc.perform(patch("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + credentials.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(expectedNormalized, node.get("handle").asText());

        // Confere no banco real
        Profile inDb = profileRepository.findByUserId(credentials.userId()).orElseThrow();
        assertEquals(expectedNormalized, inDb.getHandle());
    }

    @Test
    @DisplayName("8. Conflito case-insensitive de handle é rejeitado com 409 CONFLICT")
    void shouldRejectCaseInsensitiveHandleConflict() throws Exception {
        TestUserCredentials userA = registerNewUser("first_owner");
        TestUserCredentials userB = registerNewUser("second_user");

        // Usuário B tenta usar o handle do Usuário A com maiúsculas e @
        String conflictingHandle = "@" + userA.handle().toUpperCase();

        UpdateProfileRequest updateReq = new UpdateProfileRequest(
                conflictingHandle,
                null,
                null,
                null
        );

        MvcResult result = mockMvc.perform(patch("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + userB.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isConflict())
                .andReturn();

        JsonNode errorNode = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("HANDLE_ALREADY_EXISTS", errorNode.get("code").asText());
        assertEquals(409, errorNode.get("status").asInt());
    }

    @Test
    @DisplayName("9 e 10. Campos protegidos não podem ser alterados pelo payload e dados persistem no PostgreSQL")
    void shouldIgnoreProtectedFieldsAndPersistValidDataInPostgreSql() throws Exception {
        TestUserCredentials credentials = registerNewUser("protected_test");

        User initialUser = userRepository.findById(credentials.userId()).orElseThrow();
        Profile initialProfile = profileRepository.findByUserId(credentials.userId()).orElseThrow();

        // Tentativa de alterar id, email, passwordHash, reputationScore, etc.
        Map<String, Object> maliciousBody = Map.of(
                "id", UUID.randomUUID().toString(),
                "email", "hacked.email@rewit.com",
                "passwordHash", "novoHashArgon2Injetado",
                "reputationScore", 99999,
                "createdAt", "2020-01-01T00:00:00Z",
                "displayName", "Nome Legítimo Atualizado",
                "bio", "Bio legítima"
        );

        MvcResult result = mockMvc.perform(patch("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + credentials.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(maliciousBody)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());

        // 10. Validar persistência real no PostgreSQL
        User persistedUser = userRepository.findById(credentials.userId()).orElseThrow();
        Profile persistedProfile = profileRepository.findByUserId(credentials.userId()).orElseThrow();

        assertEquals(credentials.email(), persistedUser.getEmail());
        assertEquals(initialUser.getPasswordHash(), persistedUser.getPasswordHash());
        assertEquals(0, persistedProfile.getReputationScore());
        assertEquals(initialProfile.getCreatedAt(), persistedProfile.getCreatedAt());

        // Validar que os campos permitidos foram atualizados
        assertEquals("Nome Legítimo Atualizado", persistedProfile.getDisplayName());
        assertEquals("Bio legítima", persistedProfile.getBio());
        assertEquals("Nome Legítimo Atualizado", responseNode.get("displayName").asText());
        assertEquals("Bio legítima", responseNode.get("bio").asText());
    }

    @Test
    @DisplayName("11, 12 e 13. Resposta não expõe senha, tokens de refresh ou dados internos de AuthSession")
    void shouldNotExposeSensitiveAuthenticationDataInResponse() throws Exception {
        TestUserCredentials credentials = registerNewUser("security_audit");

        MvcResult result = mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + credentials.accessToken()))
                .andExpect(status().isOk())
                .andReturn();

        String rawJson = result.getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(rawJson);

        // 11. Nenhum hash de senha
        assertNull(node.get("passwordHash"));
        assertNull(node.get("password"));
        assertFalse(rawJson.toLowerCase().contains("argon2id"));

        // 12. Nenhum token de refresh ou hash
        assertNull(node.get("refreshToken"));
        assertNull(node.get("tokenHash"));

        // 13. Nenhuma informação de sessão
        assertNull(node.get("sessionId"));
        assertNull(node.get("ipAddress"));
        assertNull(node.get("userAgent"));
        assertNull(node.get("replacedBySessionId"));
        assertNull(node.get("revokedAt"));
    }

    @Test
    @DisplayName("PATCH: Campo bio explicitamente null segue a semântica customizada de PATCH e preserva o valor atual")
    void shouldKeepExistingBioWhenBioIsExplicitlySentAsNull() throws Exception {
        TestUserCredentials credentials = registerNewUser("patch_null_bio");

        // 1. Define bio inicial
        UpdateProfileRequest setupReq = new UpdateProfileRequest(null, null, "Bio Inicial Cadastrada", null);
        mockMvc.perform(patch("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + credentials.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(setupReq)))
                .andExpect(status().isOk());

        // 2. Envia payload com bio explicitamente null
        String jsonWithExplicitNull = "{\"bio\": null}";
        MvcResult patchResult = mockMvc.perform(patch("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + credentials.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonWithExplicitNull))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = objectMapper.readTree(patchResult.getResponse().getContentAsString());
        assertEquals("Bio Inicial Cadastrada", node.get("bio").asText());

        // Comprova no PostgreSQL real
        Profile inDb = profileRepository.findByUserId(credentials.userId()).orElseThrow();
        assertEquals("Bio Inicial Cadastrada", inDb.getBio());
    }

    @Test
    @DisplayName("PATCH: Limpeza de bio funciona explicitamente ao enviar string vazia")
    void shouldClearBioWhenSentAsEmptyString() throws Exception {
        TestUserCredentials credentials = registerNewUser("patch_clear_bio");

        // 1. Define bio inicial
        UpdateProfileRequest setupReq = new UpdateProfileRequest(null, null, "Bio Para Limpeza", null);
        mockMvc.perform(patch("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + credentials.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(setupReq)))
                .andExpect(status().isOk());

        // 2. Envia string vazia para limpar
        UpdateProfileRequest clearReq = new UpdateProfileRequest(null, null, "", null);
        MvcResult patchResult = mockMvc.perform(patch("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + credentials.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(clearReq)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = objectMapper.readTree(patchResult.getResponse().getContentAsString());
        assertTrue(node.get("bio").isNull());

        // Comprova no PostgreSQL real
        Profile inDb = profileRepository.findByUserId(credentials.userId()).orElseThrow();
        assertNull(inDb.getBio());
    }

    @Test
    @DisplayName("PATCH: Atualização de múltiplos campos simultâneos preservando campos omitidos")
    void shouldUpdateMultipleFieldsSimultaneouslyAndKeepOmittedFieldsUnchanged() throws Exception {
        TestUserCredentials credentials = registerNewUser("patch_multi");

        // 1. Define bio inicial
        UpdateProfileRequest setupReq = new UpdateProfileRequest(null, null, "Bio Permanente", false);
        mockMvc.perform(patch("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + credentials.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(setupReq)))
                .andExpect(status().isOk());

        // 2. Atualiza handle e displayName, omitindo bio
        String newHandle = "multi_" + UUID.randomUUID().toString().substring(0, 6);
        String payloadOmitBio = """
                {
                    "handle": "%s",
                    "displayName": "Nome Multiplos Campos",
                    "isAnonymousDefault": true
                }
                """.formatted(newHandle);

        MvcResult patchResult = mockMvc.perform(patch("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + credentials.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadOmitBio))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = objectMapper.readTree(patchResult.getResponse().getContentAsString());
        assertEquals(newHandle, node.get("handle").asText());
        assertEquals("Nome Multiplos Campos", node.get("displayName").asText());
        assertEquals("Bio Permanente", node.get("bio").asText()); // bio omitida foi preservada
        assertTrue(node.get("isAnonymousDefault").asBoolean());

        // Comprova no PostgreSQL real
        Profile inDb = profileRepository.findByUserId(credentials.userId()).orElseThrow();
        assertEquals(newHandle, inDb.getHandle());
        assertEquals("Nome Multiplos Campos", inDb.getDisplayName());
        assertEquals("Bio Permanente", inDb.getBio());
        assertTrue(inDb.isAnonymousDefault());
    }
}
