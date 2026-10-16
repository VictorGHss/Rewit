package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.ReviewTargetRepository;
import com.rewit.application.port.UserFollowRepository;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewTarget;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.report.CreateReportRequest;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.CreateReviewRequest;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.CreateReviewTargetRequest;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.UpdateReviewRequest;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Anonimato de avaliações observado pela API: nenhum campo da resposta revela o autor e nenhuma diferença de acesso,
 * listagem ou contador muda conforme quem segue quem. Os feeds estão em {@link FeedControllerIntegrationTest} e
 * {@link FeedV2ControllerIntegrationTest}.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração: anonimato de avaliações sem oráculo de autoria")
class AnonymousReviewPrivacyIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ReviewTargetRepository reviewTargetRepository;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

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
        String handle = prefix + "_" + suffix;
        String displayName = "Pessoa " + suffix;

        RegisterRequest request = new RegisterRequest(email, "SenhaSegura123!", handle, displayName);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(
                json.get("accessToken").asText(),
                UUID.fromString(json.get("user").get("id").asText()),
                json.get("user").get("handle").asText(),
                displayName);
    }

    private RateableTarget createTarget() {
        return rateableTargetRepository.save(new RateableTarget(UUID.randomUUID(), TargetType.PLACE));
    }

    private UUID createReviewViaApi(TestUser author, UUID targetId, boolean anonymous, String visibility) throws Exception {
        CreateReviewRequest request = new CreateReviewRequest(
                null, "Experiência " + UUID.randomUUID(), anonymous, visibility,
                List.of(new CreateReviewTargetRequest(targetId, new BigDecimal("4.0"), "Comentário")));
        MvcResult result = mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    /** Linha anônima FOLLOWERS anterior à validação do domínio: só a reconstituição a aceita. */
    private Review createLegacyAnonymousFollowersReview(TestUser author, RateableTarget target) {
        UUID reviewId = UUID.randomUUID();
        Instant now = Instant.now();
        Review review = new Review(reviewId, author.userId(), null, "Avaliação antiga anônima para seguidores",
                true, false, ReviewStatus.ACTIVE, "FOLLOWERS", null, null, null, now, now);
        ReviewTarget reviewTarget = new ReviewTarget(null, reviewId, target.getId(), new BigDecimal("2.0"), "Antiga");
        review.addTarget(reviewTarget);
        Review saved = reviewRepository.save(review);
        reviewTargetRepository.save(reviewTarget);
        return saved;
    }

    private static String bearer(TestUser user) {
        return "Bearer " + user.accessToken();
    }

    private record Observed(int status, String code, String detail) {}

    /** O que um terceiro observa de uma resposta de erro, sem os campos que variam por requisição. */
    private Observed observe(RequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        JsonNode json = body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        return new Observed(
                result.getResponse().getStatus(),
                json.path("code").asText(null),
                json.path("detail").asText(null));
    }

    private List<String> targetListingIds(TestUser requester, UUID targetId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/targets/{id}/reviews", targetId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(requester))
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> ids = new ArrayList<>();
        json.get("content").forEach(item -> ids.add(item.get("id").asText()));
        assertEquals(ids.size(), json.get("totalElements").asInt(), "O total da listagem acompanha o conteúdo");
        return ids;
    }

    private JsonNode publicStats(TestUser requester, TestUser profileOwner) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/users/{id}", profileOwner.userId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(requester)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("stats");
    }

    private static void assertDoesNotRevealAuthor(String body, TestUser author) {
        assertFalse(body.contains(author.userId().toString()), "A resposta não pode conter o id do autor");
        assertFalse(body.contains(author.handle()), "A resposta não pode conter o handle do autor");
        assertFalse(body.contains(author.displayName()), "A resposta não pode conter o nome do autor");
    }

    @Test
    @DisplayName("1. Anônima PUBLIC: detalhe e listagem por alvo não trazem id, handle, nome nem avatar do autor, nem para quem o segue")
    void anonymousReviewNeverExposesAuthorInDetailOrTargetListing() throws Exception {
        TestUser author = registerUser("anonAuthor");
        TestUser follower = registerUser("anonFollower");
        RateableTarget target = createTarget();
        userFollowRepository.follow(follower.userId(), author.userId());

        UUID reviewId = createReviewViaApi(author, target.getId(), true, "PUBLIC");

        MvcResult detail = mockMvc.perform(get("/api/v1/reviews/{id}", reviewId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(follower)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAnonymous").value(true))
                .andExpect(jsonPath("$.isMine").value(false))
                .andExpect(jsonPath("$.author.id").doesNotExist())
                .andExpect(jsonPath("$.author.handle").doesNotExist())
                .andExpect(jsonPath("$.author.avatarUrl").doesNotExist())
                .andExpect(jsonPath("$.author.displayName").value("Anônimo"))
                .andExpect(jsonPath("$.author.isAnonymous").value(true))
                .andReturn();
        assertDoesNotRevealAuthor(detail.getResponse().getContentAsString(), author);

        MvcResult listing = mockMvc.perform(get("/api/v1/targets/{id}/reviews", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(follower)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(reviewId.toString()))
                .andExpect(jsonPath("$.content[0].author.id").doesNotExist())
                .andExpect(jsonPath("$.content[0].author.handle").doesNotExist())
                .andExpect(jsonPath("$.content[0].author.displayName").value("Anônimo"))
                .andReturn();
        assertDoesNotRevealAuthor(listing.getResponse().getContentAsString(), author);
    }

    @Test
    @DisplayName("2. Anônima FOLLOWERS antiga: o detalhe responde igual a seguidor e não seguidor, antes e depois de seguir")
    void legacyAnonymousFollowersDetailIsNotAnAuthorshipOracle() throws Exception {
        TestUser author = registerUser("legacyAuthor");
        TestUser follower = registerUser("legacyFollower");
        TestUser stranger = registerUser("legacyStranger");
        RateableTarget target = createTarget();
        userFollowRepository.follow(follower.userId(), author.userId());
        Review legacy = createLegacyAnonymousFollowersReview(author, target);

        Observed asFollower = observe(get("/api/v1/reviews/{id}", legacy.getId()).header(HttpHeaders.AUTHORIZATION, bearer(follower)));
        Observed asStranger = observe(get("/api/v1/reviews/{id}", legacy.getId()).header(HttpHeaders.AUTHORIZATION, bearer(stranger)));
        assertEquals(403, asFollower.status());
        assertEquals(asStranger, asFollower, "Seguir o autor real não pode mudar a resposta");

        // O ataque: seguir um candidato e repetir a consulta
        userFollowRepository.follow(stranger.userId(), author.userId());
        Observed afterFollowing = observe(get("/api/v1/reviews/{id}", legacy.getId()).header(HttpHeaders.AUTHORIZATION, bearer(stranger)));
        assertEquals(asStranger, afterFollowing, "Seguir o autor real não pode liberar nem mudar o acesso");

        // O autor continua lendo, com a identidade pública mascarada
        mockMvc.perform(get("/api/v1/reviews/{id}", legacy.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isMine").value(true))
                .andExpect(jsonPath("$.author.id").doesNotExist())
                .andExpect(jsonPath("$.author.displayName").value("Anônimo"));
    }

    @Test
    @DisplayName("3. Anônima FOLLOWERS antiga fica fora da listagem por alvo de seguidor e não seguidor; o autor a vê")
    void legacyAnonymousFollowersIsNotListedThroughTheFollowEdge() throws Exception {
        TestUser author = registerUser("listAuthor");
        TestUser follower = registerUser("listFollower");
        TestUser stranger = registerUser("listStranger");
        RateableTarget target = createTarget();
        userFollowRepository.follow(follower.userId(), author.userId());

        UUID publicReviewId = createReviewViaApi(author, target.getId(), false, "PUBLIC");
        Review legacy = createLegacyAnonymousFollowersReview(author, target);

        List<String> followerIds = targetListingIds(follower, target.getId());
        List<String> strangerIds = targetListingIds(stranger, target.getId());

        assertEquals(strangerIds, followerIds, "A listagem não pode diferir conforme quem segue o autor real");
        assertFalse(followerIds.contains(legacy.getId().toString()));
        assertTrue(followerIds.contains(publicReviewId.toString()));

        mockMvc.perform(get("/api/v1/targets/{id}/reviews", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(legacy.getId().toString())));
    }

    @Test
    @DisplayName("4. Útil e denúncia numa anônima FOLLOWERS antiga respondem igual a seguidor e não seguidor")
    void legacyAnonymousFollowersInteractionsAreNotAnAuthorshipOracle() throws Exception {
        TestUser author = registerUser("interAuthor");
        TestUser follower = registerUser("interFollower");
        TestUser stranger = registerUser("interStranger");
        RateableTarget target = createTarget();
        userFollowRepository.follow(follower.userId(), author.userId());
        Review legacy = createLegacyAnonymousFollowersReview(author, target);

        Observed helpfulFollower = observe(post("/api/v1/reviews/{id}/helpful", legacy.getId()).header(HttpHeaders.AUTHORIZATION, bearer(follower)));
        Observed helpfulStranger = observe(post("/api/v1/reviews/{id}/helpful", legacy.getId()).header(HttpHeaders.AUTHORIZATION, bearer(stranger)));
        assertEquals(403, helpfulFollower.status());
        assertEquals(helpfulStranger, helpfulFollower);

        String report = objectMapper.writeValueAsString(new CreateReportRequest(legacy.getId(), ReportReason.SPAM, null));
        Observed reportFollower = observe(post("/api/v1/reports").header(HttpHeaders.AUTHORIZATION, bearer(follower))
                .contentType(MediaType.APPLICATION_JSON).content(report));
        Observed reportStranger = observe(post("/api/v1/reports").header(HttpHeaders.AUTHORIZATION, bearer(stranger))
                .contentType(MediaType.APPLICATION_JSON).content(report));
        assertEquals(403, reportFollower.status());
        assertEquals(reportStranger, reportFollower);
    }

    @Test
    @DisplayName("5. Review FOLLOWERS identificada: seguidor lê com autor correto; não seguidor recebe 403 e não a vê na listagem")
    void identifiedFollowersReviewKeepsAuthorizationAndAuthor() throws Exception {
        TestUser author = registerUser("folAuthor");
        TestUser follower = registerUser("folFollower");
        TestUser stranger = registerUser("folStranger");
        RateableTarget target = createTarget();
        userFollowRepository.follow(follower.userId(), author.userId());

        UUID reviewId = createReviewViaApi(author, target.getId(), false, "FOLLOWERS");

        mockMvc.perform(get("/api/v1/reviews/{id}", reviewId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(follower)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAnonymous").value(false))
                .andExpect(jsonPath("$.author.id").value(author.userId().toString()))
                .andExpect(jsonPath("$.author.handle").value(author.handle()))
                .andExpect(jsonPath("$.author.displayName").value(author.displayName()));
        mockMvc.perform(get("/api/v1/reviews/{id}", reviewId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertTrue(targetListingIds(follower, target.getId()).contains(reviewId.toString()));
        assertFalse(targetListingIds(stranger, target.getId()).contains(reviewId.toString()));
    }

    @Test
    @DisplayName("6. Criação rejeita anônima com FOLLOWERS (422) e não grava nada")
    void createRejectsAnonymousFollowers() throws Exception {
        TestUser author = registerUser("createAuthor");
        RateableTarget target = createTarget();

        for (String visibility : List.of("FOLLOWERS", "followers")) {
            CreateReviewRequest request = new CreateReviewRequest(null, "Texto", true, visibility,
                    List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("4.0"), "Comentário")));
            mockMvc.perform(post("/api/v1/reviews")
                            .header(HttpHeaders.AUTHORIZATION, bearer(author))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("ANONYMOUS_FOLLOWERS_VISIBILITY_NOT_ALLOWED"));
        }

        mockMvc.perform(get("/api/v1/me/reviews").header(HttpHeaders.AUTHORIZATION, bearer(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // As demais combinações seguem permitidas
        createReviewViaApi(author, target.getId(), true, "PUBLIC");
        createReviewViaApi(author, target.getId(), true, "PRIVATE");
        createReviewViaApi(author, target.getId(), false, "FOLLOWERS");
    }

    @Test
    @DisplayName("7. Edição não estabelece anônima com FOLLOWERS (422) e não altera a avaliação")
    void updateCannotEstablishAnonymousFollowers() throws Exception {
        TestUser author = registerUser("editAuthor");
        RateableTarget target = createTarget();
        UUID anonymousPublic = createReviewViaApi(author, target.getId(), true, "PUBLIC");
        UUID identifiedFollowers = createReviewViaApi(author, target.getId(), false, "FOLLOWERS");
        UUID identifiedPublic = createReviewViaApi(author, target.getId(), false, "PUBLIC");

        List<Object[]> attempts = List.of(
                new Object[]{anonymousPublic, new UpdateReviewRequest("Novo texto", null, null, "FOLLOWERS")},
                new Object[]{identifiedFollowers, new UpdateReviewRequest("Novo texto", null, true, null)},
                new Object[]{identifiedPublic, new UpdateReviewRequest("Novo texto", null, true, "FOLLOWERS")});

        for (Object[] attempt : attempts) {
            UUID reviewId = (UUID) attempt[0];
            MvcResult before = mockMvc.perform(get("/api/v1/reviews/{id}", reviewId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(author)))
                    .andReturn();
            JsonNode beforeJson = objectMapper.readTree(before.getResponse().getContentAsString());

            mockMvc.perform(patch("/api/v1/reviews/{id}", reviewId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(author))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(attempt[1])))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("ANONYMOUS_FOLLOWERS_VISIBILITY_NOT_ALLOWED"));

            mockMvc.perform(get("/api/v1/reviews/{id}", reviewId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(author)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isAnonymous").value(beforeJson.get("isAnonymous").asBoolean()))
                    .andExpect(jsonPath("$.visibility").value(beforeJson.get("visibility").asText()))
                    .andExpect(jsonPath("$.experienceText").value(beforeJson.get("experienceText").asText()));
        }

        // Transições que não criam a combinação seguem permitidas
        mockMvc.perform(patch("/api/v1/reviews/{id}", anonymousPublic)
                        .header(HttpHeaders.AUTHORIZATION, bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateReviewRequest("Agora privada", null, null, "PRIVATE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PRIVATE"));
    }

    @Test
    @DisplayName("8. Perfil público não reage a avaliação anônima nem a voto útil nela; reage às identificadas")
    void publicProfileCountersDoNotRevealAnonymousAuthorship() throws Exception {
        TestUser author = registerUser("statsAuthor");
        TestUser observer = registerUser("statsObserver");
        RateableTarget target = createTarget();

        JsonNode baseline = publicStats(observer, author);

        UUID anonymousReview = createReviewViaApi(author, target.getId(), true, "PUBLIC");
        mockMvc.perform(post("/api/v1/reviews/{id}/helpful", anonymousReview)
                        .header(HttpHeaders.AUTHORIZATION, bearer(observer)))
                .andExpect(status().isOk());

        JsonNode afterAnonymous = publicStats(observer, author);
        assertEquals(baseline, afterAnonymous,
                "Votar numa anônima e ver o contador de um perfil mudar revelaria o autor");

        UUID identifiedReview = createReviewViaApi(author, target.getId(), false, "PUBLIC");
        mockMvc.perform(post("/api/v1/reviews/{id}/helpful", identifiedReview)
                        .header(HttpHeaders.AUTHORIZATION, bearer(observer)))
                .andExpect(status().isOk());

        JsonNode afterIdentified = publicStats(observer, author);
        assertEquals(baseline.get("totalReviews").asLong() + 1, afterIdentified.get("totalReviews").asLong());
        assertEquals(baseline.get("helpfulVotesReceived").asLong() + 1, afterIdentified.get("helpfulVotesReceived").asLong());
    }

    @Test
    @DisplayName("9. A listagem de seguidor não muda quando o autor publica uma anônima PUBLIC (sem marca de relação)")
    void anonymousPublicReviewIsListedEquallyForFollowerAndStranger() throws Exception {
        TestUser author = registerUser("eqAuthor");
        TestUser follower = registerUser("eqFollower");
        TestUser stranger = registerUser("eqStranger");
        RateableTarget target = createTarget();
        userFollowRepository.follow(follower.userId(), author.userId());

        UUID anonymousReview = createReviewViaApi(author, target.getId(), true, "PUBLIC");

        assertEquals(targetListingIds(stranger, target.getId()), targetListingIds(follower, target.getId()));
        mockMvc.perform(get("/api/v1/targets/{id}/reviews", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(follower)))
                .andExpect(jsonPath("$.content[*].id", hasItem(anonymousReview.toString())))
                .andExpect(jsonPath("$.content[*].author.id", not(hasItem(author.userId().toString()))));
    }
}
