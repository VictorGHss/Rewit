package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.ReviewReactionRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.ReviewTargetRepository;
import com.rewit.application.port.UserFollowRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewTarget;
import com.rewit.domain.model.User;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração de API / MockMvc - Validação de Utilidade (Helpful) (Step 16.0)")
class ReviewHelpfulControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ReviewReactionRepository reviewReactionRepository;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    @Autowired
    private ReviewTargetRepository reviewTargetRepository;

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
                "Restaurante API " + suffix,
                "restaurante-api-" + suffix,
                "RESTAURANTE",
                "Descrição",
                "Rua API, 100",
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

    private RateableTarget createTarget(Place place) {
        RateableTarget target = new RateableTarget(UUID.randomUUID(), TargetType.PLACE);
        return rateableTargetRepository.save(target);
    }

    private User createDbUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User(null, "user-" + suffix + "@rewit.com", "hash123", AuthProvider.LOCAL, null);
        User savedUser = userRepository.save(user);

        Profile profile = new Profile(UUID.randomUUID(), savedUser.getId(), "handle_" + suffix, "Nome " + suffix, "Bio", null);
        profileRepository.save(profile);

        return savedUser;
    }

    private Review createReview(UUID authorId, Place place, RateableTarget target, String visibility, ReviewStatus status) {
        UUID revId = UUID.randomUUID();
        Review review = new Review(
                revId,
                authorId,
                place.getId(),
                "Avaliação para testes de API com Helpful.",
                false,
                false,
                status,
                visibility,
                -23.5505,
                -46.6333,
                10.0,
                Instant.now(),
                Instant.now()
        );
        review.addTarget(new ReviewTarget(null, revId, target.getId(), BigDecimal.valueOf(4.5), "Muito bom"));
        Review saved = reviewRepository.save(review);
        reviewTargetRepository.save(review.getTargets().get(0));
        return saved;
    }

    @Test
    @DisplayName("1. Retorna 401 Unauthorized sem token JWT no POST e DELETE")
    void shouldReturn401WhenNoJwtProvided() throws Exception {
        UUID reviewId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/reviews/{id}/helpful", reviewId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/v1/reviews/{id}/helpful", reviewId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("2. Retorna 404 Not Found quando a Review não existe")
    void shouldReturn404WhenReviewDoesNotExist() throws Exception {
        TestUser voter = registerUser("voter404");
        UUID nonExistentReviewId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/reviews/{id}/helpful", nonExistentReviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + voter.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/reviews/{id}/helpful", nonExistentReviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + voter.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    @Test
    @DisplayName("3. Retorna 400 Bad Request ao tentar marcar a própria Review como útil (SELF_HELPFUL_FORBIDDEN)")
    void shouldReturn400OnSelfHelpful() throws Exception {
        TestUser author = registerUser("authorSelf");
        Place place = createPlace();
        RateableTarget target = createTarget(place);
        Review review = createReview(author.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE);

        mockMvc.perform(post("/api/v1/reviews/{id}/helpful", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_HELPFUL_FORBIDDEN"));
    }

    @Test
    @DisplayName("4. Retorna 403 Forbidden para Review FOLLOWERS quando o requester não é seguidor")
    void shouldReturn403OnFollowersReviewWhenNotFollowing() throws Exception {
        TestUser author = registerUser("authorFol");
        TestUser nonFollower = registerUser("nonFollower");
        Place place = createPlace();
        RateableTarget target = createTarget(place);
        Review review = createReview(author.userId(), place, target, "FOLLOWERS", ReviewStatus.ACTIVE);

        mockMvc.perform(post("/api/v1/reviews/{id}/helpful", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + nonFollower.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("5. Sucesso para seguidor em Review FOLLOWERS")
    void shouldAllowHelpfulForFollowerOnFollowersReview() throws Exception {
        TestUser author = registerUser("authorFol2");
        TestUser follower = registerUser("follower");
        Place place = createPlace();
        RateableTarget target = createTarget(place);
        Review review = createReview(author.userId(), place, target, "FOLLOWERS", ReviewStatus.ACTIVE);

        // Seguir o autor
        userFollowRepository.follow(follower.userId(), author.userId());

        mockMvc.perform(post("/api/v1/reviews/{id}/helpful", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + follower.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.helpful").value(true))
                .andExpect(jsonPath("$.helpfulCount").value(1));
    }

    @Test
    @DisplayName("6. Retorna 403 Forbidden para Review PRIVATE de terceiro")
    void shouldReturn403OnPrivateReview() throws Exception {
        TestUser author = registerUser("authorPriv");
        TestUser other = registerUser("otherUser");
        Place place = createPlace();
        RateableTarget target = createTarget(place);
        Review review = createReview(author.userId(), place, target, "PRIVATE", ReviewStatus.ACTIVE);

        mockMvc.perform(post("/api/v1/reviews/{id}/helpful", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("7. Retorna 404 para Review inativa (UNDER_REVIEW e REMOVED)")
    void shouldReturn404OnInactiveReview() throws Exception {
        TestUser author = registerUser("authorInact");
        TestUser voter = registerUser("voterInact");
        Place place = createPlace();
        RateableTarget target = createTarget(place);

        Review reviewUnder = createReview(author.userId(), place, target, "PUBLIC", ReviewStatus.UNDER_REVIEW);
        Review reviewRem = createReview(author.userId(), place, target, "PUBLIC", ReviewStatus.REMOVED);

        mockMvc.perform(post("/api/v1/reviews/{id}/helpful", reviewUnder.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + voter.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/reviews/{id}/helpful", reviewRem.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + voter.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    @Test
    @DisplayName("8. POST e DELETE idempotentes retornam 200 OK com contadores consistentes")
    void shouldBeIdempotentOnPostAndDelete() throws Exception {
        TestUser author = registerUser("authorIdem");
        TestUser voter = registerUser("voterIdem");
        Place place = createPlace();
        RateableTarget target = createTarget(place);
        Review review = createReview(author.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE);

        // Primeiro POST -> helpful: true, helpfulCount: 1
        mockMvc.perform(post("/api/v1/reviews/{id}/helpful", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + voter.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.helpful").value(true))
                .andExpect(jsonPath("$.helpfulCount").value(1));

        // Segundo POST (repetido) -> helpful: true, helpfulCount: 1
        mockMvc.perform(post("/api/v1/reviews/{id}/helpful", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + voter.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.helpful").value(true))
                .andExpect(jsonPath("$.helpfulCount").value(1));

        // Primeiro DELETE -> helpful: false, helpfulCount: 0
        mockMvc.perform(delete("/api/v1/reviews/{id}/helpful", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + voter.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.helpful").value(false))
                .andExpect(jsonPath("$.helpfulCount").value(0));

        // Segundo DELETE (repetido) -> helpful: false, helpfulCount: 0
        mockMvc.perform(delete("/api/v1/reviews/{id}/helpful", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + voter.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.helpful").value(false))
                .andExpect(jsonPath("$.helpfulCount").value(0));
    }

    @Test
    @DisplayName("9. Enriquecimento de GET /api/v1/reviews/{id} com helpfulCount e isHelpfulByMe")
    void shouldEnrichGetReviewWithHelpfulData() throws Exception {
        TestUser author = registerUser("authorGet");
        TestUser voter1 = registerUser("voterGet1");
        TestUser voter2 = registerUser("voterGet2");
        Place place = createPlace();
        RateableTarget target = createTarget(place);
        Review review = createReview(author.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE);

        // Voter 1 vota
        reviewReactionRepository.addHelpful(review.getId(), voter1.userId());
        // Voter 2 vota
        reviewReactionRepository.addHelpful(review.getId(), voter2.userId());

        // Requester é voter 1: helpfulCount = 2, isHelpfulByMe = true
        mockMvc.perform(get("/api/v1/reviews/{id}", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + voter1.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(review.getId().toString()))
                .andExpect(jsonPath("$.helpfulCount").value(2))
                .andExpect(jsonPath("$.isHelpfulByMe").value(true));

        // Requester é autor: helpfulCount = 2, isHelpfulByMe = false
        mockMvc.perform(get("/api/v1/reviews/{id}", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.helpfulCount").value(2))
                .andExpect(jsonPath("$.isHelpfulByMe").value(false));

        // Requester terceiro que não votou: helpfulCount = 2, isHelpfulByMe = false
        TestUser nonVoter = registerUser("nonVoterGet");
        mockMvc.perform(get("/api/v1/reviews/{id}", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + nonVoter.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.helpfulCount").value(2))
                .andExpect(jsonPath("$.isHelpfulByMe").value(false));
    }

    @Test
    @DisplayName("10. Enriquecimento da listagem GET /api/v1/targets/{id}/reviews conforme cenário obrigatório (A=3/true, B=7/false, C=0/false)")
    void shouldEnrichReviewListingWithHelpfulCountsAndVoterState() throws Exception {
        TestUser author = registerUser("authorList");
        TestUser requester = registerUser("requesterList");
        Place place = createPlace();
        RateableTarget target = createTarget(place);

        Review reviewA = createReview(author.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE);
        Review reviewB = createReview(author.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE);
        Review reviewC = createReview(author.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE);

        // Review A: 3 Helpfuls (requester + 2 outros)
        reviewReactionRepository.addHelpful(reviewA.getId(), requester.userId());
        reviewReactionRepository.addHelpful(reviewA.getId(), createDbUser().getId());
        reviewReactionRepository.addHelpful(reviewA.getId(), createDbUser().getId());

        // Review B: 7 Helpfuls (7 outros, sem requester)
        for (int i = 0; i < 7; i++) {
            reviewReactionRepository.addHelpful(reviewB.getId(), createDbUser().getId());
        }

        // Review C: 0 Helpfuls

        MvcResult result = mockMvc.perform(get("/api/v1/targets/{id}/reviews", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(3))))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode content = root.get("content");

        for (JsonNode item : content) {
            String itemId = item.get("id").asText();
            if (itemId.equals(reviewA.getId().toString())) {
                assertEquals(3, item.get("helpfulCount").asLong());
                assertEquals(true, item.get("isHelpfulByMe").asBoolean());
            } else if (itemId.equals(reviewB.getId().toString())) {
                assertEquals(7, item.get("helpfulCount").asLong());
                assertEquals(false, item.get("isHelpfulByMe").asBoolean());
            } else if (itemId.equals(reviewC.getId().toString())) {
                assertEquals(0, item.get("helpfulCount").asLong());
                assertEquals(false, item.get("isHelpfulByMe").asBoolean());
            }
        }
    }
}
