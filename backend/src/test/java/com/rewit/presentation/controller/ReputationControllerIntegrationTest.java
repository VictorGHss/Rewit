package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.service.ReviewService;
import com.rewit.domain.model.Place;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testes de integração MockMvc / HTTP: Reputação V1 (Step 23.0).
 * Valida: 401, 200, 404, resposta sem PII, sem score, sem reports, sem localização.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração MockMvc / HTTP: Reputação V1 (Step 23.0)")
class ReputationControllerIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private ReviewService reviewService;
    @Autowired private PlaceRepository placeRepository;

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
        String email  = (prefix + "." + suffix + "@rep.test").toLowerCase();
        String handle = (prefix + suffix).toLowerCase();
        RegisterRequest req = new RegisterRequest(email, "SenhaSegura123!", handle, "Rep " + suffix);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(json.get("accessToken").asText(),
                            UUID.fromString(json.get("user").get("id").asText()));
    }

    // =========================================================================
    // 1. Autenticação
    // =========================================================================

    @Test
    @DisplayName("1. GET /api/v1/users/{id}/reputation sem token retorna 401")
    void shouldRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/users/" + UUID.randomUUID() + "/reputation"))
            .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // 2. 200 OK com sinais corretos
    // =========================================================================

    @Test
    @DisplayName("2. Usuario sem reviews: sinais devem ser zero")
    void shouldReturnZeroSignalsForNewUser() throws Exception {
        TestUser user = registerUser("repz");

        mockMvc.perform(get("/api/v1/users/" + user.userId() + "/reputation")
                .header("Authorization", "Bearer " + user.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(user.userId().toString()))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.signals.activeReviews").value(0))
            .andExpect(jsonPath("$.signals.verifiedReviews").value(0))
            .andExpect(jsonPath("$.signals.helpfulVotesReceived").value(0))
            .andExpect(jsonPath("$.signals.distinctTargetsReviewed").value(0))
            .andExpect(jsonPath("$.calculatedAt").isNotEmpty());
    }

    // =========================================================================
    // 3. 404 para usuário inexistente
    // =========================================================================

    @Test
    @DisplayName("3. userId inexistente retorna 404")
    void shouldReturn404ForUnknownUser() throws Exception {
        TestUser requester = registerUser("repx");

        mockMvc.perform(get("/api/v1/users/" + UUID.randomUUID() + "/reputation")
                .header("Authorization", "Bearer " + requester.accessToken()))
            .andExpect(status().isNotFound());
    }

    // =========================================================================
    // 4. Resposta sem PII, sem score, sem localização, sem reports
    // =========================================================================

    @Test
    @DisplayName("4. Resposta nao deve conter campos proibidos (PII, score, location, reports)")
    void shouldNotExposeProhibitedFields() throws Exception {
        TestUser user = registerUser("repclean");

        MvcResult result = mockMvc.perform(get("/api/v1/users/" + user.userId() + "/reputation")
                .header("Authorization", "Bearer " + user.accessToken()))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);

        // Campos proibidos ausentes
        assertFalse(node.has("score"),            "score nao deve ser exposto");
        assertFalse(node.has("email"),            "email nao deve ser exposto");
        assertFalse(node.has("passwordHash"),     "passwordHash nao deve ser exposto");
        assertFalse(node.has("latitude"),         "latitude nao deve ser exposta");
        assertFalse(node.has("longitude"),        "longitude nao deve ser exposta");
        assertFalse(node.has("coordinates"),      "coordinates nao devem ser expostas");
        assertFalse(node.has("reportCount"),      "reportCount nao deve ser exposto");
        assertFalse(node.has("reports"),          "reports nao devem ser expostos");

        // Campos permitidos presentes
        assertTrue(node.has("userId"));
        assertTrue(node.has("version"));
        assertTrue(node.has("signals"));
        assertTrue(node.has("calculatedAt"));
        assertTrue(node.get("signals").has("activeReviews"));
        assertTrue(node.get("signals").has("verifiedReviews"));
        assertTrue(node.get("signals").has("helpfulVotesReceived"));
        assertTrue(node.get("signals").has("distinctTargetsReviewed"));
    }

    private static void assertFalse(boolean condition, String msg) {
        if (condition) throw new AssertionError(msg);
    }

    private static void assertTrue(boolean condition) {
        if (!condition) throw new AssertionError("Expected true");
    }

    // =========================================================================
    // 5. Reviews anônimas não aparecem nos sinais
    // =========================================================================

    @Test
    @DisplayName("5. Reviews anonimas nao devem aparecer nos sinais de reputacao")
    void shouldExcludeAnonymousReviewsFromSignals() throws Exception {
        TestUser user = registerUser("repanon");
        Place place = createPlace();

        // Cria review anônima (não deve contar nos sinais)
        reviewService.createReview(new CreateReviewCommand(
            user.userId(), place.getId(), "Experiencia anonima", true, "PUBLIC",
            List.of(new CreateReviewTargetCommand(place.getId(), BigDecimal.valueOf(4.0), "Anon"))
        ));

        MvcResult result = mockMvc.perform(get("/api/v1/users/" + user.userId() + "/reputation")
                .header("Authorization", "Bearer " + user.accessToken()))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode signals = objectMapper.readTree(result.getResponse().getContentAsString())
            .get("signals");

        // Review anônima não deve contar como activeReview no sinal de reputação
        int activeReviews = signals.get("activeReviews").asInt();
        assertEquals(0, activeReviews, "Review anonima nao deve contar nos sinais de reputacao publica");
    }

    private void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) throw new AssertionError(msg + " — expected: " + expected + " actual: " + actual);
    }

    // =========================================================================
    // 6. Reviews não anônimas APARECEM nos sinais
    // =========================================================================

    @Test
    @DisplayName("6. Reviews publicas devem aparecer nos sinais de reputacao")
    void shouldIncludePublicReviewsInSignals() throws Exception {
        TestUser user = registerUser("repnonanon");
        Place place = createPlace();

        // Review pública (deve contar)
        reviewService.createReview(new CreateReviewCommand(
            user.userId(), place.getId(), "Experiencia publica", false, "PUBLIC",
            List.of(new CreateReviewTargetCommand(place.getId(), BigDecimal.valueOf(5.0), "Pub"))
        ));

        mockMvc.perform(get("/api/v1/users/" + user.userId() + "/reputation")
                .header("Authorization", "Bearer " + user.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.signals.activeReviews").value(1))
            .andExpect(jsonPath("$.signals.distinctTargetsReviewed").value(1));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
            null, "Rep Place " + suffix, "rep-place-" + suffix,
            "RESTAURANTE", "Desc", "Rua Rep, 1", "1", "Bairro", "Cidade", "SP", "BR",
            -23.5505, -46.6333, 50, "USER", false, null, "ACTIVE"
        );
        return placeRepository.save(place);
    }
}
