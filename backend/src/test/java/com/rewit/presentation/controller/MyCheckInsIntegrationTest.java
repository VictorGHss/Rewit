package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.RateableTarget;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/me/check-ins} com PostgreSQL/PostGIS real: só check-ins verificados do próprio usuário, de
 * avaliações ativas e não anônimas, paginados e sem nenhum dado da medição de presença.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Integração: histórico de check-ins do usuário autenticado")
class MyCheckInsIntegrationTest {

    private static final double PLACE_LAT = -25.4385;
    private static final double PLACE_LON = -49.2850;
    private static final double NEAR_LAT = -25.4384;
    private static final double NEAR_LON = -49.2849;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        RegisterRequest req = new RegisterRequest(prefix + "." + suffix + "@rewit.com", "Password@" + suffix,
                prefix + "_" + suffix, "Nome " + suffix);
        MvcResult res = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        return new TestUser(node.get("accessToken").asText(), UUID.fromString(node.get("user").get("id").asText()));
    }

    private Place createPlace(String name) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return placeRepository.save(new Place(null, name + " " + suffix, "historico-" + suffix, "CAFETERIA", null,
                "Rua XV de Novembro, 100", "100", "Centro", "Curitiba", "PR", "BR",
                PLACE_LAT, PLACE_LON, 60, "USER", false, null, "ACTIVE"));
    }

    private UUID checkIn(TestUser user, Place place, boolean anonymous, double latitude) throws Exception {
        RateableTarget target = rateableTargetRepository.save(new RateableTarget(UUID.randomUUID(), TargetType.PLACE));
        CreateReviewRequest request = new CreateReviewRequest(place.getId(), null, anonymous, "PUBLIC", latitude,
                NEAR_LON, 10.0, List.of(new CreateReviewTargetRequest(target.getId(), BigDecimal.valueOf(4.0), null)));
        MvcResult result = mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private JsonNode myCheckIns(TestUser user, String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me/check-ins" + query)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> reviewIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("content").forEach(item -> ids.add(item.get("reviewId").asText()));
        return ids;
    }

    @Test
    @DisplayName("Lista só os check-ins verificados do próprio usuário, do mais recente ao mais antigo, sem dados da medição")
    void listsOnlyOwnVerifiedCheckIns() throws Exception {
        TestUser ana = registerUser("hist_ana");
        TestUser bruno = registerUser("hist_bruno");
        Place cafe = createPlace("Café da Ana");

        UUID first = checkIn(ana, cafe, false, NEAR_LAT);
        jdbcTemplate.update("UPDATE check_ins SET verified_at = now() - interval '2 days' WHERE review_id = ?", first);
        UUID second = checkIn(ana, createPlace("Livraria"), false, NEAR_LAT);
        checkIn(ana, createPlace("Longe"), false, -25.4284); // fora do raio: REJECTED, não entra
        checkIn(bruno, cafe, false, NEAR_LAT);

        JsonNode page = myCheckIns(ana, "");
        assertEquals(List.of(second.toString(), first.toString()), reviewIds(page));
        assertEquals(2, page.get("totalElements").asLong());

        JsonNode item = page.get("content").get(1);
        assertEquals(cafe.getId().toString(), item.get("placeId").asText());
        assertEquals(cafe.getName(), item.get("placeName").asText());
        assertEquals("CAFETERIA", item.get("placeCategory").asText());
        assertEquals("Curitiba", item.get("placeCity").asText());
        assertEquals("PR", item.get("placeState").asText());
        assertFalse(item.get("verifiedAt").asText().isBlank());

        Set<String> fields = new HashSet<>();
        for (Iterator<String> names = item.fieldNames(); names.hasNext(); ) {
            fields.add(names.next());
        }
        assertEquals(Set.of("id", "reviewId", "placeId", "placeName", "placeCategory", "placeCity", "placeState",
                "verifiedAt"), fields, "nenhuma coordenada, distância ou precisão no contrato");

        assertEquals(1, myCheckIns(bruno, "").get("totalElements").asLong(), "cada conta vê só os seus");
        // userId na consulta é ignorado: a conta vem do token
        assertEquals(2, myCheckIns(ana, "?userId=" + bruno.userId()).get("totalElements").asLong());
    }

    @Test
    @DisplayName("Avaliação anônima, removida ou em moderação tira o check-in do histórico e do contador do perfil")
    void ineligibleReviewsAreExcluded() throws Exception {
        TestUser user = registerUser("hist_excl");
        UUID kept = checkIn(user, createPlace("Mantido"), false, NEAR_LAT);
        checkIn(user, createPlace("Anônimo"), true, NEAR_LAT);
        UUID removed = checkIn(user, createPlace("Removido"), false, NEAR_LAT);
        UUID underReview = checkIn(user, createPlace("Moderação"), false, NEAR_LAT);

        mockMvc.perform(delete("/api/v1/reviews/" + removed)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().is2xxSuccessful());
        jdbcTemplate.update("UPDATE reviews SET status = 'UNDER_REVIEW' WHERE id = ?", underReview);

        JsonNode page = myCheckIns(user, "");
        assertEquals(List.of(kept.toString()), reviewIds(page));
        mockMvc.perform(get("/api/v1/users/" + user.userId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.verifiedReviewsCount").value(1));
    }

    @Test
    @DisplayName("Paginação como em /me/reviews: page e size, totais coerentes e tamanho máximo 50")
    void paginatesLikeOtherMeEndpoints() throws Exception {
        TestUser user = registerUser("hist_page");
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            created.add(checkIn(user, createPlace("Página " + i), false, NEAR_LAT));
        }

        JsonNode first = myCheckIns(user, "?page=0&size=2");
        assertEquals(2, first.get("content").size());
        assertEquals(0, first.get("pageNumber").asInt());
        assertEquals(2, first.get("pageSize").asInt());
        assertEquals(3, first.get("totalElements").asLong());
        assertEquals(2, first.get("totalPages").asInt());
        assertFalse(first.get("isLast").asBoolean());

        JsonNode second = myCheckIns(user, "?page=1&size=2");
        assertEquals(1, second.get("content").size());
        assertTrue(second.get("isLast").asBoolean());

        Set<String> all = new HashSet<>(reviewIds(first));
        all.addAll(reviewIds(second));
        assertEquals(created.stream().map(UUID::toString).collect(java.util.stream.Collectors.toSet()), all);

        JsonNode empty = myCheckIns(registerUser("hist_empty"), "");
        assertEquals(0, empty.get("content").size());
        assertEquals(0, empty.get("totalElements").asLong());

        for (String invalid : List.of("?size=51", "?size=0", "?page=-1")) {
            mockMvc.perform(get("/api/v1/me/check-ins" + invalid)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("Sem token ou com token inválido responde 401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/me/check-ins")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/check-ins").header(HttpHeaders.AUTHORIZATION, "Bearer invalido"))
                .andExpect(status().isUnauthorized());
    }
}
