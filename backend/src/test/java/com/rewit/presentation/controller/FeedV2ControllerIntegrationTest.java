package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.*;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.*;
import com.rewit.presentation.dto.auth.RegisterRequest;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testes de integração de API / MockMvc para o Feed V2 (/api/v2/feed) com PostgreSQL Real (Step 24.4.3).
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração HTTP: FeedV2Controller (/api/v2/feed) com PostgreSQL Real (Step 24.4.3)")
class FeedV2ControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ReviewTargetRepository reviewTargetRepository;

    @Autowired
    private ReviewReactionRepository reviewReactionRepository;

    @Autowired
    private UserFollowRepository userFollowRepository;

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

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
                null,
                "Restaurante V2 Feed " + suffix,
                "restaurante-v2-feed-" + suffix,
                "RESTAURANTE",
                "Descrição",
                "Rua V2 Feed, 100",
                "100",
                "Bairro",
                "Cidade",
                "UF",
                "BR",
                -23.5505,
                -46.6333,
                50,
                "USER",
                false,
                null,
                "ACTIVE"
        );
        return placeRepository.save(place);
    }

    private RateableTarget createTarget(TargetType type) {
        RateableTarget target = new RateableTarget(UUID.randomUUID(), type != null ? type : TargetType.PLACE);
        return rateableTargetRepository.save(target);
    }

    private Review createReview(UUID authorId, Place place, RateableTarget target,
                                 String visibility, ReviewStatus status, boolean isAnonymous, Instant createdAt) {
        return createMultiTargetReview(authorId, place, List.of(target), visibility, status, isAnonymous, createdAt);
    }

    private Review createMultiTargetReview(UUID authorId, Place place, List<RateableTarget> targets,
                                            String visibility, ReviewStatus status, boolean isAnonymous, Instant createdAt) {
        UUID revId = UUID.randomUUID();
        Review review = new Review(
                revId,
                authorId,
                place.getId(),
                "Avaliação para testes de API Feed V2.",
                isAnonymous,
                false,
                status,
                visibility,
                -23.5505,
                -46.6333,
                10.0,
                createdAt != null ? createdAt : Instant.now(),
                Instant.now()
        );

        for (int i = 0; i < targets.size(); i++) {
            RateableTarget target = targets.get(i);
            ReviewTarget rt = new ReviewTarget(
                    null,
                    revId,
                    target.getId(),
                    BigDecimal.valueOf(4.0 + (i * 0.5)),
                    "Comentário do alvo " + (i + 1),
                    (createdAt != null ? createdAt : Instant.now()).plusSeconds(i * 2)
            );
            review.addTarget(rt);
        }

        Review saved = reviewRepository.save(review);
        for (ReviewTarget rt : review.getTargets()) {
            reviewTargetRepository.save(rt);
        }
        return saved;
    }

    // =========================================================================
    // 1. Autenticação e Segurança
    // =========================================================================

    @Test
    @DisplayName("1.1. Retorna 401 Unauthorized sem cabeçalho Authorization")
    void shouldReturn401WhenNoAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/api/v2/feed"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("1.2. Retorna 401 Unauthorized com token JWT inválido")
    void shouldReturn401WhenTokenIsInvalid() throws Exception {
        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token_invalido_xyz"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("1.3. Retorna 200 OK com token JWT válido quando usuário não segue ninguém (Cold Start preservando contrato)")
    void shouldReturn200OkWithValidJwtAndApplyColdStartWhenNoFollows() throws Exception {
        TestUser requester = registerUser("v2NoFollow");

        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.windowSize", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.totalPages", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.items", not(empty())));
    }

    @Test
    @DisplayName("1.4. Segurança contra IDOR: Parâmetro userId em query string é ignorado")
    void shouldIgnoreUserIdQueryParameterAndUseAuthenticatedIdentity() throws Exception {
        TestUser requester = registerUser("v2IdorReq");
        TestUser targetVictim = registerUser("v2IdorVictim");
        TestUser author = registerUser("v2IdorAuthor");

        Place place = createPlace();
        RateableTarget target = createTarget(TargetType.PLACE);

        // Somente targetVictim segue o author
        userFollowRepository.follow(targetVictim.userId(), author.userId());
        Review followersReview = createReview(author.userId(), place, target, "FOLLOWERS", ReviewStatus.ACTIVE, false, Instant.now().plusSeconds(300));

        // Requester tenta passar ?userId=<victimId>
        mockMvc.perform(get("/api/v2/feed")
                        .param("userId", targetVictim.userId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", not(hasItem(followersReview.getId().toString()))));
    }

    // =========================================================================
    // 2. Paginação e Validação de Parâmetros
    // =========================================================================

    @Test
    @DisplayName("2.1. Paginação: page=0 e size=1 fatia corretamente o primeiro item")
    void shouldPaginatePageZeroAndSizeOne() throws Exception {
        TestUser requester = registerUser("v2PageReq");
        TestUser author = registerUser("v2PageAuthor");

        Place place = createPlace();
        RateableTarget target = createTarget(TargetType.PLACE);
        userFollowRepository.follow(requester.userId(), author.userId());

        Instant now = Instant.now();
        Review r1 = createReview(author.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(10));
        Review r2 = createReview(author.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(20));

        // Page 0, Size 1
        mockMvc.perform(get("/api/v2/feed")
                        .param("page", "0")
                        .param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(r1.getId().toString()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.windowSize").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        // Page 1, Size 1
        mockMvc.perform(get("/api/v2/feed")
                        .param("page", "1")
                        .param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(r2.getId().toString()))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.windowSize").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    @DisplayName("2.2. Paginação: aceita size máximo 50")
    void shouldAcceptMaxSize50() throws Exception {
        TestUser requester = registerUser("v2Max50");

        mockMvc.perform(get("/api/v2/feed")
                        .param("size", "50")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50));
    }

    @Test
    @DisplayName("2.3. Validação: page negativo retorna 400 Bad Request com INVALID_PAGE")
    void shouldReturn400WhenPageIsNegative() throws Exception {
        TestUser requester = registerUser("v2NegPage");

        mockMvc.perform(get("/api/v2/feed")
                        .param("page", "-1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
    }

    @Test
    @DisplayName("2.4. Validação: size=0 retorna 400 Bad Request com INVALID_SIZE")
    void shouldReturn400WhenSizeIsZero() throws Exception {
        TestUser requester = registerUser("v2ZeroSize");

        mockMvc.perform(get("/api/v2/feed")
                        .param("size", "0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SIZE"));
    }

    @Test
    @DisplayName("2.5. Validação: size > 50 retorna 400 Bad Request com PAGE_SIZE_EXCEEDED")
    void shouldReturn400WhenSizeExceeds50() throws Exception {
        TestUser requester = registerUser("v2ExceedSize");

        mockMvc.perform(get("/api/v2/feed")
                        .param("size", "51")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAGE_SIZE_EXCEEDED"));
    }

    @Test
    @DisplayName("2.6. Paginação: página além da janela de candidatos retorna lista vazia sem quebrar")
    void shouldReturnEmptyListWhenPageIsBeyondWindow() throws Exception {
        TestUser requester = registerUser("v2BeyondReq");
        TestUser author = registerUser("v2BeyondAuthor");

        Place place = createPlace();
        RateableTarget target = createTarget(TargetType.PLACE);
        userFollowRepository.follow(requester.userId(), author.userId());

        createReview(author.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now());

        mockMvc.perform(get("/api/v2/feed")
                        .param("page", "10")
                        .param("size", "10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.page").value(10))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.windowSize").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    // =========================================================================
    // 3. Privacidade, Anonimato e Visibilidade
    // =========================================================================

    @Test
    @DisplayName("3.1. Anonimato: Review anônima oculta estritamente autor e perfil")
    void shouldPreserveStrictPrivacyForAnonymousReviews() throws Exception {
        TestUser requester = registerUser("v2AnonReq");
        TestUser author = registerUser("v2AnonAuthor");

        Place place = createPlace();
        RateableTarget target = createTarget(TargetType.PLACE);
        userFollowRepository.follow(requester.userId(), author.userId());

        Review anonReview = createReview(author.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE, true, Instant.now());

        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(anonReview.getId().toString()))
                .andExpect(jsonPath("$.items[0].isAnonymous").value(true))
                .andExpect(jsonPath("$.items[0].author.id").doesNotExist())
                .andExpect(jsonPath("$.items[0].author.handle").doesNotExist())
                .andExpect(jsonPath("$.items[0].author.displayName").value("Anônimo"))
                .andExpect(jsonPath("$.items[0].author.avatarUrl").doesNotExist())
                .andExpect(jsonPath("$.items[0].author.isAnonymous").value(true));
    }

    @Test
    @DisplayName("3.2. Visibilidade: Exclui PRIVATE, UNDER_REVIEW e REMOVED do Feed V2")
    void shouldExcludePrivateUnderReviewAndRemovedFromFeedV2() throws Exception {
        TestUser requester = registerUser("v2VisReq");
        TestUser author = registerUser("v2VisAuthor");

        Place place = createPlace();
        RateableTarget target = createTarget(TargetType.PLACE);
        userFollowRepository.follow(requester.userId(), author.userId());

        Instant now = Instant.now();
        Review rPublic = createReview(author.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(10));
        Review rPrivate = createReview(author.userId(), place, target, "PRIVATE", ReviewStatus.ACTIVE, false, now.minusSeconds(20));
        Review rUnderReview = createReview(author.userId(), place, target, "PUBLIC", ReviewStatus.UNDER_REVIEW, false, now.minusSeconds(30));
        Review rRemoved = createReview(author.userId(), place, target, "PUBLIC", ReviewStatus.REMOVED, false, now.minusSeconds(40));

        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(rPublic.getId().toString()))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(rPrivate.getId().toString()))))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(rUnderReview.getId().toString()))))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(rRemoved.getId().toString()))));
    }

    @Test
    @DisplayName("3.3. Visibilidade: Visibilidade FOLLOWERS é visível para quem segue o autor")
    void shouldAllowFollowersVisibilityWhenFollower() throws Exception {
        TestUser requester = registerUser("v2FolReq");
        TestUser author = registerUser("v2FolAuthor");

        Place place = createPlace();
        RateableTarget target = createTarget(TargetType.PLACE);
        userFollowRepository.follow(requester.userId(), author.userId());

        Review rFollowers = createReview(author.userId(), place, target, "FOLLOWERS", ReviewStatus.ACTIVE, false, Instant.now());

        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(rFollowers.getId().toString()))
                .andExpect(jsonPath("$.items[0].visibility").value("FOLLOWERS"));
    }

    // =========================================================================
    // 4. Hydration Completa: Author, Multi-Target, Helpful e Sinais Internos
    // =========================================================================

    @Test
    @DisplayName("4.1. Hydration: Autor público, alvos multi-target e contagens de helpful")
    void shouldHydrateCompletePublicProjectionWithMultiTargetsAndHelpful() throws Exception {
        TestUser requester = registerUser("v2HydReq");
        TestUser author = registerUser("v2HydAuthor");

        Place place = createPlace();
        RateableTarget targetPlace = createTarget(TargetType.PLACE);
        RateableTarget targetDish = createTarget(TargetType.PRODUCT);
        userFollowRepository.follow(requester.userId(), author.userId());

        Instant now = Instant.now();
        Review review = createMultiTargetReview(author.userId(), place, List.of(targetPlace, targetDish), "PUBLIC", ReviewStatus.ACTIVE, false, now);

        // Requester curte como helpful
        reviewReactionRepository.addHelpful(review.getId(), requester.userId());

        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(review.getId().toString()))
                .andExpect(jsonPath("$.items[0].contextPlaceId").value(place.getId().toString()))
                .andExpect(jsonPath("$.items[0].isAnonymous").value(false))
                .andExpect(jsonPath("$.items[0].isVerifiedOnSite").value(false))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                // Autor
                .andExpect(jsonPath("$.items[0].author.id").value(author.userId().toString()))
                .andExpect(jsonPath("$.items[0].author.handle").value(author.handle()))
                .andExpect(jsonPath("$.items[0].author.displayName").value(author.displayName()))
                .andExpect(jsonPath("$.items[0].author.isAnonymous").value(false))
                // Targets
                .andExpect(jsonPath("$.items[0].targets", hasSize(2)))
                .andExpect(jsonPath("$.items[0].targets[0].targetId").value(targetPlace.getId().toString()))
                .andExpect(jsonPath("$.items[0].targets[0].rating").value(4.0))
                .andExpect(jsonPath("$.items[0].targets[1].targetId").value(targetDish.getId().toString()))
                .andExpect(jsonPath("$.items[0].targets[1].rating").value(4.5))
                // Helpful
                .andExpect(jsonPath("$.items[0].helpfulCount").value(1))
                .andExpect(jsonPath("$.items[0].isHelpfulByMe").value(true))
                // Sinais internos não devem existir
                .andExpect(jsonPath("$.items[0].score").doesNotExist())
                .andExpect(jsonPath("$.items[0].isDirectFollow").doesNotExist());
    }

    // =========================================================================
    // 5. Não-regressão do Feed V1
    // =========================================================================

    @Test
    @DisplayName("5.1. Não-regressão: /api/v1/feed permanece ativo, cronológico e funcional")
    void shouldEnsureFeedV1IsPreservedAndUnaffected() throws Exception {
        TestUser requester = registerUser("v1CheckReq");
        TestUser author = registerUser("v1CheckAuthor");

        Place place = createPlace();
        RateableTarget target = createTarget(TargetType.PLACE);
        userFollowRepository.follow(requester.userId(), author.userId());

        Review review = createReview(author.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now());

        // V1 deve retornar com envelope PagedResponse (content, pageNumber, pageSize, totalElements)
        mockMvc.perform(get("/api/v1/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(review.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.pageNumber").value(0));

        // V2 deve retornar com envelope FeedV2PageResponse (items, page, size, windowSize, totalPages)
        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(review.getId().toString()))
                .andExpect(jsonPath("$.windowSize").value(1))
                .andExpect(jsonPath("$.page").value(0));
    }

    // =========================================================================
    // 6. Cold Start / Descoberta para Usuários Sem Seguidos (Step 24.5.1)
    // =========================================================================

    private Instant resolveFutureDiscoveryReviewTimestamp() {
        Timestamp maxTimestamp = jdbcTemplate.queryForObject(
                "SELECT MAX(created_at) FROM reviews WHERE status = 'ACTIVE' AND visibility = 'PUBLIC'",
                Timestamp.class
        );
        Instant now = Instant.now();
        if (maxTimestamp != null) {
            Instant maxInstant = maxTimestamp.toInstant();
            if (maxInstant.isAfter(now)) {
                return maxInstant.plusSeconds(60);
            }
        }
        return now.plus(java.time.Duration.ofDays(5));
    }

    private void cleanupReview(UUID reviewId) {
        if (reviewId != null) {
            jdbcTemplate.update("DELETE FROM review_targets WHERE review_id = ?", reviewId);
            jdbcTemplate.update("DELETE FROM reviews WHERE id = ?", reviewId);
        }
    }

    @Test
    @DisplayName("6.1. Cold Start: Usuário sem seguidos recebe reviews públicas de terceiros com envelope e sem campos internos")
    void shouldReturnDiscoveryCandidatesWhenRequesterHasNoFollows() throws Exception {
        TestUser requester = registerUser("v2CsReq");
        TestUser otherAuthor = registerUser("v2CsAuthor");

        Place place = createPlace();
        RateableTarget target = createTarget(TargetType.PLACE);

        // Requester NÃO segue otherAuthor. otherAuthor cria review PUBLIC + ACTIVE com timestamp no topo da janela
        Instant topCreatedAt = resolveFutureDiscoveryReviewTimestamp();
        Review discoveryReview = createReview(otherAuthor.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE, false, topCreatedAt);

        try {
            mockMvc.perform(get("/api/v2/feed")
                            .param("size", "50")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", not(empty())))
                    .andExpect(jsonPath("$.items[*].id", hasItem(discoveryReview.getId().toString())))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(50))
                    .andExpect(jsonPath("$.windowSize", greaterThanOrEqualTo(1)))
                    .andExpect(jsonPath("$.totalPages", greaterThanOrEqualTo(1)))
                    // Validação de que nenhum campo interno de ranking/pipeline vaza
                    .andExpect(jsonPath("$.items[0].score").doesNotExist())
                    .andExpect(jsonPath("$.items[0].isDirectFollow").doesNotExist())
                    .andExpect(jsonPath("$.items[0].rankingMode").doesNotExist())
                    .andExpect(jsonPath("$.items[0].coldStart").doesNotExist())
                    .andExpect(jsonPath("$.items[0].discovery").doesNotExist());
        } finally {
            cleanupReview(discoveryReview.getId());
        }
    }

    @Test
    @DisplayName("6.2. Cold Start e Privacidade: Review anônima pública oculta estritamente authorId, handle e dados sensíveis")
    void shouldStrictlyPreservePrivacyForAnonymousDiscoveryCandidate() throws Exception {
        TestUser requester = registerUser("v2AnonCsReq");
        TestUser anonAuthor = registerUser("v2AnonCsAuthor");

        Place place = createPlace();
        RateableTarget target = createTarget(TargetType.PLACE);

        // Requester NÃO segue anonAuthor. Avaliação pública anônima no topo
        Review anonDiscoveryReview = createReview(anonAuthor.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE, true, Instant.now().plus(java.time.Duration.ofDays(6)));

        MvcResult result = mockMvc.perform(get("/api/v2/feed")
                        .param("size", "50")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", hasItem(anonDiscoveryReview.getId().toString())))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode items = json.get("items");
        JsonNode targetItem = null;
        for (JsonNode item : items) {
            if (anonDiscoveryReview.getId().toString().equals(item.get("id").asText())) {
                targetItem = item;
                break;
            }
        }

        org.junit.jupiter.api.Assertions.assertNotNull(targetItem, "Review anônima de descoberta deve estar presente");
        org.junit.jupiter.api.Assertions.assertTrue(targetItem.get("isAnonymous").asBoolean());
        org.junit.jupiter.api.Assertions.assertTrue(targetItem.get("author").get("id") == null || targetItem.get("author").get("id").isNull());
        org.junit.jupiter.api.Assertions.assertTrue(targetItem.get("author").get("handle") == null || targetItem.get("author").get("handle").isNull());
        org.junit.jupiter.api.Assertions.assertTrue(targetItem.get("author").get("avatarUrl") == null || targetItem.get("author").get("avatarUrl").isNull());
        org.junit.jupiter.api.Assertions.assertEquals("Anônimo", targetItem.get("author").get("displayName").asText());
        org.junit.jupiter.api.Assertions.assertTrue(targetItem.get("author").get("isAnonymous").asBoolean());
        org.junit.jupiter.api.Assertions.assertTrue(targetItem.get("score") == null || targetItem.get("score").isNull());
        org.junit.jupiter.api.Assertions.assertTrue(targetItem.get("isDirectFollow") == null || targetItem.get("isDirectFollow").isNull());
    }

    @Test
    @DisplayName("6.3. Fallback não invade rede social: Requester com candidatos sociais recebe apenas avaliações de seguidos")
    void shouldNotInvokeColdStartWhenRequesterHasSocialCandidates() throws Exception {
        TestUser requester = registerUser("v2SocReq");
        TestUser followedAuthor = registerUser("v2SocFollowed");
        TestUser unfollowedAuthor = registerUser("v2SocUnfollowed");

        Place place = createPlace();
        RateableTarget target = createTarget(TargetType.PLACE);

        // Requester segue SOMENTE followedAuthor
        userFollowRepository.follow(requester.userId(), followedAuthor.userId());

        // Ambos publicam reviews públicas recentes
        Review socialReview = createReview(followedAuthor.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now().plus(java.time.Duration.ofDays(7)));
        Review strangerReview = createReview(unfollowedAuthor.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now().plus(java.time.Duration.ofDays(8)));

        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(socialReview.getId().toString()))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(strangerReview.getId().toString()))))
                .andExpect(jsonPath("$.windowSize").value(1));
    }
}
