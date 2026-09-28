package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração de API / MockMvc - Subsistema Social de Seguidores (Step 15.0)")
class UserFollowControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private record TestUser(String accessToken, UUID userId, String handle, String displayName) {}

    private TestUser registerUser(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = (prefix + "." + suffix + "@rewit.com").toLowerCase(java.util.Locale.ROOT);
        String handle = (prefix + "_" + suffix).toLowerCase(java.util.Locale.ROOT);

        RegisterRequest request = new RegisterRequest(email, "SenhaSegura123!", handle, "Nome " + prefix);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = json.get("accessToken").asText();
        UUID userId = UUID.fromString(json.get("user").get("id").asText());
        return new TestUser(token, userId, handle, "Nome " + prefix);
    }

    @Test
    @DisplayName("1. Retorna 401 Unauthorized sem token JWT")
    void shouldReturn401WhenNoJwtProvided() throws Exception {
        UUID targetId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/users/" + targetId + "/follow"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/v1/users/" + targetId + "/follow"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/users/" + targetId + "/follow"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/me/following"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/me/followers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("2. POST /api/v1/users/{id}/follow segue usuário e é idempotente")
    void shouldFollowUserAndBeIdempotent() throws Exception {
        TestUser userA = registerUser("userA");
        TestUser userB = registerUser("userB");

        // Follow inicial
        mockMvc.perform(post("/api/v1/users/" + userB.userId() + "/follow")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.following").value(true));

        // Verificação de status
        mockMvc.perform(get("/api/v1/users/" + userB.userId() + "/follow")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.following").value(true));

        // Follow repetido (idempotente)
        mockMvc.perform(post("/api/v1/users/" + userB.userId() + "/follow")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.following").value(true));
    }

    @Test
    @DisplayName("3. Auto-seguir retorna 400 Bad Request com SELF_FOLLOW_FORBIDDEN")
    void shouldReturn400WhenSelfFollowing() throws Exception {
        TestUser userA = registerUser("self");

        mockMvc.perform(post("/api/v1/users/" + userA.userId() + "/follow")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_FOLLOW_FORBIDDEN"));
    }

    @Test
    @DisplayName("4. Seguir usuário inexistente retorna 404 Not Found com USER_NOT_FOUND")
    void shouldReturn404WhenTargetUserDoesNotExist() throws Exception {
        TestUser userA = registerUser("seeker");
        UUID nonexistent = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/users/" + nonexistent + "/follow")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("5. DELETE /api/v1/users/{id}/follow deixa de seguir e é idempotente")
    void shouldUnfollowUserAndBeIdempotent() throws Exception {
        TestUser userA = registerUser("unfA");
        TestUser userB = registerUser("unfB");

        // Segue primeiro
        mockMvc.perform(post("/api/v1/users/" + userB.userId() + "/follow")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken()))
                .andExpect(status().isOk());

        // Deixa de seguir
        mockMvc.perform(delete("/api/v1/users/" + userB.userId() + "/follow")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.following").value(false));

        // Consulta estado
        mockMvc.perform(get("/api/v1/users/" + userB.userId() + "/follow")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.following").value(false));

        // Unfollow repetido (idempotente)
        mockMvc.perform(delete("/api/v1/users/" + userB.userId() + "/follow")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.following").value(false));
    }

    @Test
    @DisplayName("6. GET /api/v1/me/following e /api/v1/me/followers retornam envelope paginado PagedResponse")
    void shouldReturnPagedResponseForMeFollowingAndFollowers() throws Exception {
        TestUser userA = registerUser("meA");
        TestUser userB = registerUser("meB");

        // User A segue User B
        mockMvc.perform(post("/api/v1/users/" + userB.userId() + "/follow")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken()))
                .andExpect(status().isOk());

        // /me/following de User A deve conter User B
        mockMvc.perform(get("/api/v1/me/following")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(userB.userId().toString()))
                .andExpect(jsonPath("$.content[0].handle").value(userB.handle()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(jsonPath("$.isLast").value(true));

        // /me/followers de User B deve conter User A
        mockMvc.perform(get("/api/v1/me/followers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userB.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(userA.userId().toString()))
                .andExpect(jsonPath("$.content[0].handle").value(userA.handle()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("7. GET /api/v1/users/{id}/following e /api/v1/users/{id}/followers validam paginação")
    void shouldValidatePaginationOnUsersEndpoints() throws Exception {
        TestUser userA = registerUser("pagUser");

        // page negativa
        mockMvc.perform(get("/api/v1/users/" + userA.userId() + "/following?page=-1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));

        // size zero
        mockMvc.perform(get("/api/v1/users/" + userA.userId() + "/followers?size=0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SIZE"));

        // size superior a 50
        mockMvc.perform(get("/api/v1/users/" + userA.userId() + "/following?size=51")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAGE_SIZE_EXCEEDED"));
    }
}
