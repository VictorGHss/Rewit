package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ReviewRepository;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("HTTP: moderação administrativa de discussões")
class AdminDiscussionModerationControllerIntegrationTest {

    private static final String PASSWORD = "SenhaSegura123!";
    private static final String REMOVE_BODY =
            "{\"action\":\"REMOVE_DISCUSSION\",\"reasonCode\":\"HARASSMENT\",\"justification\":\"Assédio confirmado após análise\"}";
    private static final String RESTORE_BODY =
            "{\"action\":\"RESTORE_DISCUSSION\",\"reasonCode\":\"NO_VIOLATION\",\"justification\":\"Nenhuma violação das diretrizes\"}";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private DiscussionRepository discussionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("Autorização: anônimo 401, USER 403; MODERATOR lista, vê contexto e remove")
    void moderatorFlow() throws Exception {
        ReviewDiscussion comment = quarantinedComment();
        Account user = register("comum");
        Account moderator = promote(register("mod"), "MODERATOR");

        mockMvc.perform(get("/api/v1/admin/discussion-reports")).andExpect(status().isUnauthorized());
        perform(get("/api/v1/admin/discussion-reports"), user).andExpect(status().isForbidden());
        perform(get("/api/v1/admin/discussions/{id}", comment.getId()), user).andExpect(status().isForbidden());
        moderate(comment.getId(), user, REMOVE_BODY).andExpect(status().isForbidden());

        perform(get("/api/v1/admin/discussion-reports").param("discussionId", comment.getId().toString())
                .param("status", "PENDING"), moderator)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].discussionStatus").value("UNDER_REVIEW"));
        perform(get("/api/v1/admin/discussions/{id}", comment.getId()), moderator)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discussion.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.discussion.content").value("Comentário denunciado"))
                .andExpect(jsonPath("$.pendingReportCount").value(3))
                .andExpect(jsonPath("$.reports.length()").value(3));

        moderate(comment.getId(), moderator, REMOVE_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.newStatus").value("REMOVED"))
                .andExpect(jsonPath("$.decision").value("ACCEPTED"))
                .andExpect(jsonPath("$.resolvedReportsCount").value(3));

        perform(get("/api/v1/admin/discussions/{id}", comment.getId()), moderator)
                .andExpect(jsonPath("$.auditHistory.length()").value(1))
                .andExpect(jsonPath("$.auditHistory[0].action").value("REMOVE_DISCUSSION"));
    }

    @Test
    @DisplayName("ADMIN restaura; validação 400; comentário inexistente 404; não em análise 409")
    void adminRestoreAndErrors() throws Exception {
        ReviewDiscussion comment = quarantinedComment();
        Account admin = promote(register("adm"), "ADMIN");

        moderate(comment.getId(), admin, "{\"action\":\"REMOVE_DISCUSSION\",\"reasonCode\":\"X\",\"justification\":\"curta\"}")
                .andExpect(status().isBadRequest());
        moderate(comment.getId(), admin, "{\"reasonCode\":\"X\",\"justification\":\"Justificativa suficiente\"}")
                .andExpect(status().isBadRequest());
        moderate(UUID.randomUUID(), admin, RESTORE_BODY)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DISCUSSION_NOT_FOUND"));

        moderate(comment.getId(), admin, RESTORE_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.decision").value("REJECTED"));
        moderate(comment.getId(), admin, REMOVE_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISCUSSION_NOT_UNDER_REVIEW"));
    }

    private ResultActions moderate(UUID discussionId, Account account, String body) throws Exception {
        return perform(post("/api/v1/admin/discussions/{id}/moderate", discussionId)
                .contentType(MediaType.APPLICATION_JSON).content(body), account);
    }

    private ResultActions perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                  Account account) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token()));
    }

    /** Comentário levado a UNDER_REVIEW pelo endpoint público de denúncia. */
    private ReviewDiscussion quarantinedComment() throws Exception {
        Account author = register("autor");
        Account commenter = register("coment");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = placeRepository.save(new Place(null, "Lugar Admin " + suffix, "lugar-admin-" + suffix, "RESTAURANTE",
                "Descrição", "Rua Teste, 100", "100", "Centro", "São Paulo", "SP", "BR",
                -23.5505, -46.6333, 50, "USER", false, null, "ACTIVE"));
        Review review = reviewRepository.save(new Review(null, author.userId(), place.getId(), "Avaliação moderada",
                false, false, ReviewStatus.ACTIVE, "PUBLIC", null, null, null, Instant.now(), Instant.now()));
        ReviewDiscussion comment = discussionRepository.save(new ReviewDiscussion(null, review.getId(), commenter.userId(),
                null, "Comentário denunciado", false));
        for (int i = 0; i < 3; i++) {
            perform(post("/api/v1/discussions/{id}/reports", comment.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"HARASSMENT\"}"), register("den" + i))
                    .andExpect(status().isAccepted());
        }
        return comment;
    }

    private record Account(UUID userId, String email, String token) {}

    private Account register(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "." + suffix + "@rewit.test";
        String body = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, PASSWORD, prefix + "_" + suffix, "Conta " + prefix))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return new Account(UUID.fromString(json.get("user").get("id").asText()), email, json.get("accessToken").asText());
    }

    /** Promove via JDBC e faz login de novo para obter um token com a role atualizada. */
    private Account promote(Account account, String role) throws Exception {
        jdbcTemplate.update("UPDATE users SET role = ? WHERE id = ?", role, account.userId());
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(account.email(), PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Account(account.userId(), account.email(), objectMapper.readTree(body).get("accessToken").asText());
    }
}
