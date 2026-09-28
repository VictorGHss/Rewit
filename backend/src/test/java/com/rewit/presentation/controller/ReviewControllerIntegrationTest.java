package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.Review;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.CreateReviewRequest;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.CreateReviewTargetRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração da API REST de Reviews /api/v1/reviews (Step 11.0)")
class ReviewControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    @Autowired
    private ReviewRepository reviewRepository;

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
        String email = prefix + "." + suffix + "@rewit.com";
        String password = "Password@" + suffix;
        String rawHandle = prefix + "_" + suffix;
        String displayName = "Nome " + suffix;

        RegisterRequest req = new RegisterRequest(email, password, "@" + rawHandle, displayName);
        MvcResult res = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        String token = node.get("accessToken").asText();
        UUID id = UUID.fromString(node.get("user").get("id").asText());
        return new TestUser(token, id, rawHandle, displayName);
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
                null,
                "Restaurante Bella " + suffix,
                "restaurante-bella-" + suffix,
                "RESTAURANTE",
                "Comida italiana tradicional",
                "Rua Marechal Deodoro, 500",
                "500",
                "Centro",
                "Curitiba",
                "PR",
                "BR",
                -25.4284,
                -49.2733,
                50,
                "USER",
                false,
                null,
                "ACTIVE"
        );
        return placeRepository.save(place);
    }

    private RateableTarget createRateableTarget(TargetType type) {
        RateableTarget target = new RateableTarget(UUID.randomUUID(), type);
        return rateableTargetRepository.save(target);
    }

    @Test
    @DisplayName("1. POST /api/v1/reviews autenticado com um target cria Review e retorna 201 com Location")
    void shouldCreateReviewWithSingleTargetSuccessfully() throws Exception {
        TestUser user = registerUser("rev_single");
        Place place = createPlace();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        CreateReviewRequest request = new CreateReviewRequest(
                place.getId(),
                "Ambiente incrível e atendimento impecável!",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("4.5"), "Lugar fantástico"))
        );

        MvcResult result = mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/v1/reviews/")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.contextPlaceId").value(place.getId().toString()))
                .andExpect(jsonPath("$.experienceText").value("Ambiente incrível e atendimento impecável!"))
                .andExpect(jsonPath("$.isAnonymous").value(false))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.author.id").value(user.userId().toString()))
                .andExpect(jsonPath("$.author.handle").value(user.handle()))
                .andExpect(jsonPath("$.author.displayName").value(user.displayName()))
                .andExpect(jsonPath("$.author.isAnonymous").value(false))
                .andExpect(jsonPath("$.targets", hasSize(1)))
                .andExpect(jsonPath("$.targets[0].targetId").value(target.getId().toString()))
                .andExpect(jsonPath("$.targets[0].rating").value(4.5))
                .andExpect(jsonPath("$.targets[0].specificComment").value("Lugar fantástico"))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID reviewId = UUID.fromString(json.get("id").asText());

        // Validação no PostgreSQL real
        Optional<Review> fromDb = reviewRepository.findById(reviewId);
        assertTrue(fromDb.isPresent());
        assertEquals(user.userId(), fromDb.get().getUserId());
        assertEquals(place.getId(), fromDb.get().getContextPlaceId());
        assertEquals("ACTIVE", fromDb.get().getStatus().name());
        assertEquals(1, fromDb.get().getTargets().size());
    }

    @Test
    @DisplayName("2. POST /api/v1/reviews autenticado com múltiplos targets cria Review com todos os alvos")
    void shouldCreateReviewWithMultipleTargetsSuccessfully() throws Exception {
        TestUser user = registerUser("rev_multi");
        RateableTarget targetPlace = createRateableTarget(TargetType.PLACE);
        RateableTarget targetProduct1 = createRateableTarget(TargetType.PRODUCT);
        RateableTarget targetProduct2 = createRateableTarget(TargetType.PRODUCT);

        CreateReviewRequest request = new CreateReviewRequest(
                null,
                "Jantar completo de sexta-feira",
                false,
                "PUBLIC",
                List.of(
                        new CreateReviewTargetRequest(targetPlace.getId(), new BigDecimal("5.0"), "Restaurante ótimo"),
                        new CreateReviewTargetRequest(targetProduct1.getId(), new BigDecimal("4.0"), "Pasta ao pesto muito boa"),
                        new CreateReviewTargetRequest(targetProduct2.getId(), new BigDecimal("4.5"), "Sobremesa excelente")
                )
        );

        mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targets", hasSize(3)))
                .andExpect(jsonPath("$.targets[0].targetId").value(targetPlace.getId().toString()))
                .andExpect(jsonPath("$.targets[1].targetId").value(targetProduct1.getId().toString()))
                .andExpect(jsonPath("$.targets[2].targetId").value(targetProduct2.getId().toString()));
    }

    @Test
    @DisplayName("3. POST /api/v1/reviews sem autenticação retorna 401 Unauthorized")
    void shouldRejectUnauthenticatedReviewCreation() throws Exception {
        RateableTarget target = createRateableTarget(TargetType.PLACE);
        CreateReviewRequest request = new CreateReviewRequest(
                null,
                "Texto sem auth",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("4.0"), null))
        );

        mockMvc.perform(post("/api/v1/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("4. POST /api/v1/reviews sem targets retorna 400 Bad Request")
    void shouldRejectReviewWithoutTargets() throws Exception {
        TestUser user = registerUser("rev_no_targets");

        CreateReviewRequest request = new CreateReviewRequest(
                null,
                "Tentando postar sem targets",
                false,
                "PUBLIC",
                List.of()
        );

        mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("5. POST /api/v1/reviews com target duplicado retorna 400 Bad Request")
    void shouldRejectReviewWithDuplicateTarget() throws Exception {
        TestUser user = registerUser("rev_dup");
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        CreateReviewRequest request = new CreateReviewRequest(
                null,
                "Tentando avaliar mesmo alvo duas vezes",
                false,
                "PUBLIC",
                List.of(
                        new CreateReviewTargetRequest(target.getId(), new BigDecimal("4.0"), "Primeira"),
                        new CreateReviewTargetRequest(target.getId(), new BigDecimal("5.0"), "Segunda")
                )
        );

        mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is(HttpStatus.UNPROCESSABLE_CONTENT.value()))
                .andExpect(jsonPath("$.code").value("DUPLICATE_REVIEW_TARGET"));
    }

    @Test
    @DisplayName("6. POST /api/v1/reviews com rating inválido (< 1.0, > 5.0 ou > 1 decimal) rejeita a requisição")
    void shouldRejectReviewWithInvalidRating() throws Exception {
        TestUser user = registerUser("rev_invalid_rating");
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        // Abaixo do mínimo (0.5) -> Bean Validation 400 Bad Request
        CreateReviewRequest requestLow = new CreateReviewRequest(
                null,
                "Rating muito baixo",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("0.5"), null))
        );
        mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestLow)))
                .andExpect(status().isBadRequest());

        // Acima do máximo (5.5) -> Bean Validation 400 Bad Request
        CreateReviewRequest requestHigh = new CreateReviewRequest(
                null,
                "Rating muito alto",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("5.5"), null))
        );
        mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestHigh)))
                .andExpect(status().isBadRequest());

        // Mais de uma casa decimal (4.55) -> Domínio 422 Unprocessable Entity
        CreateReviewRequest requestScale = new CreateReviewRequest(
                null,
                "Rating com duas decimais",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("4.55"), null))
        );
        mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestScale)))
                .andExpect(status().is(HttpStatus.UNPROCESSABLE_CONTENT.value()))
                .andExpect(jsonPath("$.code").value("INVALID_RATING_PRECISION"));
    }

    @Test
    @DisplayName("7. POST /api/v1/reviews com contextPlaceId inexistente retorna 404 Not Found")
    void shouldRejectReviewWithNonExistentContextPlace() throws Exception {
        TestUser user = registerUser("rev_no_place");
        RateableTarget target = createRateableTarget(TargetType.PLACE);
        UUID nonExistentPlaceId = UUID.randomUUID();

        CreateReviewRequest request = new CreateReviewRequest(
                nonExistentPlaceId,
                "Place que não existe",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("4.0"), null))
        );

        mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));
    }

    @Test
    @DisplayName("8. POST /api/v1/reviews anônimo cria Review e oculta dados de autor na resposta")
    void shouldCreateAnonymousReviewAndHideAuthorInfo() throws Exception {
        TestUser user = registerUser("rev_anon");
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        CreateReviewRequest request = new CreateReviewRequest(
                null,
                "Avaliação sincera em modo anônimo",
                true,
                "PUBLIC",
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("3.0"), "Razoável"))
        );

        MvcResult result = mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isAnonymous").value(true))
                .andExpect(jsonPath("$.author.isAnonymous").value(true))
                .andExpect(jsonPath("$.author.id").isEmpty())
                .andExpect(jsonPath("$.author.handle").isEmpty())
                .andExpect(jsonPath("$.author.displayName").value("Anônimo"))
                .andExpect(jsonPath("$.author.avatarUrl").isEmpty())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID reviewId = UUID.fromString(json.get("id").asText());

        // Confirma que internamente o banco de dados armazena o userId real
        Review reviewInDb = reviewRepository.findById(reviewId).orElseThrow();
        assertEquals(user.userId(), reviewInDb.getUserId());
        assertTrue(reviewInDb.isAnonymous());
    }

    @Test
    @DisplayName("9. POST /api/v1/reviews aceita visibilidades válidas (PUBLIC, PRIVATE, FOLLOWERS)")
    void shouldAcceptValidVisibilities() throws Exception {
        TestUser user = registerUser("rev_visibility");
        RateableTarget target1 = createRateableTarget(TargetType.PLACE);
        RateableTarget target2 = createRateableTarget(TargetType.PLACE);
        RateableTarget target3 = createRateableTarget(TargetType.PLACE);

        for (String vis : List.of("PUBLIC", "PRIVATE", "FOLLOWERS")) {
            RateableTarget t = vis.equals("PUBLIC") ? target1 : (vis.equals("PRIVATE") ? target2 : target3);
            CreateReviewRequest request = new CreateReviewRequest(
                    null,
                    "Visibilidade " + vis,
                    false,
                    vis,
                    List.of(new CreateReviewTargetRequest(t.getId(), new BigDecimal("4.0"), null))
            );

            mockMvc.perform(post("/api/v1/reviews")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.visibility").value(vis));
        }
    }

    @Test
    @DisplayName("10. GET /api/v1/reviews/{id} existente retorna representação completa da Review")
    void shouldRetrieveExistingReviewSuccessfully() throws Exception {
        TestUser author = registerUser("rev_get_author");
        TestUser reader = registerUser("rev_get_reader");
        RateableTarget target = createRateableTarget(TargetType.PRODUCT);

        CreateReviewRequest request = new CreateReviewRequest(
                null,
                "Texto de experiência pública",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("5.0"), "Perfeito"))
        );

        MvcResult postRes = mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode postJson = objectMapper.readTree(postRes.getResponse().getContentAsString());
        UUID reviewId = UUID.fromString(postJson.get("id").asText());

        mockMvc.perform(get("/api/v1/reviews/{id}", reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reader.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reviewId.toString()))
                .andExpect(jsonPath("$.experienceText").value("Texto de experiência pública"))
                .andExpect(jsonPath("$.isAnonymous").value(false))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.author.id").value(author.userId().toString()))
                .andExpect(jsonPath("$.author.handle").value(author.handle()))
                .andExpect(jsonPath("$.author.displayName").value(author.displayName()))
                .andExpect(jsonPath("$.targets", hasSize(1)))
                .andExpect(jsonPath("$.targets[0].targetId").value(target.getId().toString()))
                .andExpect(jsonPath("$.targets[0].rating").value(5.0))
                .andExpect(jsonPath("$.targets[0].specificComment").value("Perfeito"));
    }

    @Test
    @DisplayName("11. GET /api/v1/reviews/{id} inexistente retorna 404 Not Found")
    void shouldReturn404WhenReviewNotFound() throws Exception {
        TestUser user = registerUser("rev_not_found");
        UUID randomId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/reviews/{id}", randomId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    @Test
    @DisplayName("12. GET /api/v1/reviews/{id} respeita anonimização ocultando identidade do autor")
    void shouldRespectAnonymizationOnGet() throws Exception {
        TestUser author = registerUser("rev_anon_author");
        TestUser reader = registerUser("rev_anon_reader");
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        CreateReviewRequest request = new CreateReviewRequest(
                null,
                "Avaliação anônima consultada por terceiro",
                true,
                "PUBLIC",
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("3.5"), null))
        );

        MvcResult postRes = mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID reviewId = UUID.fromString(objectMapper.readTree(postRes.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/api/v1/reviews/{id}", reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reader.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAnonymous").value(true))
                .andExpect(jsonPath("$.author.isAnonymous").value(true))
                .andExpect(jsonPath("$.author.id").isEmpty())
                .andExpect(jsonPath("$.author.handle").isEmpty())
                .andExpect(jsonPath("$.author.displayName").value("Anônimo"))
                .andExpect(jsonPath("$.author.avatarUrl").isEmpty());
    }

    @Test
    @DisplayName("13. GET /api/v1/reviews/{id} com PRIVATE ou FOLLOWERS restringe acesso a terceiros (403) e permite ao autor (200)")
    void shouldEnforceVisibilityRulesOnGet() throws Exception {
        TestUser author = registerUser("rev_vis_author");
        TestUser reader = registerUser("rev_vis_reader");

        RateableTarget targetPrivate = createRateableTarget(TargetType.PLACE);
        CreateReviewRequest privateReq = new CreateReviewRequest(
                null,
                "Minha avaliação estritamente privada",
                false,
                "PRIVATE",
                List.of(new CreateReviewTargetRequest(targetPrivate.getId(), new BigDecimal("4.0"), null))
        );

        MvcResult privRes = mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(privateReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID privateReviewId = UUID.fromString(objectMapper.readTree(privRes.getResponse().getContentAsString()).get("id").asText());

        // Terceiro consultando review PRIVATE deve receber 403 Forbidden
        mockMvc.perform(get("/api/v1/reviews/{id}", privateReviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reader.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // Autor consultando sua própria review PRIVATE deve receber 200 OK
        mockMvc.perform(get("/api/v1/reviews/{id}", privateReviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(privateReviewId.toString()))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"));

        // Caso FOLLOWERS:
        RateableTarget targetFollowers = createRateableTarget(TargetType.PLACE);
        CreateReviewRequest followersReq = new CreateReviewRequest(
                null,
                "Avaliação apenas para seguidores",
                false,
                "FOLLOWERS",
                List.of(new CreateReviewTargetRequest(targetFollowers.getId(), new BigDecimal("5.0"), null))
        );

        MvcResult follRes = mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(followersReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID followersReviewId = UUID.fromString(objectMapper.readTree(follRes.getResponse().getContentAsString()).get("id").asText());

        // Terceiro consultando review FOLLOWERS recebe 403 Forbidden
        mockMvc.perform(get("/api/v1/reviews/{id}", followersReviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reader.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // Autor consultando sua review FOLLOWERS recebe 200 OK
        mockMvc.perform(get("/api/v1/reviews/{id}", followersReviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(followersReviewId.toString()))
                .andExpect(jsonPath("$.visibility").value("FOLLOWERS"));
    }
}
