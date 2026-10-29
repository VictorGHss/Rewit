package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração MockMvc / HTTP: Preferências de Rankings (ADR-016)")
class RankingPreferencesControllerIntegrationTest {

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

    private record TestUser(String accessToken, UUID userId) {}

    private TestUser registerUser(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = (prefix + "." + suffix + "@rankingtest.com").toLowerCase();
        String handle = (prefix + suffix).toLowerCase();
        RegisterRequest req = new RegisterRequest(email, "SenhaSegura123!", handle, "User " + suffix);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(
                json.get("accessToken").asText(),
                UUID.fromString(json.get("user").get("id").asText())
        );
    }

    @Test
    @DisplayName("GET /api/v1/me/ranking-preferences sem autenticação retorna 401")
    void shouldRequireAuthenticationToGetPreferences() throws Exception {
        mockMvc.perform(get("/api/v1/me/ranking-preferences"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /api/v1/me/ranking-preferences sem autenticação retorna 401")
    void shouldRequireAuthenticationToUpdatePreferences() throws Exception {
        mockMvc.perform(put("/api/v1/me/ranking-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showInRankings\": true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Ciclo completo de consentimento opt-in: padrão FALSE, ativação TRUE e desativação FALSE")
    void shouldManageOptInLifecycle() throws Exception {
        TestUser user = registerUser("opt");

        // 1. Padrão inicial: showInRankings == false
        mockMvc.perform(get("/api/v1/me/ranking-preferences")
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showInRankings").value(false));

        // 2. Atualizar para opt-in ativo (showInRankings = true)
        mockMvc.perform(put("/api/v1/me/ranking-preferences")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showInRankings\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showInRankings").value(true));

        // 3. Confirmar persistência da ativação
        mockMvc.perform(get("/api/v1/me/ranking-preferences")
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showInRankings").value(true));

        // 4. Desativar participação (showInRankings = false)
        mockMvc.perform(put("/api/v1/me/ranking-preferences")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showInRankings\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showInRankings").value(false));

        // 5. Confirmar retorno ao estado desligado
        mockMvc.perform(get("/api/v1/me/ranking-preferences")
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showInRankings").value(false));
    }
}
