package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.domain.enums.BusinessResponseModerationAction;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewDiscussion;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testes de segurança e integração HTTP das rotas de respostas oficiais de empresas (C11)
 * pela cadeia real do Spring Security, tokens Bearer, filtros e PostgreSQL real.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Segurança das rotas HTTP de Respostas Oficiais de Empresas (C11)")
class BusinessResponseSecurityIntegrationTest {

    private static final String JUSTIFICATION = "Violação das diretrizes da comunidade verificada formalmente.";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private DiscussionRepository discussionRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record TestUser(UUID id, String email, String password, String token) {}

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    // ---------------------------------------------------------------------------------------------------------
    // 1. Sem autenticação (401 AUTHENTICATION_REQUIRED em todas as rotas)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Sem token: todas as 7 rotas rejeitam com 401 AUTHENTICATION_REQUIRED")
    void unauthenticatedRequestsAreRejected() throws Exception {
        UUID dummyId = UUID.randomUUID();

        List<MockHttpServletRequestBuilder> requests = List.of(
                post("/api/v1/reviews/{reviewId}/business-responses", dummyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(dummyId, dummyId, "Conteúdo")),
                get("/api/v1/reviews/{reviewId}/business-responses", dummyId),
                get("/api/v1/reviews/{reviewId}/business-responses/eligibility", dummyId),
                post("/api/v1/discussions/{discussionId}/business-responses", dummyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(dummyId, dummyId, "Conteúdo")),
                get("/api/v1/discussions/{discussionId}/business-responses", dummyId),
                delete("/api/v1/business-responses/{responseId}", dummyId),
                post("/api/v1/admin/business-responses/{responseId}/moderate", dummyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moderateBody(BusinessResponseModerationAction.REMOVE_RESPONSE, "SPAM", JUSTIFICATION))
        );

        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // 2. Fluxo completo de publicação e consulta de resposta a avaliação
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Administrador de empresa aprovada publica resposta oficial a avaliação e consulta (201, 200)")
    void shouldPublishAndListReviewBusinessResponse() throws Exception {
        TestUser businessAdmin = registerUser("biz_adm");
        TestUser reviewer = registerUser("reviewer");

        UUID placeId = createPlace();
        UUID reviewId = createReview(reviewer.id(), placeId, "PUBLIC", ReviewStatus.ACTIVE);
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        String content = "Agradecemos pela avaliação! Nosso compromisso é com a excelência.";

        // 1. Consulta elegibilidade
        mockMvc.perform(withToken(get("/api/v1/reviews/{reviewId}/business-responses/eligibility", reviewId), businessAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.respondable").value(true))
                .andExpect(jsonPath("$.accounts[0].businessAccountId").value(accountId.toString()))
                .andExpect(jsonPath("$.accounts[0].canPublish").value(true))
                .andExpect(jsonPath("$.accounts[0].places[0].placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.accounts[0].taxId").doesNotExist());

        // 2. Publica resposta oficial
        MvcResult publishResult = mockMvc.perform(withToken(
                post("/api/v1/reviews/{reviewId}/business-responses", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(accountId, placeId, content)), businessAdmin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.businessAccountId").value(accountId.toString()))
                .andExpect(jsonPath("$.placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.content").value(content))
                .andExpect(jsonPath("$.authorUserId").doesNotExist())
                .andExpect(jsonPath("$.taxId").doesNotExist())
                .andReturn();

        UUID responseId = UUID.fromString(read(publishResult).get("id").asText());
        assertNotNull(responseId);

        // 3. Lista respostas da avaliação
        mockMvc.perform(withToken(get("/api/v1/reviews/{reviewId}/business-responses", reviewId), reviewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(responseId.toString()))
                .andExpect(jsonPath("$.content[0].reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.content[0].content").value(content))
                .andExpect(jsonPath("$.content[0].authorUserId").doesNotExist())
                .andExpect(jsonPath("$.content[0].taxId").doesNotExist());

        // 4. Empresa remove a própria resposta (204 No Content)
        mockMvc.perform(withToken(delete("/api/v1/business-responses/{responseId}", responseId), businessAdmin))
                .andExpect(status().isNoContent());

        // 5. Tentativa de remover novamente resulta em 409 BUSINESS_RESPONSE_ALREADY_REMOVED
        mockMvc.perform(withToken(delete("/api/v1/business-responses/{responseId}", responseId), businessAdmin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RESPONSE_ALREADY_REMOVED"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // 3. Regras de representação: sem vínculo com a conta ou conta não aprovada
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Usuário sem vínculo com a conta comercial recebe 404/403 ao tentar publicar")
    void shouldRejectPublishingWhenUserDoesNotAdministerAccount() throws Exception {
        TestUser businessAdmin = registerUser("legit_admin");
        TestUser outsider = registerUser("outsider");
        TestUser reviewer = registerUser("reviewer2");

        UUID placeId = createPlace();
        UUID reviewId = createReview(reviewer.id(), placeId, "PUBLIC", ReviewStatus.ACTIVE);
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        // Outsider tenta publicar usando o accountId do businessAdmin
        mockMvc.perform(withToken(
                post("/api/v1/reviews/{reviewId}/business-responses", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(accountId, placeId, "Resposta indevida")), outsider))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BUSINESS_ACCOUNT_NOT_FOUND"));
    }

    @Test
    @DisplayName("Conta comercial ainda não aprovada (PENDING) é recusada (409 BUSINESS_ACCOUNT_NOT_APPROVED)")
    void shouldRejectPublishingWhenAccountNotApproved() throws Exception {
        TestUser businessAdmin = registerUser("pending_admin");
        TestUser reviewer = registerUser("reviewer3");

        UUID placeId = createPlace();
        UUID reviewId = createReview(reviewer.id(), placeId, "PUBLIC", ReviewStatus.ACTIVE);

        // Cria conta mas não aprova (permanece PENDING)
        UUID accountId = createAccount(businessAdmin);

        mockMvc.perform(withToken(
                post("/api/v1/reviews/{reviewId}/business-responses", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(accountId, placeId, "Tentativa de resposta")), businessAdmin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_ACCOUNT_NOT_APPROVED"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // 4. Publicação em comentário raiz de discussão
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Publicação de resposta oficial em comentário raiz de discussão (201)")
    void shouldPublishAndListDiscussionBusinessResponse() throws Exception {
        TestUser businessAdmin = registerUser("disc_biz_adm");
        TestUser reviewer = registerUser("disc_reviewer");
        TestUser commenter = registerUser("commenter");

        UUID placeId = createPlace();
        UUID reviewId = createReview(reviewer.id(), placeId, "PUBLIC", ReviewStatus.ACTIVE);
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        // Cria comentário raiz
        ReviewDiscussion discussion = discussionRepository.save(new ReviewDiscussion(
                UUID.randomUUID(), reviewId, commenter.id(), null, "Comentário sobre o serviço", false
        ));
        UUID discussionId = discussion.getId();

        String content = "Prezado cliente, estamos à disposição para esclarecer qualquer dúvida.";

        // Publica resposta oficial ao comentário raiz
        MvcResult publishResult = mockMvc.perform(withToken(
                post("/api/v1/discussions/{discussionId}/business-responses", discussionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(accountId, placeId, content)), businessAdmin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.discussionId").value(discussionId.toString()))
                .andExpect(jsonPath("$.businessAccountId").value(accountId.toString()))
                .andExpect(jsonPath("$.placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.content").value(content))
                .andExpect(jsonPath("$.authorUserId").doesNotExist())
                .andExpect(jsonPath("$.taxId").doesNotExist())
                .andReturn();

        UUID responseId = UUID.fromString(read(publishResult).get("id").asText());
        assertNotNull(responseId);

        // Lista respostas oficiais do comentário
        mockMvc.perform(withToken(get("/api/v1/discussions/{discussionId}/business-responses", discussionId), commenter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(responseId.toString()))
                .andExpect(jsonPath("$.content[0].discussionId").value(discussionId.toString()));
    }

    // ---------------------------------------------------------------------------------------------------------
    // 5. Moderação Administrativa (PreAuthorize, Auditoria, Conflito de Interesse, Revalidação de Role)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Usuário comum sem role administrativa é barrado pelo filtro HTTP com 403 Forbidden")
    void nonAdminUserIsBlockedByPreAuthorize() throws Exception {
        TestUser regularUser = registerUser("regular_mod_attempt");
        UUID responseId = UUID.randomUUID();

        mockMvc.perform(withToken(
                post("/api/v1/admin/business-responses/{responseId}/moderate", responseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moderateBody(BusinessResponseModerationAction.REMOVE_RESPONSE, "SPAM", JUSTIFICATION)),
                regularUser))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("MODERATOR remove e restaura resposta oficial com sucesso (200 OK)")
    void moderatorCanRemoveAndRestoreResponse() throws Exception {
        TestUser businessAdmin = registerUser("target_biz_admin");
        TestUser reviewer = registerUser("target_reviewer");
        TestUser moderator = withRole(registerUser("active_moderator"), "MODERATOR");

        UUID placeId = createPlace();
        UUID reviewId = createReview(reviewer.id(), placeId, "PUBLIC", ReviewStatus.ACTIVE);
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        MvcResult publishResult = mockMvc.perform(withToken(
                post("/api/v1/reviews/{reviewId}/business-responses", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(accountId, placeId, "Resposta para moderação")), businessAdmin))
                .andExpect(status().isCreated())
                .andReturn();
        UUID responseId = UUID.fromString(read(publishResult).get("id").asText());

        // 1. Moderação remove
        mockMvc.perform(withToken(
                post("/api/v1/admin/business-responses/{responseId}/moderate", responseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moderateBody(BusinessResponseModerationAction.REMOVE_RESPONSE, "INAPPROPRIATE_CONTENT", JUSTIFICATION)),
                moderator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseId").value(responseId.toString()))
                .andExpect(jsonPath("$.action").value("REMOVE_RESPONSE"))
                .andExpect(jsonPath("$.previousStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.newStatus").value("REMOVED"))
                .andExpect(jsonPath("$.removedBy").value("MODERATION"))
                .andExpect(jsonPath("$.authorUserId").doesNotExist())
                .andExpect(jsonPath("$.taxId").doesNotExist());

        // 2. Moderação restaura
        mockMvc.perform(withToken(
                post("/api/v1/admin/business-responses/{responseId}/moderate", responseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moderateBody(BusinessResponseModerationAction.RESTORE_RESPONSE, "APPEAL_ACCEPTED", JUSTIFICATION)),
                moderator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseId").value(responseId.toString()))
                .andExpect(jsonPath("$.action").value("RESTORE_RESPONSE"))
                .andExpect(jsonPath("$.previousStatus").value("REMOVED"))
                .andExpect(jsonPath("$.newStatus").value("ACTIVE"));
    }

    @Test
    @DisplayName("Conflito de interesse: moderador não pode moderar resposta da própria empresa (403 SELF_MODERATION_FORBIDDEN)")
    void selfModerationIsBlocked() throws Exception {
        // Usuário que administra a empresa E possui role MODERATOR
        TestUser dualUser = withRole(registerUser("dual_biz_mod"), "MODERATOR");
        TestUser reviewer = registerUser("conflict_reviewer");

        UUID placeId = createPlace();
        UUID reviewId = createReview(reviewer.id(), placeId, "PUBLIC", ReviewStatus.ACTIVE);
        UUID accountId = createAndApproveBusinessAccount(dualUser, placeId);

        MvcResult publishResult = mockMvc.perform(withToken(
                post("/api/v1/reviews/{reviewId}/business-responses", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(accountId, placeId, "Resposta da própria empresa do moderador")), dualUser))
                .andExpect(status().isCreated())
                .andReturn();
        UUID responseId = UUID.fromString(read(publishResult).get("id").asText());

        // Tenta moderar a própria resposta -> 403 SELF_MODERATION_FORBIDDEN
        mockMvc.perform(withToken(
                post("/api/v1/admin/business-responses/{responseId}/moderate", responseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moderateBody(BusinessResponseModerationAction.REMOVE_RESPONSE, "SPAM", JUSTIFICATION)),
                dualUser))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_MODERATION_FORBIDDEN"));
    }

    @Test
    @DisplayName("Moderador rebaixado no banco com token antigo é recusado pelo caso de uso (403 FORBIDDEN)")
    void demotedModeratorWithOldTokenIsRejected() throws Exception {
        TestUser moderator = withRole(registerUser("demoted_mod"), "MODERATOR");
        TestUser businessAdmin = registerUser("biz_for_demote");
        TestUser reviewer = registerUser("rev_for_demote");

        UUID placeId = createPlace();
        UUID reviewId = createReview(reviewer.id(), placeId, "PUBLIC", ReviewStatus.ACTIVE);
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        MvcResult publishResult = mockMvc.perform(withToken(
                post("/api/v1/reviews/{reviewId}/business-responses", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(accountId, placeId, "Resposta para teste de rebaixamento")), businessAdmin))
                .andExpect(status().isCreated())
                .andReturn();
        UUID responseId = UUID.fromString(read(publishResult).get("id").asText());

        // Rebaixa cargo no banco enquanto o usuário mantém token JWT com claim MODERATOR
        jdbcTemplate.update("UPDATE users SET role = 'USER' WHERE id = ?", moderator.id());

        mockMvc.perform(withToken(
                post("/api/v1/admin/business-responses/{responseId}/moderate", responseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moderateBody(BusinessResponseModerationAction.REMOVE_RESPONSE, "SPAM", JUSTIFICATION)),
                moderator))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private String publishBody(UUID accountId, UUID placeId, String content) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "businessAccountId", accountId.toString(),
                "placeId", placeId.toString(),
                "content", content
        ));
    }

    private String moderateBody(BusinessResponseModerationAction action, String reasonCode, String justification)
            throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "action", action.name(),
                "reasonCode", reasonCode,
                "justification", justification
        ));
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, TestUser user) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private TestUser registerUser(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "." + suffix + "@rewit.test";
        String password = "Senha@" + suffix;
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(
                                email, password, prefix + "_" + suffix, "Nome " + suffix
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = read(result);
        return new TestUser(
                UUID.fromString(node.get("user").get("id").asText()),
                email,
                password,
                node.get("accessToken").asText()
        );
    }

    private TestUser withRole(TestUser user, String role) throws Exception {
        jdbcTemplate.update("UPDATE users SET role = ? WHERE id = ?", role, user.id());
        return login(user);
    }

    private TestUser login(TestUser user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isOk())
                .andReturn();
        String token = read(result).get("accessToken").asText();
        return new TestUser(user.id(), user.email(), user.password(), token);
    }

    private UUID createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place saved = placeRepository.save(new Place(
                null, "Local " + suffix, "loc-" + UUID.randomUUID(),
                "RESTAURANTE", "Descrição", "Rua das Respostas, 100", "Curitiba", "PR", "BR",
                -25.43, -49.27, 50, "USER", false, null, "ACTIVE"
        ));
        return saved.getId();
    }

    private UUID createReview(UUID authorId, UUID placeId, String visibility, ReviewStatus status) {
        Review review = new Review(
                null, authorId, placeId, "Texto da avaliação para teste de resposta oficial",
                false, false, status, visibility, null, null, null, Instant.now(), Instant.now()
        );
        return reviewRepository.save(review).getId();
    }

    private UUID createAccount(TestUser user) throws Exception {
        MvcResult result = mockMvc.perform(withToken(
                post("/api/v1/business-accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "corporateName", "Empresa " + user.id().toString().substring(0, 8),
                                "taxId", randomTaxId()
                        ))), user))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").asText());
    }

    private UUID createAndApproveBusinessAccount(TestUser user, UUID placeId) throws Exception {
        UUID accountId = createAccount(user);
        // Aprova a conta comercial e vincula o local
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'APPROVED' WHERE id = ?", accountId);
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", accountId, placeId);
        return accountId;
    }

    private static String randomTaxId() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            builder.append(ThreadLocalRandom.current().nextInt(10));
        }
        return builder.toString();
    }
}
