package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.ReviewDto.TargetStatsView;
import com.rewit.application.dto.reputation.ReputationDtos.ReputationView;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.UserFollowRepository;
import com.rewit.application.service.ReputationService;
import com.rewit.application.service.ReviewHelpfulService;
import com.rewit.application.service.ReviewService;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.RateableTarget;
import com.rewit.infrastructure.persistence.entity.ReviewJpaEntity;
import com.rewit.infrastructure.persistence.repository.CheckInJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewTargetJpaRepository;
import com.rewit.presentation.dto.auth.RegisterRequest;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração de API REST: Ciclo de Vida da Review PATCH/DELETE (Step 25.3)")
class ReviewLifecycleControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    @Autowired
    private ReviewJpaRepository reviewJpaRepository;

    @Autowired
    private ReviewTargetJpaRepository reviewTargetJpaRepository;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private ReputationService reputationService;

    @Autowired
    private ReviewHelpfulService reviewHelpfulService;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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
                "Comida italiana",
                "Rua XV de Novembro, 100",
                "100",
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

    private UUID createReviewViaHttp(TestUser user, UUID placeId, List<CreateReviewTargetRequest> targets, String text, boolean isAnon, String visibility) throws Exception {
        CreateReviewRequest request = new CreateReviewRequest(placeId, text, isAnon, visibility, targets);
        MvcResult res = mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        return UUID.fromString(node.get("id").asText());
    }

    @Test
    @DisplayName("1. PATCH /api/v1/reviews/{id} sucesso: autor edita texto dentro de 24h preservando createdAt")
    void shouldUpdateReviewExperienceTextSuccessfully() throws Exception {
        TestUser author = registerUser("patch_text");
        Place place = createPlace();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        UUID reviewId = createReviewViaHttp(author, place.getId(),
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("4.0"), "Bom")),
                "Texto inicial", false, "PUBLIC");

        ReviewJpaEntity beforeEntity = reviewJpaRepository.findById(reviewId).orElseThrow();
        Instant originalCreatedAt = beforeEntity.getCreatedAt();
        Instant originalUpdatedAt = beforeEntity.getUpdatedAt();

        Thread.sleep(10); // Garantir delta temporal em updatedAt

        UpdateReviewRequest patchReq = new UpdateReviewRequest("Texto atualizado e enriquecido", null, null, null);

        mockMvc.perform(patch("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patchReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reviewId.toString()))
                .andExpect(jsonPath("$.experienceText").value("Texto atualizado e enriquecido"))
                .andExpect(jsonPath("$.isAnonymous").value(false))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.targets[0].rating").value(4.0));

        ReviewJpaEntity afterEntity = reviewJpaRepository.findById(reviewId).orElseThrow();
        assertEquals(originalCreatedAt, afterEntity.getCreatedAt());
        assertTrue(afterEntity.getUpdatedAt().isAfter(originalUpdatedAt));
        assertEquals("Texto atualizado e enriquecido", afterEntity.getExperienceText());
    }

    @Test
    @DisplayName("2. PATCH /api/v1/reviews/{id} sucesso: autor altera nota do alvo com recálculo imediato de stats no PostgreSQL")
    void shouldUpdateReviewTargetRatingAndRecalculateStats() throws Exception {
        TestUser author = registerUser("patch_rating");
        Place place = createPlace();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        UUID reviewId = createReviewViaHttp(author, place.getId(),
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("2.0"), "Inicial")),
                "Texto inicial", false, "PUBLIC");

        TargetStatsView statsBefore = reviewService.getTargetStats(target.getId());
        assertEquals(new BigDecimal("2.00"), statsBefore.averageRating());
        assertEquals(1, statsBefore.reviewsCount());

        UpdateReviewRequest patchReq = new UpdateReviewRequest(null, Map.of(target.getId(), new BigDecimal("5.0")), null, null);

        mockMvc.perform(patch("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patchReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets[0].rating").value(5.0))
                .andExpect(jsonPath("$.experienceText").value("Texto inicial")); // Semântica PATCH: texto mantido

        TargetStatsView statsAfter = reviewService.getTargetStats(target.getId());
        assertEquals(new BigDecimal("5.00"), statsAfter.averageRating());
        assertEquals(1, statsAfter.reviewsCount());
    }

    @Test
    @DisplayName("3. PATCH /api/v1/reviews/{id} multi-target: altera nota de um alvo e preserva o outro com recálculo isolado")
    void shouldUpdateMultiTargetSelectively() throws Exception {
        TestUser author = registerUser("patch_multi");
        Place place = createPlace();
        RateableTarget target1 = createRateableTarget(TargetType.PLACE);
        RateableTarget target2 = createRateableTarget(TargetType.PRODUCT);

        UUID reviewId = createReviewViaHttp(author, place.getId(),
                List.of(
                        new CreateReviewTargetRequest(target1.getId(), new BigDecimal("2.0"), "T1"),
                        new CreateReviewTargetRequest(target2.getId(), new BigDecimal("4.0"), "T2")
                ),
                "Multi-alvo", false, "PUBLIC");

        assertEquals(new BigDecimal("2.00"), reviewService.getTargetStats(target1.getId()).averageRating());
        assertEquals(new BigDecimal("4.00"), reviewService.getTargetStats(target2.getId()).averageRating());

        // Altera somente target1 para 4.0
        UpdateReviewRequest patchReq = new UpdateReviewRequest(null, Map.of(target1.getId(), new BigDecimal("4.0")), null, null);

        mockMvc.perform(patch("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patchReq)))
                .andExpect(status().isOk());

        assertEquals(new BigDecimal("4.00"), reviewService.getTargetStats(target1.getId()).averageRating());
        assertEquals(new BigDecimal("4.00"), reviewService.getTargetStats(target2.getId()).averageRating());
    }

    @Test
    @DisplayName("4. PATCH /api/v1/reviews/{id} anonimato: máscara pública e dedução/recomposição de reputação factual")
    void shouldToggleAnonymityAndReflectOnReputationAndPrivacy() throws Exception {
        TestUser author = registerUser("patch_anon");
        Place place = createPlace();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        UUID reviewId = createReviewViaHttp(author, place.getId(),
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("4.0"), "T")),
                "Review pública inicial", false, "PUBLIC");

        // Reputação inicial do autor: 1 review ativa
        ReputationView repBefore = reputationService.getReputation(author.userId());
        assertEquals(1, repBefore.signals().activeReviews());

        // 1. Tornar ANÔNIMA
        UpdateReviewRequest makeAnonReq = new UpdateReviewRequest(null, null, true, null);
        mockMvc.perform(patch("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(makeAnonReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAnonymous").value(true))
                .andExpect(jsonPath("$.author.isAnonymous").value(true))
                .andExpect(jsonPath("$.author.displayName").value("Anônimo"))
                .andExpect(jsonPath("$.author.id").doesNotExist());

        // Reputação deve expurgar a review anônima
        ReputationView repAnon = reputationService.getReputation(author.userId());
        assertEquals(0, repAnon.signals().activeReviews());

        // 2. Reverter para NÃO-ANÔNIMA
        UpdateReviewRequest makePublicReq = new UpdateReviewRequest(null, null, false, null);
        mockMvc.perform(patch("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(makePublicReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAnonymous").value(false))
                .andExpect(jsonPath("$.author.isAnonymous").value(false))
                .andExpect(jsonPath("$.author.handle").value(author.handle()));

        // Reputação deve reincorporar a review
        ReputationView repPublic = reputationService.getReputation(author.userId());
        assertEquals(1, repPublic.signals().activeReviews());
    }

    @Test
    @DisplayName("5. PATCH /api/v1/reviews/{id} sucesso: alteração de visibilidade (PUBLIC -> PRIVATE)")
    void shouldUpdateReviewVisibilitySuccessfully() throws Exception {
        TestUser author = registerUser("patch_vis");
        Place place = createPlace();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        UUID reviewId = createReviewViaHttp(author, place.getId(),
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("4.0"), "T")),
                "Texto", false, "PUBLIC");

        UpdateReviewRequest patchReq = new UpdateReviewRequest(null, null, null, "PRIVATE");

        mockMvc.perform(patch("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patchReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PRIVATE"));

        ReviewJpaEntity entity = reviewJpaRepository.findById(reviewId).orElseThrow();
        assertEquals("PRIVATE", entity.getVisibility());
    }

    @Test
    @DisplayName("6. PATCH /api/v1/reviews/{id} IDOR rejeitado com 403 e REVIEW_NOT_OWNED quando terceiro tenta editar")
    void shouldRejectPatchWhenRequesterIsNotAuthor() throws Exception {
        TestUser author = registerUser("patch_author");
        TestUser imposter = registerUser("patch_imposter");
        Place place = createPlace();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        UUID reviewId = createReviewViaHttp(author, place.getId(),
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("4.0"), "T")),
                "Texto do autor", false, "PUBLIC");

        UpdateReviewRequest hackReq = new UpdateReviewRequest("Texto invadido", null, null, null);

        mockMvc.perform(patch("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + imposter.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(hackReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_OWNED"));

        // Garante que no banco nada mudou
        ReviewJpaEntity dbReview = reviewJpaRepository.findById(reviewId).orElseThrow();
        assertEquals("Texto do autor", dbReview.getExperienceText());
    }

    @Test
    @DisplayName("7. PATCH /api/v1/reviews/{id} rejeitado com 404 e REVIEW_NOT_FOUND para review inexistente")
    void shouldRejectPatchWhenReviewNotFound() throws Exception {
        TestUser user = registerUser("patch_not_found");
        UUID nonExistentId = UUID.randomUUID();

        UpdateReviewRequest req = new UpdateReviewRequest("Texto", null, null, null);

        mockMvc.perform(patch("/api/v1/reviews/" + nonExistentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    @Test
    @DisplayName("8. PATCH /api/v1/reviews/{id} rejeitado com 401 Unauthorized sem token JWT")
    void shouldRejectPatchWhenUnauthenticated() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UpdateReviewRequest req = new UpdateReviewRequest("Texto", null, null, null);

        mockMvc.perform(patch("/api/v1/reviews/" + reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("9. PATCH /api/v1/reviews/{id} rejeitado com 409 quando review está UNDER_REVIEW")
    void shouldRejectPatchWhenReviewIsUnderReview() throws Exception {
        TestUser author = registerUser("patch_under_rev");
        Place place = createPlace();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        UUID reviewId = createReviewViaHttp(author, place.getId(),
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("4.0"), "T")),
                "Texto em investigação", false, "PUBLIC");

        // Simula moderação
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            ReviewJpaEntity entity = reviewJpaRepository.findById(reviewId).orElseThrow();
            entity.setStatus("UNDER_REVIEW");
            reviewJpaRepository.save(entity);
        });

        UpdateReviewRequest req = new UpdateReviewRequest("Tentando burlar moderação", null, null, null);

        mockMvc.perform(patch("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("REVIEW_UNDER_REVIEW_MUTATION_DENIED"));
    }

    @Test
    @DisplayName("10. PATCH /api/v1/reviews/{id} rejeitado com 409 quando janela de 24h expirou")
    void shouldRejectPatchWhen24HoursWindowHasExpired() throws Exception {
        TestUser author = registerUser("patch_window_exp");
        Place place = createPlace();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        UUID reviewId = createReviewViaHttp(author, place.getId(),
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("4.0"), "T")),
                "Texto antigo", false, "PUBLIC");

        // Força createdAt para 25 horas atrás no PostgreSQL via SQL direto
        jdbcTemplate.update("UPDATE reviews SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(25, ChronoUnit.HOURS)),
                reviewId);

        UpdateReviewRequest req = new UpdateReviewRequest("Edição atrasada", null, null, null);

        mockMvc.perform(patch("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("REVIEW_EDIT_WINDOW_EXPIRED"));
    }

    @Test
    @DisplayName("11. PATCH /api/v1/reviews/{id} bloqueia rating quando possui helpful, mas permite alteração de texto")
    void shouldBlockRatingPatchWhenHelpfulExistsButAllowTextPatch() throws Exception {
        TestUser author = registerUser("patch_help_auth");
        TestUser voter = registerUser("patch_help_voter");
        Place place = createPlace();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        UUID reviewId = createReviewViaHttp(author, place.getId(),
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("3.0"), "T")),
                "Texto inicial", false, "PUBLIC");

        // Voter vota Helpful
        reviewHelpfulService.addHelpful(reviewId, voter.userId());

        // 1. Tentar alterar rating: deve ser bloqueado com 409
        UpdateReviewRequest ratingPatchReq = new UpdateReviewRequest(null, Map.of(target.getId(), new BigDecimal("5.0")), null, null);

        mockMvc.perform(patch("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ratingPatchReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("REVIEW_EDIT_RATING_BLOCKED_BY_HELPFUL"));

        // 2. Tentar alterar apenas texto: deve ser permitido com 200 OK
        UpdateReviewRequest textPatchReq = new UpdateReviewRequest("Texto atualizado com helpful preservado", null, null, null);

        mockMvc.perform(patch("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(textPatchReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.experienceText").value("Texto atualizado com helpful preservado"))
                .andExpect(jsonPath("$.targets[0].rating").value(3.0))
                .andExpect(jsonPath("$.helpfulCount").value(1));
    }

    @Test
    @DisplayName("12. PATCH /api/v1/reviews/{id} Bean Validation rejeita valores inválidos com 400 Bad Request")
    void shouldRejectPatchWithInvalidPayloadViaBeanValidation() throws Exception {
        TestUser author = registerUser("patch_bad_input");
        Place place = createPlace();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        UUID reviewId = createReviewViaHttp(author, place.getId(),
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("3.0"), "T")),
                "Texto", false, "PUBLIC");

        // Nota inválida (> 5.0)
        UpdateReviewRequest invalidRatingReq = new UpdateReviewRequest(null, Map.of(target.getId(), new BigDecimal("6.0")), null, null);

        mockMvc.perform(patch("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRatingReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Erro de Validação de Dados"));

        // Visibilidade inválida
        UpdateReviewRequest invalidVisReq = new UpdateReviewRequest(null, null, null, "INVALID_VISIBILITY_LEVEL");

        mockMvc.perform(patch("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidVisReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Erro de Validação de Dados"));
    }

    @Test
    @DisplayName("13. DELETE /api/v1/reviews/{id} sucesso: retorna 204 No Content e expurga review dos agregados")
    void shouldSoftDeleteReviewSuccessfully() throws Exception {
        TestUser author = registerUser("del_success");
        Place place = createPlace();
        RateableTarget target1 = createRateableTarget(TargetType.PLACE);
        RateableTarget target2 = createRateableTarget(TargetType.PRODUCT);

        UUID reviewId = createReviewViaHttp(author, place.getId(),
                List.of(
                        new CreateReviewTargetRequest(target1.getId(), new BigDecimal("4.0"), "T1"),
                        new CreateReviewTargetRequest(target2.getId(), new BigDecimal("5.0"), "T2")
                ),
                "Review para exclusão", false, "PUBLIC");

        assertEquals(1, reviewService.getTargetStats(target1.getId()).reviewsCount());
        assertEquals(1, reviewService.getTargetStats(target2.getId()).reviewsCount());
        assertEquals(1, reputationService.getReputation(author.userId()).signals().activeReviews());

        mockMvc.perform(delete("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // 1. Review status agora é REMOVED no PostgreSQL
        ReviewJpaEntity dbReview = reviewJpaRepository.findById(reviewId).orElseThrow();
        assertEquals("REMOVED", dbReview.getStatus());

        // 2. Targets continuam persistidos (soft delete, não hard delete)
        assertEquals(2, reviewTargetJpaRepository.findByReviewId(reviewId).size());

        // 3. Stats foram zerados
        assertEquals(0, reviewService.getTargetStats(target1.getId()).reviewsCount());
        assertEquals(0, reviewService.getTargetStats(target2.getId()).reviewsCount());

        // 4. Reputação foi recalculada (sem a review)
        assertEquals(0, reputationService.getReputation(author.userId()).signals().activeReviews());

        // 5. Consulta pública direta retorna review com status REMOVED
        mockMvc.perform(get("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REMOVED"));
    }

    @Test
    @DisplayName("14. DELETE /api/v1/reviews/{id} a partir de UNDER_REVIEW é permitido ao autor para retirar do ar")
    void shouldAllowDeleteFromUnderReview() throws Exception {
        TestUser author = registerUser("del_under_rev");
        Place place = createPlace();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        UUID reviewId = createReviewViaHttp(author, place.getId(),
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("4.0"), "T")),
                "Conteúdo sob denúncia", false, "PUBLIC");

        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            ReviewJpaEntity entity = reviewJpaRepository.findById(reviewId).orElseThrow();
            entity.setStatus("UNDER_REVIEW");
            reviewJpaRepository.save(entity);
        });

        mockMvc.perform(delete("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken()))
                .andExpect(status().isNoContent());

        ReviewJpaEntity dbReview = reviewJpaRepository.findById(reviewId).orElseThrow();
        assertEquals("REMOVED", dbReview.getStatus());
    }

    @Test
    @DisplayName("15. DELETE /api/v1/reviews/{id} IDOR rejeitado com 403 e REVIEW_NOT_OWNED")
    void shouldRejectDeleteWhenRequesterIsNotAuthor() throws Exception {
        TestUser author = registerUser("del_auth");
        TestUser imposter = registerUser("del_imposter");
        Place place = createPlace();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        UUID reviewId = createReviewViaHttp(author, place.getId(),
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("4.0"), "T")),
                "Texto protegido", false, "PUBLIC");

        mockMvc.perform(delete("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + imposter.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_OWNED"));

        ReviewJpaEntity dbReview = reviewJpaRepository.findById(reviewId).orElseThrow();
        assertEquals("ACTIVE", dbReview.getStatus());
    }

    @Test
    @DisplayName("16. DELETE /api/v1/reviews/{id} rejeitado com 409 quando review já está REMOVED")
    void shouldRejectDeleteWhenReviewAlreadyRemoved() throws Exception {
        TestUser author = registerUser("del_already_rem");
        Place place = createPlace();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        UUID reviewId = createReviewViaHttp(author, place.getId(),
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("4.0"), "T")),
                "Texto", false, "PUBLIC");

        // Primeiro delete: sucesso 204
        mockMvc.perform(delete("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken()))
                .andExpect(status().isNoContent());

        // Segundo delete: conflito 409 REVIEW_ALREADY_REMOVED
        mockMvc.perform(delete("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("REVIEW_ALREADY_REMOVED"));
    }

    @Test
    @DisplayName("17. DELETE /api/v1/reviews/{id} rejeitado com 401 Unauthorized sem token")
    void shouldRejectDeleteWhenUnauthenticated() throws Exception {
        mockMvc.perform(delete("/api/v1/reviews/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("18. DELETE /api/v1/reviews/{id} rejeitado com 404 e REVIEW_NOT_FOUND para review inexistente")
    void shouldRejectDeleteWhenReviewNotFound() throws Exception {
        TestUser user = registerUser("del_not_found");
        mockMvc.perform(delete("/api/v1/reviews/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    @Test
    @DisplayName("19. Regressão Feed V1 & Feed V2: review removida deixa de aparecer na timeline e no ranking")
    void shouldExcludeRemovedReviewFromFeedV1AndFeedV2() throws Exception {
        TestUser follower = registerUser("follower_reg");
        TestUser author = registerUser("author_reg");
        Place place = createPlace();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        // Follower segue autor
        userFollowRepository.follow(follower.userId(), author.userId());

        // Autor cria review pública
        UUID reviewId = createReviewViaHttp(author, place.getId(),
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("5.0"), "Top")),
                "Review excelente no feed", false, "PUBLIC");

        // 1. Confirma que aparece no Feed V1
        mockMvc.perform(get("/api/v1/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + follower.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + reviewId + "')]").exists());

        // 2. Confirma que aparece no Feed V2
        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + follower.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + reviewId + "')]").exists());

        // 3. Executa Soft Delete pelo autor
        mockMvc.perform(delete("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken()))
                .andExpect(status().isNoContent());

        // 4. Confirma que desapareceu do Feed V1
        mockMvc.perform(get("/api/v1/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + follower.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + reviewId + "')]").doesNotExist());

        // 5. Confirma que desapareceu do Feed V2
        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + follower.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + reviewId + "')]").doesNotExist());
    }
}
