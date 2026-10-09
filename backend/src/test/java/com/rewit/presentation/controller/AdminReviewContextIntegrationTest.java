package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.report.ReportDtos.CreateReportCommand;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProductRepository;
import com.rewit.application.service.ReportService;
import com.rewit.application.service.ReviewService;
import com.rewit.domain.enums.ModerationAction;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Product;
import com.rewit.presentation.dto.admin.AdminModerationDtos.ModerateReviewRequest;
import com.rewit.presentation.dto.auth.LoginRequest;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/admin/reviews/{reviewId}/context pela API real com PostgreSQL: autorização por role, contrato,
 * histórico em ordem cronológica, ausência de identidades e ausência de efeitos colaterais.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Contexto administrativo de avaliação denunciada (C8): GET /api/v1/admin/reviews/{id}/context")
class AdminReviewContextIntegrationTest {

    private static final double USER_LATITUDE = -25.4312987;
    private static final double USER_LONGITUDE = -49.2765431;

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private ReviewService reviewService;
    @Autowired private ReportService reportService;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record TestUser(String accessToken, UUID userId, String email, String password, String handle) {}

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("Sem token 401; USER 403; MODERATOR e ADMIN 200")
    void requiresModeratorOrAdmin() throws Exception {
        TestUser author = registerUser("ctx_auth_author");
        UUID reviewId = createReview(author, place("Auth"), null, false);

        mockMvc.perform(get("/api/v1/admin/reviews/{id}/context", reviewId)).andExpect(status().isUnauthorized());
        TestUser user = registerUser("ctx_auth_user");
        mockMvc.perform(get("/api/v1/admin/reviews/{id}/context", reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/reviews/{id}/context", reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + withRole(registerUser("ctx_auth_mod"), "MODERATOR").accessToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/reviews/{id}/context", reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + withRole(registerUser("ctx_auth_admin"), "ADMIN").accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Avaliação inexistente: 404 RFC 7807 com REVIEW_NOT_FOUND")
    void missingReviewIsProblemDetail() throws Exception {
        TestUser moderator = withRole(registerUser("ctx_404_mod"), "MODERATOR");
        mockMvc.perform(get("/api/v1/admin/reviews/{id}/context", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + moderator.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    @Test
    @DisplayName("Contrato completo: avaliação removida, alvos com nome, denúncias e histórico em ordem, sem identidades")
    void fullContextWithoutIdentities() throws Exception {
        TestUser author = registerUser("ctx_full_author");
        Place place = place("Contexto");
        Product product = product("Contexto");
        UUID reviewId = createReview(author, place, product, true);
        TestUser moderator = withRole(registerUser("ctx_full_mod"), "MODERATOR");

        List<TestUser> reporters = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            reporters.add(report(reviewId, "ctx_full_rep_a" + i, ReportReason.SPAM));
        }
        moderate(moderator, reviewId, ModerationAction.RESTORE_REVIEW, "NO_VIOLATION");
        for (int i = 0; i < 3; i++) {
            reporters.add(report(reviewId, "ctx_full_rep_b" + i, ReportReason.HARASSMENT));
        }
        moderate(moderator, reviewId, ModerationAction.REMOVE_REVIEW, "OFFENSIVE_CONFIRMED");

        MvcResult result = mockMvc.perform(get("/api/v1/admin/reviews/{id}/context", reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + moderator.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(body);

        assertEquals(List.of("auditHistory", "pendingReportCount", "reports", "review"), fieldNames(json));

        JsonNode review = json.get("review");
        assertEquals(List.of("createdAt", "experienceText", "id", "isAnonymous", "isVerifiedOnSite", "status", "targets",
                "updatedAt", "visibility"), fieldNames(review));
        assertEquals(reviewId.toString(), review.get("id").asText());
        assertEquals("Texto da avaliação denunciada", review.get("experienceText").asText());
        assertEquals("REMOVED", review.get("status").asText());
        assertEquals("PUBLIC", review.get("visibility").asText());
        assertTrue(review.get("isAnonymous").asBoolean());
        assertTrue(review.get("isVerifiedOnSite").asBoolean(), "check-in dentro do raio do local");

        JsonNode targets = review.get("targets");
        assertEquals(2, targets.size());
        assertEquals(List.of("displayName", "rating", "specificComment", "targetId", "type"), fieldNames(targets.get(0)));
        JsonNode placeTarget = findBy(targets, "targetId", place.getId().toString());
        assertEquals("PLACE", placeTarget.get("type").asText());
        assertEquals(place.getName(), placeTarget.get("displayName").asText());
        assertEquals(4.5, placeTarget.get("rating").asDouble());
        assertEquals("Comentário do local", placeTarget.get("specificComment").asText());
        JsonNode productTarget = findBy(targets, "targetId", product.getId().toString());
        assertEquals("PRODUCT", productTarget.get("type").asText());
        assertEquals(product.getName(), productTarget.get("displayName").asText());
        assertTrue(productTarget.get("specificComment").isNull(), "comentário específico ausente sai null");

        // REMOVE aceitou as pendentes; RESTORE rejeitou as anteriores: nenhuma pendente
        assertEquals(0, json.get("pendingReportCount").asLong());
        JsonNode reports = json.get("reports");
        assertEquals(6, reports.size());
        assertEquals(List.of("createdAt", "detail", "id", "reason", "status", "updatedAt"), fieldNames(reports.get(0)));
        assertEquals("SPAM", reports.get(0).get("reason").asText());
        assertEquals("REJECTED", reports.get(0).get("status").asText());
        assertEquals("Detalhe da denúncia", reports.get(0).get("detail").asText());
        assertEquals("HARASSMENT", reports.get(5).get("reason").asText());
        assertEquals("ACCEPTED", reports.get(5).get("status").asText());

        JsonNode audit = json.get("auditHistory");
        assertEquals(2, audit.size(), "histórico em ordem cronológica");
        assertEquals(List.of("action", "createdAt", "justification", "newStatus", "previousStatus", "reasonCode"),
                fieldNames(audit.get(0)));
        assertEquals("RESTORE_REVIEW", audit.get(0).get("action").asText());
        assertEquals("NO_VIOLATION", audit.get(0).get("reasonCode").asText());
        assertEquals("UNDER_REVIEW", audit.get(0).get("previousStatus").asText());
        assertEquals("ACTIVE", audit.get(0).get("newStatus").asText());
        assertEquals("REMOVE_REVIEW", audit.get(1).get("action").asText());
        assertEquals("UNDER_REVIEW", audit.get(1).get("previousStatus").asText());
        assertEquals("REMOVED", audit.get(1).get("newStatus").asText());
        assertTrue(audit.get(1).get("justification").asText().startsWith("Justificativa administrativa"));

        // Nenhuma identidade nem dado pessoal: autor, denunciantes, moderador, e-mail, handle, coordenadas
        List<String> forbidden = new ArrayList<>(List.of(author.userId().toString(), author.email(), author.handle(),
                moderator.userId().toString(), String.valueOf(USER_LATITUDE), String.valueOf(USER_LONGITUDE)));
        reporters.forEach(reporter -> forbidden.add(reporter.userId().toString()));
        for (String value : forbidden) {
            assertFalse(body.contains(value), "vazou '" + value + "'");
        }
        for (String field : List.of("userId", "authorUserId", "reporterUserId", "moderatorUserId", "email", "handle",
                "avatarUrl", "userLatitude", "userLongitude", "author")) {
            assertFalse(body.contains("\"" + field + "\""), "campo proibido " + field);
        }
    }

    @Test
    @DisplayName("Avaliação ativa com denúncia pendente: contagem, sem histórico, e a consulta não altera estado")
    void pendingReportsAndReadOnly() throws Exception {
        TestUser author = registerUser("ctx_ro_author");
        UUID reviewId = createReview(author, place("Leitura"), null, false);
        report(reviewId, "ctx_ro_rep", ReportReason.MISINFORMATION);
        TestUser admin = withRole(registerUser("ctx_ro_admin"), "ADMIN");
        Integer auditBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM moderation_audit_logs WHERE review_id = ?",
                Integer.class, reviewId);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/v1/admin/reviews/{id}/context", reviewId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.accessToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.review.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.review.isAnonymous").value(false))
                    .andExpect(jsonPath("$.review.isVerifiedOnSite").value(true))
                    .andExpect(jsonPath("$.pendingReportCount").value(1))
                    .andExpect(jsonPath("$.reports.length()").value(1))
                    .andExpect(jsonPath("$.reports[0].status").value("PENDING"))
                    .andExpect(jsonPath("$.auditHistory.length()").value(0));
        }

        assertEquals("ACTIVE", jdbcTemplate.queryForObject("SELECT status FROM reviews WHERE id = ?", String.class, reviewId));
        assertEquals("PENDING", jdbcTemplate.queryForObject("SELECT status FROM review_reports WHERE review_id = ?",
                String.class, reviewId));
        assertEquals(auditBefore, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM moderation_audit_logs WHERE review_id = ?", Integer.class, reviewId));
    }

    // -----------------------------------------------------------------------------------------------------------

    private UUID createReview(TestUser author, Place place, Product product, boolean anonymous) {
        List<CreateReviewTargetCommand> targets = new ArrayList<>();
        targets.add(new CreateReviewTargetCommand(place.getId(), new BigDecimal("4.5"), "Comentário do local"));
        if (product != null) {
            targets.add(new CreateReviewTargetCommand(product.getId(), new BigDecimal("3.0"), null));
        }
        return reviewService.createReview(new CreateReviewCommand(author.userId(), place.getId(),
                "Texto da avaliação denunciada", anonymous, "PUBLIC", USER_LATITUDE, USER_LONGITUDE, 5.0, targets)).id();
    }

    private TestUser report(UUID reviewId, String prefix, ReportReason reason) throws Exception {
        TestUser reporter = registerUser(prefix);
        reportService.createReport(new CreateReportCommand(reporter.userId(), reviewId, reason, "Detalhe da denúncia"));
        return reporter;
    }

    private void moderate(TestUser moderator, UUID reviewId, ModerationAction action, String reasonCode) throws Exception {
        mockMvc.perform(post("/api/v1/admin/reviews/{id}/moderate", reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + moderator.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ModerateReviewRequest(action, reasonCode,
                                "Justificativa administrativa com detalhes suficientes para " + action))))
                .andExpect(status().isOk());
    }

    private Place place(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return placeRepository.save(new Place(null, "Local " + label + " " + suffix, "ctx-" + UUID.randomUUID(), "BAR",
                "Descrição", "Rua Contexto, 1", "Curitiba", "PR", "BR", USER_LATITUDE, USER_LONGITUDE, 50, "USER", false,
                null, "ACTIVE"));
    }

    private Product product(String label) {
        return productRepository.save(new Product(null, "Produto " + label + " " + UUID.randomUUID().toString().substring(0, 8),
                "Marca", null, null, "BEBIDA", null, "ACTIVE"));
    }

    private static JsonNode findBy(JsonNode array, String field, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.get(field).asText())) {
                return item;
            }
        }
        throw new AssertionError("sem item com " + field + "=" + value + ": " + array);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names.stream().sorted().toList();
    }

    private TestUser registerUser(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "." + suffix + "@rewit.test";
        String password = "Senha@" + suffix;
        String handle = prefix + "_" + suffix;
        MvcResult res = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, password, handle, "Nome " + suffix))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return new TestUser(node.get("accessToken").asText(), UUID.fromString(node.get("user").get("id").asText()),
                email, password, handle);
    }

    private TestUser withRole(TestUser user, String role) throws Exception {
        jdbcTemplate.update("UPDATE users SET role = ? WHERE id = ?", role, user.userId());
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("accessToken").asText();
        return new TestUser(token, user.userId(), user.email(), user.password(), user.handle());
    }
}
