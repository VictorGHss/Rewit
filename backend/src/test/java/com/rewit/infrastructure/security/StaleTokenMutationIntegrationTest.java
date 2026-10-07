package com.rewit.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Review;
import com.rewit.presentation.dto.auth.LoginRequest;
import com.rewit.presentation.dto.auth.RefreshRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JWT antigo: um access token emitido enquanto a conta estava ativa continua com assinatura e expiração válidas
 * depois da desativação, mas não inicia nenhuma mutação. Leituras não consultam o estado da conta.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Estado da conta: JWT emitido antes da desativação")
class StaleTokenMutationIntegrationTest {

    private static final String PASSWORD = "SenhaSegura123!";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("Inativa: mutações com o token antigo recebem 401 ACCOUNT_DISABLED; leitura segue; autenticação segue as regras atuais")
    void staleTokenOfInactiveAccountCannotMutate() throws Exception {
        assertStaleTokenRejected("UPDATE users SET account_status = 'SUSPENDED', is_active = FALSE WHERE id = ?");
    }

    @Test
    @DisplayName("Soft-deleted: mutações com o token antigo recebem 401 ACCOUNT_DISABLED; leitura segue; autenticação segue as regras atuais")
    void staleTokenOfSoftDeletedAccountCannotMutate() throws Exception {
        assertStaleTokenRejected("UPDATE users SET account_status = 'DELETED', is_active = FALSE, deleted_at = now() WHERE id = ?");
    }

    private void assertStaleTokenRejected(String deactivationSql) throws Exception {
        Account author = register("autor");
        Account actor = register("antigo");
        Place place = createPlace();
        Review authorReview = createReview(author.userId(), place);
        Review ownReview = createReview(actor.userId(), place);

        // Conta ativa: o token funciona normalmente
        perform(post("/api/v1/users/{id}/follow", author.userId()), actor.accessToken()).andExpect(status().isOk());

        assertEquals(1, jdbcTemplate.update(deactivationSql, actor.userId()));

        // Mesmo token, ainda dentro da validade: toda mutação é recusada
        expectAccountDisabled(perform(post("/api/v1/reviews/{id}/helpful", authorReview.getId()), actor.accessToken()));
        expectAccountDisabled(perform(delete("/api/v1/users/{id}/follow", author.userId()), actor.accessToken()));
        expectAccountDisabled(perform(patch("/api/v1/reviews/{id}", ownReview.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"experienceText\":\"Edição com token antigo\"}"), actor.accessToken()));
        expectAccountDisabled(perform(post("/api/v1/reviews/{id}/discussions", authorReview.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Comentário com token antigo\"}"), actor.accessToken()));

        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_follows WHERE follower_user_id = ?", Long.class, actor.userId()));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_reactions WHERE user_id = ?", Long.class, actor.userId()));

        // Leitura não passa pelo estado da conta
        perform(get("/api/v1/feed"), actor.accessToken()).andExpect(status().isOk());

        // Não regressão da autenticação: login negado como credencial inválida e refresh como conta desativada
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(actor.email(), PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(actor.refreshToken()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        // A conta do autor, ativa, segue operando
        perform(post("/api/v1/users/{id}/follow", actor.userId()), author.accessToken()).andExpect(status().isNotFound());
        perform(post("/api/v1/reviews/{id}/helpful", ownReview.getId()), author.accessToken()).andExpect(status().isOk());
    }

    private ResultActions perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                  String token) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static void expectAccountDisabled(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    private record Account(UUID userId, String email, String accessToken, String refreshToken) {}

    private Account register(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "." + suffix + "@rewit.test";
        String body = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, PASSWORD, prefix + "_" + suffix, "Conta " + prefix))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return new Account(UUID.fromString(json.get("user").get("id").asText()), email,
                json.get("accessToken").asText(), json.get("refreshToken").asText());
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return placeRepository.save(new Place(null, "Lugar Token " + suffix, "lugar-token-" + suffix, "RESTAURANTE",
                "Descrição", "Rua Teste, 100", "100", "Centro", "São Paulo", "SP", "BR",
                -23.5505, -46.6333, 50, "USER", false, null, "ACTIVE"));
    }

    private Review createReview(UUID authorId, Place place) {
        return reviewRepository.save(new Review(null, authorId, place.getId(), "Avaliação para o teste de token antigo",
                false, false, ReviewStatus.ACTIVE, "PUBLIC", null, null, null, Instant.now(), Instant.now()));
    }
}
