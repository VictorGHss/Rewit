package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.UserFollowRepository;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Review;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.report.CreateReportRequest;
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

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração RESTful / MockMvc - Denúncias Comunitárias de Reviews (Step 19.0 / Seção 26)")
class ReportControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @Autowired
    private PlaceRepository placeRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private record TestUser(String accessToken, UUID userId, String handle) {}

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
        return new TestUser(token, userId, handle);
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

    private Review createReview(UUID authorId, Place place, String visibility, ReviewStatus status) {
        Review review = new Review(
                null,
                authorId,
                place.getId(),
                "Texto avaliativo para testes de API de denúncias",
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
        return reviewRepository.save(review);
    }

    @Test
    @DisplayName("1. Retorna 401 Unauthorized para requisição sem token JWT")
    void createReport_withoutJwt_shouldReturn401() throws Exception {
        CreateReportRequest request = new CreateReportRequest(UUID.randomUUID(), ReportReason.SPAM, "Spam");

        mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("2. Retorna 201 Created para denúncia válida, sem expor identidade do denunciante")
    void createReport_valid_shouldReturn201WithoutReporterIdentity() throws Exception {
        TestUser author = registerUser("autha");
        TestUser reporter = registerUser("repA");
        Place place = createPlace();
        Review review = createReview(author.userId(), place, "PUBLIC", ReviewStatus.ACTIVE);

        CreateReportRequest request = new CreateReportRequest(review.getId(), ReportReason.SPAM, "Conteúdo inadequado e promocional");

        mockMvc.perform(post("/api/v1/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reporter.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.reviewId").value(review.getId().toString()))
                .andExpect(jsonPath("$.reason").value("SPAM"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                // Garante que a identidade do denunciante NUNCA é exposta (anti-vazamento / privacidade)
                .andExpect(jsonPath("$.reporterUserId").doesNotExist())
                .andExpect(jsonPath("$.reporter").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    @DisplayName("3. Retorna 404 REVIEW_NOT_FOUND para Review inexistente")
    void createReport_nonExistentReview_shouldReturn404() throws Exception {
        TestUser reporter = registerUser("repB");
        CreateReportRequest request = new CreateReportRequest(UUID.randomUUID(), ReportReason.FRAUD, null);

        mockMvc.perform(post("/api/v1/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reporter.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    @Test
    @DisplayName("4. Retorna 400 SELF_REPORT_FORBIDDEN quando o autor tenta denunciar a própria Review")
    void createReport_selfReport_shouldReturn400() throws Exception {
        TestUser author = registerUser("authb");
        Place place = createPlace();
        Review review = createReview(author.userId(), place, "PUBLIC", ReviewStatus.ACTIVE);

        CreateReportRequest request = new CreateReportRequest(review.getId(), ReportReason.SPAM, "Minha própria review");

        mockMvc.perform(post("/api/v1/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_REPORT_FORBIDDEN"));
    }

    @Test
    @DisplayName("5. Retorna 403 FORBIDDEN para Review PRIVATE denunciada por terceiro")
    void createReport_privateReview_shouldReturn403() throws Exception {
        TestUser author = registerUser("authc");
        TestUser reporter = registerUser("repC");
        Place place = createPlace();
        Review review = createReview(author.userId(), place, "PRIVATE", ReviewStatus.ACTIVE);

        CreateReportRequest request = new CreateReportRequest(review.getId(), ReportReason.HARASSMENT, null);

        mockMvc.perform(post("/api/v1/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reporter.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("6. Retorna 403 FORBIDDEN para Review FOLLOWERS quando usuário não é seguidor")
    void createReport_followersReviewNotFollowing_shouldReturn403() throws Exception {
        TestUser author = registerUser("authd");
        TestUser reporter = registerUser("repD");
        Place place = createPlace();
        Review review = createReview(author.userId(), place, "FOLLOWERS", ReviewStatus.ACTIVE);

        CreateReportRequest request = new CreateReportRequest(review.getId(), ReportReason.HATE_SPEECH, null);

        mockMvc.perform(post("/api/v1/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reporter.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("7. Retorna 201 Created para Review FOLLOWERS quando usuário segue o autor")
    void createReport_followersReviewFollowing_shouldReturn201() throws Exception {
        TestUser author = registerUser("authe");
        TestUser reporter = registerUser("repE");
        Place place = createPlace();
        Review review = createReview(author.userId(), place, "FOLLOWERS", ReviewStatus.ACTIVE);

        userFollowRepository.follow(reporter.userId(), author.userId());

        CreateReportRequest request = new CreateReportRequest(review.getId(), ReportReason.INAPPROPRIATE_CONTENT, null);

        mockMvc.perform(post("/api/v1/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reporter.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reason").value("INAPPROPRIATE_CONTENT"));
    }

    @Test
    @DisplayName("8. Retorna 400 Bad Request para motivo inválido")
    void createReport_invalidReason_shouldReturn400() throws Exception {
        TestUser reporter = registerUser("repF");

        String payload = """
        {
            "reviewId": "%s",
            "reason": "INVALIDO_XYZ"
        }
        """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reporter.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("9. Retorna 400 Bad Request quando detail > 500 caracteres")
    void createReport_detailExceeding500_shouldReturn400() throws Exception {
        TestUser author = registerUser("authg");
        TestUser reporter = registerUser("repG");
        Place place = createPlace();
        Review review = createReview(author.userId(), place, "PUBLIC", ReviewStatus.ACTIVE);

        CreateReportRequest request = new CreateReportRequest(review.getId(), ReportReason.SPAM, "X".repeat(501));

        mockMvc.perform(post("/api/v1/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reporter.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("10. Idempotência: envio repetido mesmo com motivo e detalhe divergentes retorna 200 OK com o report original")
    void createReport_idempotentRepeat_shouldReturn200Ok() throws Exception {
        TestUser author = registerUser("authh");
        TestUser reporter = registerUser("repH");
        Place place = createPlace();
        Review review = createReview(author.userId(), place, "PUBLIC", ReviewStatus.ACTIVE);

        CreateReportRequest initialRequest = new CreateReportRequest(review.getId(), ReportReason.SPAM, "Primeiro envio");

        // 1ª chamada -> 201 Created
        MvcResult firstResult = mockMvc.perform(post("/api/v1/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reporter.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initialRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String reportId = objectMapper.readTree(firstResult.getResponse().getContentAsString()).get("id").asText();

        // 2ª chamada -> mesmo usuário, mesma review, mas motivo e detalhe divergentes -> 200 OK com dados originais preservados
        CreateReportRequest divergentRequest = new CreateReportRequest(review.getId(), ReportReason.FRAUD, "Segundo envio diferente");

        mockMvc.perform(post("/api/v1/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reporter.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(divergentRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reportId))
                .andExpect(jsonPath("$.reason").value("SPAM"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("11. Anti-IDOR: mesmo se o cliente passar reporterUserId no JSON, o sistema ignora e usa o JWT")
    void createReport_antiIdor_ignoresBodyReporterUserId() throws Exception {
        TestUser author = registerUser("authi");
        TestUser realReporter = registerUser("repReal");
        UUID fakeReporterId = UUID.randomUUID();
        Place place = createPlace();
        Review review = createReview(author.userId(), place, "PUBLIC", ReviewStatus.ACTIVE);

        String payload = """
        {
            "reviewId": "%s",
            "reason": "SPAM",
            "reporterUserId": "%s"
        }
        """.formatted(review.getId(), fakeReporterId);

        mockMvc.perform(post("/api/v1/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + realReporter.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reporterUserId").doesNotExist());
    }

    @Test
    @DisplayName("12. Limiar de 3 denúncias: transiciona Review para UNDER_REVIEW e rejeita a 4ª denúncia com 404")
    void createReport_thresholdBehavior_transitionsToUnderReviewAndRejectsFourthReport() throws Exception {
        TestUser author = registerUser("authj");
        TestUser rep1 = registerUser("th_rep1");
        TestUser rep2 = registerUser("th_rep2");
        TestUser rep3 = registerUser("th_rep3");
        TestUser rep4 = registerUser("th_rep4");

        Place place = createPlace();
        Review review = createReview(author.userId(), place, "PUBLIC", ReviewStatus.ACTIVE);

        // 1ª denúncia -> 201 Created, Review continua ACTIVE
        mockMvc.perform(post("/api/v1/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + rep1.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReportRequest(review.getId(), ReportReason.SPAM, null))))
                .andExpect(status().isCreated());
        assertEquals(ReviewStatus.ACTIVE, reviewRepository.findById(review.getId()).orElseThrow().getStatus());

        // 2ª denúncia -> 201 Created, Review continua ACTIVE
        mockMvc.perform(post("/api/v1/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + rep2.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReportRequest(review.getId(), ReportReason.HARASSMENT, null))))
                .andExpect(status().isCreated());
        assertEquals(ReviewStatus.ACTIVE, reviewRepository.findById(review.getId()).orElseThrow().getStatus());

        // 3ª denúncia -> 201 Created, Review transiciona para UNDER_REVIEW
        mockMvc.perform(post("/api/v1/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + rep3.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReportRequest(review.getId(), ReportReason.FRAUD, null))))
                .andExpect(status().isCreated());
        assertEquals(ReviewStatus.UNDER_REVIEW, reviewRepository.findById(review.getId()).orElseThrow().getStatus());

        // 4ª denúncia em Review UNDER_REVIEW -> deve retornar 404 REVIEW_NOT_FOUND
        mockMvc.perform(post("/api/v1/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + rep4.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReportRequest(review.getId(), ReportReason.SPAM, null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }
}
