package com.rewit.presentation.controller;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.CreatePlaceRequest;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de integração do Search V1 de catálogo")
class CatalogSearchControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unused")
    @BeforeEach
    void beforeEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private String registerToken() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest request = new RegisterRequest(
                "search." + suffix + "@rewit.com",
                "Password@" + suffix,
                "@search_" + suffix,
                "Usuario Search " + suffix
        );

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }

    private String createPlace(String token, String name) throws Exception {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        CreatePlaceRequest request = new CreatePlaceRequest(
                name,
                "cafe-search-" + suffix,
                "CAFÉ",
                "Café para trabalho",
                "Rua das Flores, 123",
                "123",
                "Centro",
                "São Paulo",
                "SP",
                "BR",
                -23.5505,
                -46.6333,
                80
        );

        MvcResult result = mockMvc.perform(post("/api/v1/places")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asText();
    }

    @Test
    @DisplayName("1. GET /api/v1/search sem autenticação deve retornar 401 Unauthorized")
    void shouldReturn401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("q", "cafe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("2. GET /api/v1/search autenticado devolve resultados paginados do catálogo")
    void shouldReturnCatalogSearchResultsWhenAuthenticated() throws Exception {
        String token = registerToken();
        createPlace(token, "Café Aurora");
        createPlace(token, "Padaria Aurora");

        mockMvc.perform(get("/api/v1/search")
                        .header("Authorization", "Bearer " + token)
                        .param("q", "aurora")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.content[0].targetType").exists())
                .andExpect(jsonPath("$.content[0].name").exists());
    }
}
