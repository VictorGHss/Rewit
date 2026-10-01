package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.report.ReportDtos.CreateReportCommand;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.service.ReportService;
import com.rewit.application.service.ReviewService;
import com.rewit.domain.enums.ModerationAction;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.RateableTarget;
import com.rewit.presentation.dto.auth.LoginRequest;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.admin.AdminModerationDtos.ModerateReviewRequest;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes de integração MockMvc para os endpoints de moderação administrativa (Step 26.3).
 * Cobre segurança (401/403), fluxos felizes e fluxos de erro (404/409).
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração da API Administrativa de Moderação (Step 26.3)")
class AdminModerationControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    // -------------------------------------------------------------------------
    // Helpers de infraestrutura de teste
    // -------------------------------------------------------------------------

    private record TestUser(String accessToken, UUID userId, String email, String password) {}

    /**
     * Registra um usuário via HTTP e retorna credenciais e token de acesso.
     */
    private TestUser registerUser(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "." + suffix + "@rewit.com";
        String password = "Password@" + suffix;
        String handle = "@" + prefix + "_" + suffix;
        String displayName = "User " + suffix;

        RegisterRequest req = new RegisterRequest(email, password, handle, displayName);
        MvcResult res = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        String token = node.get("accessToken").asText();
        UUID id = UUID.fromString(node.get("user").get("id").asText());
        return new TestUser(token, id, email, password);
    }

    /**
     * Promove um usuário para MODERATOR via JDBC e faz login para obter token com role atualizada.
     */
    private TestUser promoteToModerator(TestUser user) throws Exception {
        jdbcTemplate.update("UPDATE users SET role = 'MODERATOR' WHERE id = ?", user.userId());
        return loginAndRefreshToken(user);
    }

    /**
     * Promove um usuário para ADMIN via JDBC e faz login para obter token com role atualizada.
     */
    private TestUser promoteToAdmin(TestUser user) throws Exception {
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.userId());
        return loginAndRefreshToken(user);
    }

    /**
     * Faz login e retorna um novo TestUser com o token atualizado.
     */
    private TestUser loginAndRefreshToken(TestUser user) throws Exception {
        LoginRequest loginReq = new LoginRequest(user.email(), user.password());
        MvcResult loginRes = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = objectMapper.readTree(loginRes.getResponse().getContentAsString());
        String newToken = node.get("accessToken").asText();
        return new TestUser(newToken, user.userId(), user.email(), user.password());
    }

    private RateableTarget createRateableTarget() {
        RateableTarget target = new RateableTarget(UUID.randomUUID(), TargetType.PLACE);
        return rateableTargetRepository.save(target);
    }

    private UUID createReview(UUID authorId, RateableTarget target) {
        return reviewService.createReview(new CreateReviewCommand(
                authorId,
                null,
                "Avaliação para teste de moderação administrativa",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("4.0"), "Bom"))
        )).id();
    }

    private void createReport(UUID reporterUserId, UUID reviewId) {
        reportService.createReport(new CreateReportCommand(
                reporterUserId,
                reviewId,
                ReportReason.SPAM,
                "Conteúdo suspeito para teste"
        ));
    }

    private ModerateReviewRequest removeRequest() {
        return new ModerateReviewRequest(
                ModerationAction.REMOVE_REVIEW,
                "SPAM_CONFIRMED",
                "Avaliação verificada como conteúdo de spam com padrão repetitivo identificado."
        );
    }

    // =========================================================================
    // TESTES DE SEGURANÇA — /api/v1/admin/reports
    // =========================================================================

    @Test
    @DisplayName("1. GET /api/v1/admin/reports sem autenticação deve retornar 401")
    void listReports_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("2. GET /api/v1/admin/reports com usuário USER deve retornar 403")
    void listReports_asUser_returns403() throws Exception {
        TestUser regularUser = registerUser("list_user");

        mockMvc.perform(get("/api/v1/admin/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + regularUser.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("3. POST /api/v1/admin/reviews/{id}/moderate sem autenticação deve retornar 401")
    void moderateReview_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/reviews/" + UUID.randomUUID() + "/moderate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(removeRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("4. POST /api/v1/admin/reviews/{id}/moderate com usuário USER deve retornar 403")
    void moderateReview_asUser_returns403() throws Exception {
        TestUser regularUser = registerUser("mod_user_forbidden");
        TestUser author = registerUser("mod_author_for_403");
        RateableTarget target = createRateableTarget();
        UUID reviewId = createReview(author.userId(), target);

        mockMvc.perform(post("/api/v1/admin/reviews/" + reviewId + "/moderate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + regularUser.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(removeRequest())))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // TESTES DE FLUXO FELIZ — GET /api/v1/admin/reports
    // =========================================================================

    @Test
    @DisplayName("5. GET /api/v1/admin/reports com MODERATOR retorna página vazia quando não há denúncias")
    void listReports_asModerator_returnsEmptyPage() throws Exception {
        TestUser user = registerUser("list_mod_empty");
        TestUser moderator = promoteToModerator(user);

        mockMvc.perform(get("/api/v1/admin/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + moderator.accessToken())
                        .param("size", "20")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("6. GET /api/v1/admin/reports com ADMIN retorna relatórios existentes com dados corretos")
    void listReports_asAdmin_returnsPendingReports() throws Exception {
        TestUser authorUser = registerUser("list_admin_author");
        TestUser reporterUser = registerUser("list_admin_reporter");
        TestUser adminUser = registerUser("list_admin");
        TestUser admin = promoteToAdmin(adminUser);

        RateableTarget target = createRateableTarget();
        UUID reviewId = createReview(authorUser.userId(), target);
        createReport(reporterUser.userId(), reviewId);

        MvcResult result = mockMvc.perform(get("/api/v1/admin/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.accessToken())
                        .param("status", "PENDING")
                        .param("reviewId", reviewId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode content = responseNode.get("content");
        assertTrue(content.size() >= 1, "Deve haver ao menos um report PENDING para a review criada");

        // Verificar estrutura do primeiro item
        JsonNode firstReport = content.get(0);
        assertNotNull(firstReport.get("id"));
        assertEquals(reviewId.toString(), firstReport.get("reviewId").asText());
        assertEquals("SPAM", firstReport.get("reason").asText());
        assertEquals("PENDING", firstReport.get("status").asText());
    }

    @Test
    @DisplayName("7. GET /api/v1/admin/reports suporta filtro por status PENDING")
    void listReports_filterByStatusPending_returnsOnlyPendingReports() throws Exception {
        TestUser moderatorUser = registerUser("filter_status_mod");
        TestUser moderator = promoteToModerator(moderatorUser);

        mockMvc.perform(get("/api/v1/admin/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + moderator.accessToken())
                        .param("status", "PENDING")
                        .param("sort", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.pageNumber").value(0));
    }

    // =========================================================================
    // TESTES DE FLUXO FELIZ — POST /api/v1/admin/reviews/{id}/moderate
    // =========================================================================

    @Test
    @DisplayName("8. POST moderate com MODERATOR remove avaliação ACTIVE com sucesso")
    void moderateReview_asModerator_removeActiveReview_returns200() throws Exception {
        TestUser authorUser = registerUser("mod_remove_author");
        TestUser moderatorUser = registerUser("mod_remove_moderator");
        TestUser moderator = promoteToModerator(moderatorUser);

        RateableTarget target = createRateableTarget();
        UUID reviewId = createReview(authorUser.userId(), target);
        createReport(moderatorUser.userId(), reviewId); // denúncia para criar contexto

        // A denúncia é do próprio moderator; precisa de outro reporter para evitar REPORTER_CANNOT_MODERATE
        TestUser reporterUser = registerUser("mod_remove_reporter");
        UUID reviewId2 = createReview(authorUser.userId(), target);
        createReport(reporterUser.userId(), reviewId2);

        MvcResult result = mockMvc.perform(post("/api/v1/admin/reviews/" + reviewId2 + "/moderate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + moderator.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(removeRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditLogId").isNotEmpty())
                .andExpect(jsonPath("$.reviewId").value(reviewId2.toString()))
                .andExpect(jsonPath("$.action").value("REMOVE_REVIEW"))
                .andExpect(jsonPath("$.previousStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.newStatus").value("REMOVED"))
                .andExpect(jsonPath("$.resolvedReportsCount").value(1))
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("SPAM_CONFIRMED", node.get("reasonCode").asText());
        assertNotNull(node.get("moderatedAt").asText());
    }

    @Test
    @DisplayName("9. POST moderate com ADMIN remove avaliação com sucesso (autorização ADMIN aceita)")
    void moderateReview_asAdmin_removeReview_returns200() throws Exception {
        TestUser authorUser = registerUser("admin_remove_author");
        TestUser reporterUser = registerUser("admin_remove_reporter");
        TestUser adminUser = registerUser("admin_remove_admin");
        TestUser admin = promoteToAdmin(adminUser);

        RateableTarget target = createRateableTarget();
        UUID reviewId = createReview(authorUser.userId(), target);
        createReport(reporterUser.userId(), reviewId);

        mockMvc.perform(post("/api/v1/admin/reviews/" + reviewId + "/moderate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(removeRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("REMOVE_REVIEW"))
                .andExpect(jsonPath("$.newStatus").value("REMOVED"));
    }

    // =========================================================================
    // TESTES DE ERRO — POST /api/v1/admin/reviews/{id}/moderate
    // =========================================================================

    @Test
    @DisplayName("10. POST moderate com reviewId inexistente deve retornar 404")
    void moderateReview_reviewNotFound_returns404() throws Exception {
        TestUser moderatorUser = registerUser("mod_404_moderator");
        TestUser moderator = promoteToModerator(moderatorUser);

        UUID nonExistentId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/admin/reviews/" + nonExistentId + "/moderate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + moderator.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(removeRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    @Test
    @DisplayName("11. POST moderate REMOVE_REVIEW em avaliação já REMOVED deve retornar 409 REVIEW_ALREADY_REMOVED")
    void moderateReview_alreadyRemoved_returns409() throws Exception {
        TestUser authorUser = registerUser("mod_409_author");
        TestUser reporterUser = registerUser("mod_409_reporter");
        TestUser moderatorUser = registerUser("mod_409_moderator");
        TestUser moderator = promoteToModerator(moderatorUser);

        RateableTarget target = createRateableTarget();
        UUID reviewId = createReview(authorUser.userId(), target);
        createReport(reporterUser.userId(), reviewId);

        // Primeira remoção — deve ser sucesso
        mockMvc.perform(post("/api/v1/admin/reviews/" + reviewId + "/moderate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + moderator.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(removeRequest())))
                .andExpect(status().isOk());

        // Segunda remoção — deve retornar 409
        mockMvc.perform(post("/api/v1/admin/reviews/" + reviewId + "/moderate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + moderator.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(removeRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_ALREADY_REMOVED"));
    }

    @Test
    @DisplayName("12. POST moderate com auto-moderação deve retornar 403 SELF_MODERATION_FORBIDDEN")
    void moderateReview_selfModeration_returns403() throws Exception {
        TestUser moderatorUser = registerUser("mod_self_moderator");
        TestUser moderator = promoteToModerator(moderatorUser);

        RateableTarget target = createRateableTarget();
        // O moderador é o autor da própria avaliação
        UUID reviewId = createReview(moderator.userId(), target);

        TestUser reporterUser = registerUser("mod_self_reporter");
        createReport(reporterUser.userId(), reviewId);

        mockMvc.perform(post("/api/v1/admin/reviews/" + reviewId + "/moderate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + moderator.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(removeRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_MODERATION_FORBIDDEN"));
    }

    @Test
    @DisplayName("13. POST moderate com payload inválido (justificativa muito curta) deve retornar 400")
    void moderateReview_invalidPayload_returns400() throws Exception {
        TestUser moderatorUser = registerUser("mod_400_moderator");
        TestUser moderator = promoteToModerator(moderatorUser);

        ModerateReviewRequest invalidRequest = new ModerateReviewRequest(
                ModerationAction.REMOVE_REVIEW,
                "SPAM",
                "Curto" // menos de 15 caracteres
        );

        mockMvc.perform(post("/api/v1/admin/reviews/" + UUID.randomUUID() + "/moderate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + moderator.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("14. POST moderate com payload inválido (action nulo) deve retornar 400")
    void moderateReview_nullAction_returns400() throws Exception {
        TestUser moderatorUser = registerUser("mod_400_null_action");
        TestUser moderator = promoteToModerator(moderatorUser);

        // Payload sem o campo action
        String invalidJson = """
                {
                  "reasonCode": "SPAM_CONFIRMED",
                  "justification": "Justificativa longa o suficiente para passar na validação de tamanho mínimo."
                }
                """;

        mockMvc.perform(post("/api/v1/admin/reviews/" + UUID.randomUUID() + "/moderate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + moderator.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("15. GET /api/v1/admin/reports com parâmetro size=0 (inválido) deve retornar 400")
    void listReports_invalidPageSize_returns400() throws Exception {
        TestUser moderatorUser = registerUser("mod_bad_size");
        TestUser moderator = promoteToModerator(moderatorUser);

        mockMvc.perform(get("/api/v1/admin/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + moderator.accessToken())
                        .param("size", "0"))
                .andExpect(status().isBadRequest());
    }
}
