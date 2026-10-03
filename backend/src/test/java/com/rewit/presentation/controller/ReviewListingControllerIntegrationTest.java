package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.domain.enums.TargetType;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração de API / MockMvc - Listagem e Paginação de Reviews (Step 14.0)")
class ReviewListingControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

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

    private RateableTarget createRateableTarget(TargetType type) {
        RateableTarget target = new RateableTarget(UUID.randomUUID(), type);
        return rateableTargetRepository.save(target);
    }

    private void createReview(TestUser user, UUID targetId, BigDecimal rating, String experienceText, boolean isAnonymous, String visibility) throws Exception {
        CreateReviewRequest req = new CreateReviewRequest(
                null,
                experienceText,
                isAnonymous,
                visibility,
                List.of(new CreateReviewTargetRequest(targetId, rating, "Comentário"))
        );

        mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("1. 401 Unauthorized sem JWT para GET /api/v1/targets/{id}/reviews")
    void shouldReturn401WithoutJwtForTargetReviews() throws Exception {
        UUID targetId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/targets/{id}/reviews", targetId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("2. 401 Unauthorized sem JWT para GET /api/v1/me/reviews")
    void shouldReturn401WithoutJwtForMeReviews() throws Exception {
        mockMvc.perform(get("/api/v1/me/reviews"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("3. 404 Not Found para target inexistente com RFC 7807")
    void shouldReturn404ForNonexistentTarget() throws Exception {
        TestUser user = registerUser("user_not_found");
        UUID nonexistentTargetId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/targets/{id}/reviews", nonexistentTargetId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RATEABLE_TARGET_NOT_FOUND"));
    }

    @Test
    @DisplayName("4. 200 OK para target sem avaliações (envelope vazio com totalElements 0)")
    void shouldReturn200EmptyEnvelopeForTargetWithoutReviews() throws Exception {
        TestUser user = registerUser("user_empty");
        RateableTarget target = createRateableTarget(TargetType.PRODUCT);

        mockMvc.perform(get("/api/v1/targets/{id}/reviews", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.isLast").value(true));
    }

    @Test
    @DisplayName("5. 200 OK para página válida com envelope esperado")
    void shouldReturnValidEnvelopeWithContent() throws Exception {
        TestUser author = registerUser("author_valid");
        TestUser requester = registerUser("requester_valid");
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        createReview(author, target.getId(), new BigDecimal("4.5"), "Ambiente incrível", false, "PUBLIC");

        mockMvc.perform(get("/api/v1/targets/{id}/reviews", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken())
                        .param("page", "0")
                        .param("size", "10")
                        .param("sort", "newest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.isLast").value(true))
                .andExpect(jsonPath("$.content[0].author.handle").value(author.handle()))
                .andExpect(jsonPath("$.content[0].author.displayName").value(author.displayName()))
                .andExpect(jsonPath("$.content[0].author.isAnonymous").value(false))
                .andExpect(jsonPath("$.content[0].targets[0].targetId").value(target.getId().toString()))
                .andExpect(jsonPath("$.content[0].targets[0].rating").value(4.5));
    }

    @Test
    @DisplayName("6. Validações de query params (page < 0, size <= 0, size > 50, sort inválido) retornam 400 com RFC 7807")
    void shouldReturn400ForInvalidQueryParams() throws Exception {
        TestUser user = registerUser("user_invalid_params");
        RateableTarget target = createRateableTarget(TargetType.SERVICE);

        // page < 0
        mockMvc.perform(get("/api/v1/targets/{id}/reviews", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));

        // size <= 0
        mockMvc.perform(get("/api/v1/targets/{id}/reviews", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SIZE"));

        // size > 50
        mockMvc.perform(get("/api/v1/targets/{id}/reviews", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAGE_SIZE_EXCEEDED"));

        // sort inválido
        mockMvc.perform(get("/api/v1/targets/{id}/reviews", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .param("sort", "unsupported_sort"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT"));
    }

    @Test
    @DisplayName("7. Anonimização na API: review anônima oculta dados do autor para terceiros")
    void shouldMaskAnonymousReviewInListingApi() throws Exception {
        TestUser author = registerUser("anon_author");
        TestUser requester = registerUser("anon_reader");
        RateableTarget target = createRateableTarget(TargetType.EVENT);

        createReview(author, target.getId(), new BigDecimal("3.5"), "Avaliação em sigilo", true, "PUBLIC");

        mockMvc.perform(get("/api/v1/targets/{id}/reviews", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].isAnonymous").value(true))
                .andExpect(jsonPath("$.content[0].author.isAnonymous").value(true))
                .andExpect(jsonPath("$.content[0].author.displayName").value("Anônimo"))
                .andExpect(jsonPath("$.content[0].author.id").doesNotExist())
                .andExpect(jsonPath("$.content[0].author.handle").doesNotExist());
    }

    @Test
    @DisplayName("8. Isolamento de usuário em /me/reviews com token JWT")
    void shouldIsolateUserReviewsInMeReviewsEndpoint() throws Exception {
        TestUser userA = registerUser("user_a");
        TestUser userB = registerUser("user_b");
        RateableTarget target = createRateableTarget(TargetType.PRODUCT);

        createReview(userA, target.getId(), new BigDecimal("4.0"), "Review do User A", false, "PUBLIC");
        createReview(userB, target.getId(), new BigDecimal("5.0"), "Review do User B", false, "PUBLIC");

        // User A consulta /me/reviews -> apenas sua avaliação
        mockMvc.perform(get("/api/v1/me/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].author.handle").value(userA.handle()))
                .andExpect(jsonPath("$.content[0].experienceText").value("Review do User A"));

        // User B consulta /me/reviews -> apenas sua avaliação
        mockMvc.perform(get("/api/v1/me/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userB.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].author.handle").value(userB.handle()))
                .andExpect(jsonPath("$.content[0].experienceText").value("Review do User B"));
    }
}
