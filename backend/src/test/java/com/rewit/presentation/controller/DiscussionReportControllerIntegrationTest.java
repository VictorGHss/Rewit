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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("HTTP: denúncia de discussão com confirmação genérica")
class DiscussionReportControllerIntegrationTest {

    private static final String BODY = "{\"reason\":\"HARASSMENT\",\"detail\":\"Ofensivo\"}";

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
    @DisplayName("Denúncia nova, repetida e a que dispara a quarentena recebem o mesmo 202 genérico")
    void sameGenericReceiptForEveryOutcome() throws Exception {
        Account author = register("autor");
        Account commenter = register("coment");
        ReviewDiscussion comment = createComment(author, commenter);
        Account first = register("d1");

        String newReport = report(first, comment.getId()).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String repeated = report(first, comment.getId()).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        report(register("d2"), comment.getId()).andExpect(status().isAccepted());
        String quarantining = report(register("d3"), comment.getId()).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        assertEquals(newReport, repeated);
        assertEquals(newReport, quarantining);
        JsonNode body = objectMapper.readTree(newReport);
        assertEquals("RECEIVED", body.get("status").asText());
        assertEquals(2, body.size(), "sem id, contagem ou estado do comentário");
        assertEquals("UNDER_REVIEW", jdbcTemplate.queryForObject(
                "SELECT status FROM review_discussions WHERE id = ?", String.class, comment.getId()));

        // Novo denunciante depois da quarentena: 404 uniforme, sem revelar o motivo
        report(register("d4"), comment.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DISCUSSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("Sem token 401; sem motivo 400; autor denunciando o próprio comentário 400")
    void validationAndAuthentication() throws Exception {
        Account author = register("autor_v");
        Account commenter = register("coment_v");
        ReviewDiscussion comment = createComment(author, commenter);

        mockMvc.perform(post("/api/v1/discussions/{id}/reports", comment.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/discussions/{id}/reports", comment.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + register("v1").token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"detail\":\"sem motivo\"}"))
                .andExpect(status().isBadRequest());
        report(commenter, comment.getId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_REPORT_FORBIDDEN"));
    }

    private ResultActions report(Account account, UUID discussionId) throws Exception {
        return mockMvc.perform(post("/api/v1/discussions/{id}/reports", discussionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token())
                .contentType(MediaType.APPLICATION_JSON).content(BODY));
    }

    private ReviewDiscussion createComment(Account author, Account commenter) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = placeRepository.save(new Place(null, "Lugar Report " + suffix, "lugar-report-" + suffix, "RESTAURANTE",
                "Descrição", "Rua Teste, 100", "100", "Centro", "São Paulo", "SP", "BR",
                -23.5505, -46.6333, 50, "USER", false, null, "ACTIVE"));
        Review review = reviewRepository.save(new Review(null, author.userId(), place.getId(), "Avaliação HTTP de denúncia",
                false, false, ReviewStatus.ACTIVE, "PUBLIC", null, null, null, Instant.now(), Instant.now()));
        return discussionRepository.save(new ReviewDiscussion(null, review.getId(), commenter.userId(), null,
                "Comentário denunciável", false));
    }

    private record Account(UUID userId, String token) {}

    private Account register(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String body = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(
                                prefix + "." + suffix + "@rewit.test", "SenhaSegura123!", prefix + "_" + suffix, "Conta " + prefix))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return new Account(UUID.fromString(json.get("user").get("id").asText()), json.get("accessToken").asText());
    }
}
