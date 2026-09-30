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
@DisplayName("Testes de Integração de API / MockMvc - Perfil Público do Usuário (Step 18.0)")
class UserControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

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
                "Restaurante API User " + suffix,
                "restaurante-api-user-" + suffix,
                "RESTAURANTE",
                "Descrição",
                "Rua API User, 100",
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

    private Review createReview(UUID authorId, Place place, RateableTarget target,
                                 ReviewStatus status, String visibility) {
        UUID reviewId = UUID.randomUUID();
        Review review = new Review(
                reviewId,
                authorId,
                place.getId(),
                "Experiência da avaliação",
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
        ReviewTarget reviewTarget = new ReviewTarget(UUID.randomUUID(), reviewId, target.getId(), BigDecimal.valueOf(4.5), "Nota");
        review.addTarget(reviewTarget);
        Review saved = reviewRepository.save(review);
        reviewTargetRepository.save(reviewTarget);
        return saved;
    }

    @Test
    @DisplayName("1. Deve retornar 401 Unauthorized quando requisição não possui JWT")
    void shouldReturn401WhenNoJwtProvided() throws Exception {
        UUID randomUserId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/users/{id}", randomUserId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("2. Deve retornar 404 USER_NOT_FOUND para UUID inexistente")
    void shouldReturn404WhenUserNotFound() throws Exception {
        TestUser requester = registerUser("req");
        UUID randomUserId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/users/{id}", randomUserId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("USER_NOT_FOUND")))
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    @DisplayName("3. Deve retornar 200 OK com dados públicos, stats factuais e isFollowing para perfil existente")
    void shouldReturn200AndAccuratePublicProfileWithStats() throws Exception {
        TestUser targetUser = registerUser("target");
        TestUser requester = registerUser("requester");
        TestUser thirdUser = registerUser("third");

        Place place = createPlace();
        RateableTarget target = createTarget();

        // 2 Reviews ACTIVE do targetUser
        Review r1 = createReview(targetUser.userId(), place, target, ReviewStatus.ACTIVE, "PUBLIC");
        createReview(targetUser.userId(), place, target, ReviewStatus.ACTIVE, "FOLLOWERS");

        // 1 Review REMOVED (não deve entrar no totalReviews)
        createReview(targetUser.userId(), place, target, ReviewStatus.REMOVED, "PUBLIC");

        // Requester segue targetUser -> followersCount = 1, isFollowing = true
        userFollowRepository.follow(requester.userId(), targetUser.userId());

        // targetUser segue thirdUser -> followingCount = 1
        userFollowRepository.follow(targetUser.userId(), thirdUser.userId());

        // Requester e Third votam HELPFUL em r1 -> helpfulVotesReceived = 2
        reviewReactionRepository.addHelpful(r1.getId(), requester.userId());
        reviewReactionRepository.addHelpful(r1.getId(), thirdUser.userId());

        mockMvc.perform(get("/api/v1/users/{id}", targetUser.userId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(targetUser.userId().toString())))
                .andExpect(jsonPath("$.handle", is(targetUser.handle())))
                .andExpect(jsonPath("$.displayName", is(targetUser.displayName())))
                .andExpect(jsonPath("$.isFollowing", is(true)))
                .andExpect(jsonPath("$.stats.totalReviews", is(2)))
                .andExpect(jsonPath("$.stats.verifiedReviewsCount", is(0)))
                .andExpect(jsonPath("$.stats.followersCount", is(1)))
                .andExpect(jsonPath("$.stats.followingCount", is(1)))
                .andExpect(jsonPath("$.stats.helpfulVotesReceived", is(2)))
                // Garantir ausência total de PII, credenciais e votantes
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.providerUserId").doesNotExist())
                .andExpect(jsonPath("$.authProvider").doesNotExist())
                .andExpect(jsonPath("$.tokens").doesNotExist())
                .andExpect(jsonPath("$.sessions").doesNotExist())
                .andExpect(jsonPath("$.coordinates").doesNotExist())
                .andExpect(jsonPath("$.latitude").doesNotExist())
                .andExpect(jsonPath("$.longitude").doesNotExist())
                .andExpect(jsonPath("$.voters").doesNotExist())
                .andExpect(jsonPath("$.reviews").doesNotExist())
                .andExpect(jsonPath("$.followers").doesNotExist())
                .andExpect(jsonPath("$.following").doesNotExist());
    }

    @Test
    @DisplayName("4. Deve retornar isFollowing = false quando o requester consulta seu próprio perfil")
    void shouldReturnFalseForIsFollowingWhenRequesterViewsOwnProfile() throws Exception {
        TestUser user = registerUser("self");

        mockMvc.perform(get("/api/v1/users/{id}", user.userId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(user.userId().toString())))
                .andExpect(jsonPath("$.isFollowing", is(false)))
                .andExpect(jsonPath("$.stats.totalReviews", is(0)))
                .andExpect(jsonPath("$.stats.followersCount", is(0)));
    }

    @Test
    @DisplayName("5. Deve retornar isFollowing = false quando o requester não segue o usuário alvo")
    void shouldReturnFalseForIsFollowingWhenNotFollowing() throws Exception {
        TestUser targetUser = registerUser("targetnot");
        TestUser requester = registerUser("reqnot");

        mockMvc.perform(get("/api/v1/users/{id}", targetUser.userId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(targetUser.userId().toString())))
                .andExpect(jsonPath("$.isFollowing", is(false)));
    }

    @Test
    @DisplayName("6. Deve retornar 404 USER_NOT_FOUND para usuário soft-deleted")
    void shouldReturn404WhenUserIsSoftDeleted() throws Exception {
        TestUser targetUser = registerUser("softdel");
        TestUser requester = registerUser("reqsoft");

        User user = userRepository.findById(targetUser.userId()).orElseThrow();
        user.softDelete();
        userRepository.save(user);

        mockMvc.perform(get("/api/v1/users/{id}", targetUser.userId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("USER_NOT_FOUND")))
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    @DisplayName("7. Deve incluir Review PRIVATE em totalReviews sem expor conteúdo textual ou dados individuais via HTTP")
    void shouldIncludePrivateReviewsInCountWithoutExposingContentViaMockMvc() throws Exception {
        TestUser targetUser = registerUser("targetprivapi");
        TestUser requester = registerUser("reqprivapi");

        Place place = createPlace();
        RateableTarget target = createTarget();

        // Cria uma review estritamente PRIVATE
        createReview(targetUser.userId(), place, target, ReviewStatus.ACTIVE, "PRIVATE");

        mockMvc.perform(get("/api/v1/users/{id}", targetUser.userId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(targetUser.userId().toString())))
                .andExpect(jsonPath("$.stats.totalReviews", is(1)))
                .andExpect(jsonPath("$.stats.verifiedReviewsCount", is(0)))
                .andExpect(jsonPath("$.reviews").doesNotExist())
                .andExpect(jsonPath("$.privateReviews").doesNotExist())
                .andExpect(jsonPath("$.experienceText").doesNotExist());
    }

    @Test
    @DisplayName("8. Deve garantir que o requester é derivado exclusivamente do token JWT e não pode ser forjado via query param")
    void shouldEnsureRequesterIsExclusivelyDerivedFromJwtAndCannotBeForged() throws Exception {
        TestUser targetUser = registerUser("targetidor");
        TestUser actualRequester = registerUser("actualreq");
        TestUser otherFollower = registerUser("otherfol");

        // Outro usuário segue o target, mas actualRequester NÃO segue
        userFollowRepository.follow(otherFollower.userId(), targetUser.userId());

        // Tentativa de passar query parameter malicioso querendo se passar por otherFollower
        mockMvc.perform(get("/api/v1/users/{id}", targetUser.userId())
                        .param("requesterId", otherFollower.userId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + actualRequester.accessToken())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(targetUser.userId().toString())))
                .andExpect(jsonPath("$.isFollowing", is(false)));
    }
}
