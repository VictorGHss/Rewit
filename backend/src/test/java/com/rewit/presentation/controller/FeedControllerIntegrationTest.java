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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração de API / MockMvc - Feed Social V1 (Step 17.0)")
class FeedControllerIntegrationTest {

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
                "Restaurante API Feed " + suffix,
                "restaurante-api-feed-" + suffix,
                "RESTAURANTE",
                "Descrição",
                "Rua API Feed, 100",
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

    private RateableTarget createTarget() {
        RateableTarget target = new RateableTarget(UUID.randomUUID(), TargetType.PLACE);
        return rateableTargetRepository.save(target);
    }

    private Review createReview(UUID authorId, Place place, RateableTarget target, String visibility, ReviewStatus status, boolean isAnonymous) {
        UUID revId = UUID.randomUUID();
        Review review = new Review(
                revId,
                authorId,
                place.getId(),
                "Avaliação para testes de API Feed.",
                isAnonymous,
                false,
                status,
                visibility,
                -23.5505,
                -46.6333,
                10.0,
                Instant.now(),
                Instant.now()
        );
        ReviewTarget rt = new ReviewTarget(null, revId, target.getId(), BigDecimal.valueOf(4.5), "Muito bom");
        review.addTarget(rt);
        Review saved = reviewRepository.save(review);
        reviewTargetRepository.save(rt);
        return saved;
    }

    @Test
    @DisplayName("1. Retorna 401 Unauthorized sem token JWT")
    void shouldReturn401WhenNoJwtProvided() throws Exception {
        mockMvc.perform(get("/api/v1/feed"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("2. Retorna feed vazio 200 OK quando o usuário não segue ninguém")
    void shouldReturnEmptyFeedWhenFollowingNoOne() throws Exception {
        TestUser requester = registerUser("feedEmpty");

        mockMvc.perform(get("/api/v1/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.isLast").value(true));
    }

    @Test
    @DisplayName("3. Retorna avaliações de usuários seguidos com conteúdo completo e metadados")
    void shouldReturnFeedWithContentFromFollowedUsers() throws Exception {
        TestUser requester = registerUser("feedReq");
        TestUser followed = registerUser("feedFol");
        TestUser nonFollowed = registerUser("feedNonFol");

        Place place = createPlace();
        RateableTarget target = createTarget();

        // Seguir o usuário
        userFollowRepository.follow(requester.userId(), followed.userId());

        // Criar reviews
        Review revFollowed = createReview(followed.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE, false);
        Review revNonFollowed = createReview(nonFollowed.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE, false);

        mockMvc.perform(get("/api/v1/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(revFollowed.getId().toString()))
                .andExpect(jsonPath("$.content[0].author.handle").value(followed.handle()))
                .andExpect(jsonPath("$.content[0].targets", hasSize(1)))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(revNonFollowed.getId().toString()))));
    }

    @Test
    @DisplayName("4. Exclui Reviews PRIVATE e Reviews inativas do Feed")
    void shouldExcludePrivateAndInactiveReviewsFromFeed() throws Exception {
        TestUser requester = registerUser("feedPrivReq");
        TestUser followed = registerUser("feedPrivFol");

        Place place = createPlace();
        RateableTarget target = createTarget();

        userFollowRepository.follow(requester.userId(), followed.userId());

        Review revPublic = createReview(followed.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE, false);
        Review revPrivate = createReview(followed.userId(), place, target, "PRIVATE", ReviewStatus.ACTIVE, false);
        Review revRemoved = createReview(followed.userId(), place, target, "PUBLIC", ReviewStatus.REMOVED, false);
        Review revUnderReview = createReview(followed.userId(), place, target, "PUBLIC", ReviewStatus.UNDER_REVIEW, false);

        mockMvc.perform(get("/api/v1/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(revPublic.getId().toString()))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(revPrivate.getId().toString()))))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(revRemoved.getId().toString()))))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(revUnderReview.getId().toString()))));
    }

    @Test
    @DisplayName("5. Rejeita parâmetros de paginação inválidos com 400 Bad Request")
    void shouldRejectInvalidPaginationParameters() throws Exception {
        TestUser requester = registerUser("feedPageErr");

        // size > 50
        mockMvc.perform(get("/api/v1/feed")
                        .param("size", "51")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAGE_SIZE_EXCEEDED"));

        // page < 0
        mockMvc.perform(get("/api/v1/feed")
                        .param("page", "-1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));

        // size <= 0
        mockMvc.perform(get("/api/v1/feed")
                        .param("size", "0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SIZE"));

        // sort != newest
        mockMvc.perform(get("/api/v1/feed")
                        .param("sort", "rating")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT"));
    }

    @Test
    @DisplayName("6. Preserva anonimização e metadados de Helpful no Feed")
    void shouldPreserveAnonymizationAndHelpfulInFeed() throws Exception {
        TestUser requester = registerUser("feedAnonReq");
        TestUser followed = registerUser("feedAnonFol");

        Place place = createPlace();
        RateableTarget target = createTarget();

        userFollowRepository.follow(requester.userId(), followed.userId());

        Review reviewAnon = createReview(followed.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE, true);
        reviewReactionRepository.addHelpful(reviewAnon.getId(), requester.userId());

        mockMvc.perform(get("/api/v1/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].isAnonymous").value(true))
                .andExpect(jsonPath("$.content[0].author.displayName").value("Anônimo"))
                .andExpect(jsonPath("$.content[0].author.handle").doesNotExist())
                .andExpect(jsonPath("$.content[0].helpfulCount").value(1))
                .andExpect(jsonPath("$.content[0].isHelpfulByMe").value(true));
    }

    @Test
    @DisplayName("7. Exclui avaliações publicadas pelo próprio requester do seu próprio Feed")
    void shouldExcludeRequestersOwnReviewsFromFeed() throws Exception {
        TestUser requester = registerUser("feedSelfReq");
        TestUser followed = registerUser("feedSelfFol");

        Place place = createPlace();
        RateableTarget target = createTarget();

        userFollowRepository.follow(requester.userId(), followed.userId());

        Review revFollowed = createReview(followed.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE, false);
        Review revOwn = createReview(requester.userId(), place, target, "PUBLIC", ReviewStatus.ACTIVE, false);

        mockMvc.perform(get("/api/v1/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(revFollowed.getId().toString()))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(revOwn.getId().toString()))));
    }
}
