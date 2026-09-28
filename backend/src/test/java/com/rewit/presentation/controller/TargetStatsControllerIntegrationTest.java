package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceRepository;
import com.rewit.domain.model.Place;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.CreateReviewRequest;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.CreateReviewTargetRequest;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração da API REST /api/v1/targets/{id}/stats (Step 13.0)")
class TargetStatsControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PlaceRepository placeRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private String registerAndGetToken(String username) throws Exception {
        RegisterRequest request = new RegisterRequest(
                username + "@rewit.com",
                "SenhaForte#123",
                "@" + username,
                "Usuário " + username
        );

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("accessToken").asText();
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
                null,
                "Restaurante " + suffix,
                "restaurante-" + suffix,
                "ALIMENTACAO",
                "Descrição do restaurante",
                "Rua Marechal Deodoro",
                "250",
                "Centro",
                "Curitiba",
                "PR",
                "BR",
                -25.4300,
                -49.2700,
                100,
                "USER",
                false,
                null,
                "ACTIVE"
        );
        return placeRepository.save(place);
    }

    @Test
    @DisplayName("Deve rejeitar consulta de estatísticas sem token JWT com 401 Unauthorized")
    void shouldRejectUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/targets/{id}/stats", UUID.randomUUID())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Deve retornar estatísticas agregadas atualizadas após criação de Review")
    void shouldReturnAggregatedStatsAfterReviewCreated() throws Exception {
        String token = registerAndGetToken("stats_user_" + UUID.randomUUID().toString().substring(0, 8));
        Place place = createPlace();

        // 1. Criar Review com nota 4.5 para o Place
        CreateReviewRequest reviewRequest = new CreateReviewRequest(
                place.getId(),
                "Excelente comida e espaço",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetRequest(place.getId(), new BigDecimal("4.5"), "Nota 4.5"))
        );

        mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reviewRequest)))
                .andExpect(status().isCreated());

        // 2. Consultar estatísticas via GET /api/v1/targets/{id}/stats
        mockMvc.perform(get("/api/v1/targets/{id}/stats", place.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetId").value(place.getId().toString()))
                .andExpect(jsonPath("$.averageRating").value(4.50))
                .andExpect(jsonPath("$.reviewsCount").value(1))
                .andExpect(jsonPath("$.lastCalculatedAt").isNotEmpty());
    }

    @Test
    @DisplayName("Deve retornar 200 OK com valores padrão (0.00 / 0) para alvo existente sem avaliações")
    void shouldReturnDefaultStatsForUnratedTarget() throws Exception {
        String token = registerAndGetToken("unrated_user_" + UUID.randomUUID().toString().substring(0, 8));
        Place place = createPlace();

        mockMvc.perform(get("/api/v1/targets/{id}/stats", place.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetId").value(place.getId().toString()))
                .andExpect(jsonPath("$.averageRating").value(0.00))
                .andExpect(jsonPath("$.reviewsCount").value(0))
                .andExpect(jsonPath("$.lastCalculatedAt").doesNotExist());
    }

    @Test
    @DisplayName("Deve retornar 404 Not Found RFC 7807 quando o alvo não existe em rateable_targets")
    void shouldReturn404WhenTargetDoesNotExist() throws Exception {
        String token = registerAndGetToken("notfound_user_" + UUID.randomUUID().toString().substring(0, 8));
        UUID missingId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/targets/{id}/stats", missingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RATEABLE_TARGET_NOT_FOUND"));
    }
}
