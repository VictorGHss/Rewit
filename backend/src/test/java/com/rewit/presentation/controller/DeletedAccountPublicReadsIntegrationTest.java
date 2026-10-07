package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.RateableTarget;
import com.rewit.presentation.dto.auth.LoginRequest;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.discussion.CreateDiscussionRequest;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.CreateReviewRequest;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.CreateReviewTargetRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Leituras públicas de uma conta DELETED (C2), pela API real com PostgreSQL: o conteúdo histórico continua
 * (avaliações, notas, helpful, comentários, mídia, agregados), a identidade pública não aparece em nenhum campo.
 *
 * <p>Isolamento: cada teste cria os próprios usuários, local e alvo; o leitor segue apenas os autores do cenário,
 * então os feeds (candidatos por {@code user_follows}) não dependem de outros dados do banco compartilhado.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Conta DELETED (C2): leituras públicas sem identidade, conteúdo histórico preservado")
class DeletedAccountPublicReadsIntegrationTest {

    private static final String DELETED_NAME = "Usuário excluído";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private RateableTargetRepository rateableTargetRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record TestUser(UUID id, String email, String password, String handle, String displayName,
                            String accessToken) {}

    /** Cenário: D (excluída ao final), A (ativa), V (leitor que segue A e D). */
    private record Scenario(TestUser deleted, TestUser active, TestUser viewer, UUID targetId, UUID deletedReviewId,
                            UUID activeReviewId, UUID deletedRootId, UUID activeRootId, String avatarUrl) {}

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("Autor de review: DELETED anonimizado no detalhe, na listagem por alvo e nos feeds V1/V2; ACTIVE normal")
    void reviewAuthorIsAnonymizedEverywhere() throws Exception {
        Scenario s = scenario();

        JsonNode detail = read(get("/api/v1/reviews/{id}", s.deletedReviewId()), s.viewer());
        assertDeletedAuthor(detail.get("author"));
        assertEquals("Avaliação histórica da conta excluída", detail.get("experienceText").asText());
        assertFalse(detail.get("isAnonymous").asBoolean(), "a avaliação não vira anônima; só o autor é ocultado");
        assertActiveAuthor(read(get("/api/v1/reviews/{id}", s.activeReviewId()), s.viewer()).get("author"), s.active());

        JsonNode byTarget = read(get("/api/v1/targets/{id}/reviews", s.targetId()), s.viewer());
        assertAuthorsById(byTarget.get("content"), s);

        JsonNode feedV1 = read(get("/api/v1/feed").param("size", "50"), s.viewer());
        assertAuthorsById(feedV1.get("content"), s);

        JsonNode feedV2 = read(get("/api/v2/feed").param("size", "50"), s.viewer());
        assertAuthorsById(feedV2.get("items"), s);
    }

    @Test
    @DisplayName("Discussions: raiz e resposta de DELETED anonimizadas, misturadas com ACTIVE na mesma thread")
    void discussionAuthorsAreAnonymized() throws Exception {
        Scenario s = scenario();

        JsonNode thread = read(get("/api/v1/reviews/{id}/discussions", s.activeReviewId()), s.viewer());
        JsonNode deletedRoot = findById(thread.get("content"), s.deletedRootId());
        JsonNode activeRoot = findById(thread.get("content"), s.activeRootId());

        assertEquals("VISIBLE", deletedRoot.get("state").asText());
        assertEquals("Comentário da conta excluída", deletedRoot.get("content").asText());
        assertDeletedDiscussionAuthor(deletedRoot.get("author"));
        assertFalse(deletedRoot.get("canDelete").asBoolean());
        assertActiveDiscussionAuthor(deletedRoot.get("replies").get(0).get("author"), s.active());

        assertActiveDiscussionAuthor(activeRoot.get("author"), s.active());
        assertEquals("Resposta da conta excluída", activeRoot.get("replies").get(0).get("content").asText());
        assertDeletedDiscussionAuthor(activeRoot.get("replies").get(0).get("author"));

        JsonNode replies = read(get("/api/v1/discussions/{id}/replies", s.activeRootId()), s.viewer());
        assertDeletedDiscussionAuthor(replies.get("content").get(0).get("author"));
    }

    @Test
    @DisplayName("Perfil, follows e reputação de DELETED: 404 igual ao de UUID inexistente; listas e contadores sem a conta")
    void profileFollowsAndReputationDoNotRevealDeletedAccount() throws Exception {
        Scenario s = scenario();
        UUID missing = UUID.randomUUID();

        for (String path : List.of("/api/v1/users/{id}", "/api/v1/users/{id}/followers", "/api/v1/users/{id}/following",
                "/api/v1/users/{id}/follow", "/api/v1/users/{id}/reputation")) {
            // Mesmo corpo de um UUID que nunca existiu (instance ecoa o caminho pedido; timestamp varia)
            JsonNode deletedBody = withoutRequestEcho(notFound(get(path, s.deleted().id()), s.viewer()));
            JsonNode missingBody = withoutRequestEcho(notFound(get(path, missing), s.viewer()));
            assertEquals(missingBody, deletedBody, path);
            assertNoIdentity(deletedBody.toString(), s);
        }

        // A era seguida por D e seguia D; V seguia D: nenhum vínculo com D aparece em listas ou contadores
        JsonNode activeFollowers = read(get("/api/v1/users/{id}/followers", s.active().id()), s.viewer());
        assertEquals(List.of(s.viewer().id().toString()), ids(activeFollowers.get("content")));
        assertEquals(1, activeFollowers.get("totalElements").asLong());
        JsonNode activeFollowing = read(get("/api/v1/users/{id}/following", s.active().id()), s.viewer());
        assertEquals(0, activeFollowing.get("totalElements").asLong());
        JsonNode viewerFollowing = read(get("/api/v1/me/following"), s.viewer());
        assertEquals(List.of(s.active().id().toString()), ids(viewerFollowing.get("content")));

        JsonNode activeProfile = read(get("/api/v1/users/{id}", s.active().id()), s.viewer());
        assertEquals(1, activeProfile.get("stats").get("followersCount").asLong());
        assertEquals(0, activeProfile.get("stats").get("followingCount").asLong());
    }

    @Test
    @DisplayName("Busca: handle, nome e UUID de DELETED não retornam identidade")
    void searchDoesNotReturnDeletedAccount() throws Exception {
        Scenario s = scenario();
        for (String query : List.of(s.deleted().handle(), s.deleted().displayName(), s.deleted().id().toString())) {
            String body = body(get("/api/v1/search").param("q", query), s.viewer());
            assertNoIdentity(body, s);
        }
    }

    @Test
    @DisplayName("Notificações: o UUID do ator DELETED não chega ao destinatário; ator ACTIVE continua")
    void notificationsDoNotRevealDeletedActor() throws Exception {
        Scenario s = scenario();

        JsonNode notifications = read(get("/api/v1/me/notifications").param("size", "50"), s.active());
        boolean sawMaskedFollower = false;
        boolean sawActiveFollower = false;
        for (JsonNode n : notifications.get("content")) {
            if (!"NEW_FOLLOWER".equals(n.get("type").asText())) {
                continue;
            }
            if (n.get("actorId").isNull()) {
                assertTrue(n.get("referenceId").isNull());
                sawMaskedFollower = true;
            } else {
                assertEquals(s.viewer().id().toString(), n.get("actorId").asText());
                sawActiveFollower = true;
            }
        }
        assertTrue(sawMaskedFollower && sawActiveFollower, notifications.toString());
        assertNoIdentity(notifications.toString(), s);
    }

    @Test
    @DisplayName("Histórico preservado: nota no agregado, helpful recebido, mídia e conteúdo da review de DELETED")
    void historyAndAggregatesArePreserved() throws Exception {
        Scenario s = scenario();

        JsonNode stats = read(get("/api/v1/targets/{id}/stats", s.targetId()), s.viewer());
        assertEquals(2, stats.get("reviewsCount").asInt(), "a nota da conta excluída continua no agregado");
        assertEquals(new BigDecimal("3.0").compareTo(new BigDecimal(stats.get("averageRating").asText())), 0);

        JsonNode detail = read(get("/api/v1/reviews/{id}", s.deletedReviewId()), s.viewer());
        assertEquals(1, detail.get("helpfulCount").asLong());
        assertEquals("ACTIVE", detail.get("status").asText());

        String media = body(get("/api/v1/reviews/{id}/media", s.deletedReviewId()), s.viewer());
        assertEquals(1, objectMapper.readTree(media).size());
        assertNoIdentity(media, s);
        UUID mediaId = UUID.fromString(objectMapper.readTree(media).get(0).get("id").asText());
        mockMvc.perform(get("/api/v1/reviews/{id}/media/{mediaId}", s.deletedReviewId(), mediaId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(s.viewer())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Política inalterada: autor DEACTIVATED ou SUSPENDED continua com a identidade nas reviews")
    void deactivatedAndSuspendedAuthorsAreNotAnonymized() throws Exception {
        TestUser admin = admin();
        TestUser viewer = register("leitor_inalterado");
        UUID targetId = createTarget();
        UUID placeId = createPlace();
        for (String state : List.of("deactivated", "suspended")) {
            TestUser author = register("autor_" + state);
            UUID reviewId = createReview(author, placeId, targetId, "Avaliação " + state, 4.0);
            if (state.equals("deactivated")) {
                mockMvc.perform(post("/api/v1/me/deactivate").header(HttpHeaders.AUTHORIZATION, bearer(author)))
                        .andExpect(status().isNoContent());
            } else {
                mockMvc.perform(post("/api/v1/admin/users/{id}/suspend", author.id()).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                        .andExpect(status().isNoContent());
            }
            assertActiveAuthor(read(get("/api/v1/reviews/{id}", reviewId), viewer).get("author"), author);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Cenário
    // ---------------------------------------------------------------------------------------------------------

    private Scenario scenario() throws Exception {
        TestUser deleted = register("excluida");
        TestUser active = register("ativa");
        TestUser viewer = register("leitor");
        TestUser admin = admin();
        String avatarUrl = "https://cdn.rewit.test/avatars/" + deleted.id() + ".webp";
        jdbcTemplate.update("UPDATE profiles SET avatar_url = ? WHERE user_id = ?", avatarUrl, deleted.id());

        UUID placeId = createPlace();
        UUID targetId = createTarget();
        UUID deletedReviewId = createReview(deleted, placeId, targetId, "Avaliação histórica da conta excluída", 2.0);
        UUID activeReviewId = createReview(active, placeId, targetId, "Avaliação da conta ativa", 4.0);

        uploadMedia(deleted, deletedReviewId);
        mockMvc.perform(post("/api/v1/reviews/{id}/helpful", deletedReviewId).header(HttpHeaders.AUTHORIZATION, bearer(active)))
                .andExpect(status().isOk());

        follow(viewer, deleted);
        follow(viewer, active);
        follow(deleted, active);
        follow(active, deleted);

        UUID deletedRootId = comment(deleted, activeReviewId, "Comentário da conta excluída", null);
        comment(active, activeReviewId, "Resposta da conta ativa", deletedRootId);
        UUID activeRootId = comment(active, activeReviewId, "Comentário da conta ativa", null);
        comment(deleted, activeReviewId, "Resposta da conta excluída", activeRootId);

        mockMvc.perform(delete("/api/v1/admin/users/{id}", deleted.id()).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNoContent());
        assertEquals("DELETED", jdbcTemplate.queryForObject(
                "SELECT account_status FROM users WHERE id = ?", String.class, deleted.id()));

        return new Scenario(deleted, active, viewer, targetId, deletedReviewId, activeReviewId, deletedRootId,
                activeRootId, avatarUrl);
    }

    private UUID createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(null, "Local Privacidade " + suffix, "local-privacidade-" + suffix, "RESTAURANTE",
                "Descrição", "Rua Privacidade, 10", "10", "Centro", "Curitiba", "PR", "BR", -25.43, -49.27, 50,
                "USER", false, null, "ACTIVE");
        return placeRepository.save(place).getId();
    }

    private UUID createTarget() {
        RateableTarget target = rateableTargetRepository.save(new RateableTarget(UUID.randomUUID(), TargetType.PLACE));
        return target.getId();
    }

    private UUID createReview(TestUser author, UUID placeId, UUID targetId, String text, double rating) throws Exception {
        CreateReviewRequest request = new CreateReviewRequest(placeId, text, false, "PUBLIC",
                List.of(new CreateReviewTargetRequest(targetId, BigDecimal.valueOf(rating), "Comentário do alvo")));
        return UUID.fromString(read(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)), author, 201).get("id").asText());
    }

    private UUID comment(TestUser author, UUID reviewId, String content, UUID parentId) throws Exception {
        return UUID.fromString(read(post("/api/v1/reviews/{id}/discussions", reviewId).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateDiscussionRequest(content, parentId))), author, 201)
                .get("id").asText());
    }

    private void follow(TestUser follower, TestUser target) throws Exception {
        mockMvc.perform(post("/api/v1/users/{id}/follow", target.id()).header(HttpHeaders.AUTHORIZATION, bearer(follower)))
                .andExpect(status().isOk());
    }

    private void uploadMedia(TestUser author, UUID reviewId) throws Exception {
        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", bytes);
        mockMvc.perform(multipart("/api/v1/reviews/{id}/media", reviewId)
                        .file(new MockMultipartFile("file", "foto.jpg", "image/jpeg", bytes.toByteArray()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(author)))
                .andExpect(status().isCreated());
    }

    private TestUser register(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "." + suffix + "@rewit.test";
        String password = "Senha@" + suffix;
        String handle = (prefix + "_" + suffix).toLowerCase();
        String displayName = "Nome " + prefix + " " + suffix;
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, password, handle, displayName))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return new TestUser(UUID.fromString(node.get("user").get("id").asText()), email, password, handle, displayName,
                node.get("accessToken").asText());
    }

    private TestUser admin() throws Exception {
        TestUser user = register("privacidade_admin");
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.id());
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isOk())
                .andReturn();
        return new TestUser(user.id(), user.email(), user.password(), user.handle(), user.displayName(),
                objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("accessToken").asText());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Asserções
    // ---------------------------------------------------------------------------------------------------------

    /** Itens de review (detalhe, listagens e feeds) dos dois autores do cenário, conferidos por id. */
    private void assertAuthorsById(JsonNode items, Scenario s) {
        JsonNode deletedItem = findById(items, s.deletedReviewId());
        JsonNode activeItem = findById(items, s.activeReviewId());
        assertDeletedAuthor(deletedItem.get("author"));
        assertActiveAuthor(activeItem.get("author"), s.active());
        assertNoIdentity(items.toString(), s);
    }

    private static void assertDeletedAuthor(JsonNode author) {
        assertTrue(author.get("id").isNull(), author.toString());
        assertTrue(author.get("handle").isNull(), author.toString());
        assertTrue(author.get("avatarUrl").isNull(), author.toString());
        assertEquals(DELETED_NAME, author.get("displayName").asText());
        assertTrue(author.get("isAnonymous").asBoolean(), "identidade oculta: clientes não criam link de perfil");
    }

    private static void assertActiveAuthor(JsonNode author, TestUser user) {
        assertEquals(user.id().toString(), author.get("id").asText());
        assertEquals(user.handle(), author.get("handle").asText());
        assertEquals(user.displayName(), author.get("displayName").asText());
        assertFalse(author.get("isAnonymous").asBoolean());
    }

    private static void assertDeletedDiscussionAuthor(JsonNode author) {
        assertNotNull(author, "o autor excluído é projetado como objeto, não confundido com o anonimato do dono");
        assertTrue(author.get("id").isNull(), author.toString());
        assertTrue(author.get("handle").isNull(), author.toString());
        assertTrue(author.get("avatarUrl").isNull(), author.toString());
        assertEquals(DELETED_NAME, author.get("displayName").asText());
    }

    private static void assertActiveDiscussionAuthor(JsonNode author, TestUser user) {
        assertEquals(user.id().toString(), author.get("id").asText());
        assertEquals(user.handle(), author.get("handle").asText());
    }

    /** Nenhum dado identificador da conta excluída na resposta: UUID, e-mail, handle, nome, avatar, link de perfil. */
    private static void assertNoIdentity(String body, Scenario s) {
        TestUser d = s.deleted();
        for (String identifier : List.of(d.id().toString(), d.email(), d.handle(), d.displayName(), s.avatarUrl(),
                "/users/" + d.id())) {
            assertFalse(body.contains(identifier), "identidade da conta excluída exposta (" + identifier + "): " + body);
        }
    }

    private static JsonNode findById(JsonNode items, UUID id) {
        for (JsonNode item : items) {
            if (id.toString().equals(item.get("id").asText())) {
                return item;
            }
        }
        throw new AssertionError("item " + id + " ausente em " + items);
    }

    private static List<String> ids(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private JsonNode withoutRequestEcho(String problemBody) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode node =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(problemBody);
        node.remove(List.of("instance", "timestamp"));
        return node;
    }

    private JsonNode read(MockHttpServletRequestBuilder request, TestUser user) throws Exception {
        return read(request, user, 200);
    }

    private JsonNode read(MockHttpServletRequestBuilder request, TestUser user, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String body(MockHttpServletRequestBuilder request, TestUser user) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String notFound(MockHttpServletRequestBuilder request, TestUser user) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String bearer(TestUser user) {
        return "Bearer " + user.accessToken();
    }
}
