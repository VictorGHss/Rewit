package com.rewit.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.usecase.PurgeDeletedAccountUseCase.PurgeResult;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.User;
import com.rewit.presentation.dto.auth.LoginRequest;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.AssociateProductPresenceRequest;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.CreateProductRequest;
import com.rewit.presentation.dto.discussion.CreateDiscussionRequest;
import com.rewit.presentation.dto.discussion.ReportDiscussionRequest;
import com.rewit.presentation.dto.report.CreateReportRequest;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.CreateReviewRequest;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.CreateReviewTargetRequest;
import com.rewit.domain.enums.ReportReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Purge de conta excluída (C2.3) com PostgreSQL real: a conta excluída (D) tem todos os tipos de vínculo do schema,
 * criados pelos fluxos reais sempre que existem; o purge remove o que é pessoal, minimiza a identidade e preserva o
 * histórico da plataforma.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Purge de conta DELETED (C2.3): PostgreSQL real")
class PurgeDeletedAccountIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private RateableTargetRepository rateableTargetRepository;
    @Autowired private PurgeDeletedAccountUseCase purgeDeletedAccountUseCase;
    @Autowired private PlatformTransactionManager transactionManager;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record TestUser(UUID id, String email, String password, String handle, String displayName, String accessToken) {}

    private record Scenario(TestUser deleted, TestUser active, TestUser viewer, TestUser admin, UUID targetId,
                            UUID deletedReviewId, UUID activeReviewId, UUID deletedRootId, UUID activeRootId,
                            UUID productId) {}

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("Purge: remove o que é pessoal, minimiza a identidade e preserva o histórico da plataforma")
    void purgeRemovesPersonalDataAndPreservesHistory() throws Exception {
        Scenario s = scenario();
        UUID d = s.deleted().id();
        Map<String, Object> preservedBefore = preservedState(s);

        PurgeResult result = purgeDeletedAccountUseCase.execute(d);
        assertTrue(result.identityMinimized());
        assertTrue(result.relations().total() > 0);

        // Removido
        assertEquals(0, count("SELECT count(*) FROM auth_sessions WHERE user_id = ?", d));
        assertEquals(0, count("SELECT count(*) FROM user_follows WHERE follower_user_id = ? OR followed_user_id = ?", d, d));
        assertEquals(0, count("SELECT count(*) FROM saved_items WHERE user_id = ?", d));
        assertEquals(0, count("SELECT count(*) FROM user_interests WHERE user_id = ?", d));
        assertEquals(0, count("SELECT count(*) FROM user_activities WHERE user_id = ?", d));
        assertEquals(0, count("SELECT count(*) FROM notifications WHERE user_id = ?", d));
        assertEquals(0, count("SELECT count(*) FROM user_reputation WHERE user_id = ?", d));

        // Minimizado: identidade da linha users e do perfil
        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT email, password_hash, account_status, deleted_at FROM users WHERE id = ?", d);
        assertEquals(User.reservedEmailFor(s.deleted().email()), user.get("email"));
        assertFalse(((String) user.get("email")).contains(s.deleted().email().substring(0, s.deleted().email().indexOf('@'))));
        assertNull(user.get("password_hash"));
        assertEquals("DELETED", user.get("account_status"));
        Map<String, Object> profile = jdbcTemplate.queryForMap(
                "SELECT handle, display_name, bio, avatar_url, reputation_score FROM profiles WHERE user_id = ?", d);
        assertEquals(Profile.deletedHandleFor(d), profile.get("handle"));
        assertEquals("Usuário excluído", profile.get("display_name"));
        assertNull(profile.get("bio"));
        assertNull(profile.get("avatar_url"));
        assertEquals(0, profile.get("reputation_score"));

        // Minimizado: referências à conta fora dela
        assertEquals(0, count("SELECT count(*) FROM notifications WHERE metadata_json ->> 'actorId' = ? "
                + "OR metadata_json ->> 'referenceId' = ? OR action_url = ?", d.toString(), d.toString(), "/api/v1/users/" + d));
        assertEquals(1, count("SELECT count(*) FROM notifications WHERE user_id = ? AND notification_type = 'NEW_FOLLOWER' "
                + "AND metadata_json -> 'actorId' = 'null'::jsonb AND action_url IS NULL", s.active().id()),
                "a notificação de A continua, sem o ator excluído");
        assertEquals(0, count("SELECT count(*) FROM product_presences WHERE reported_by_user_id = ?", d));
        assertEquals(1, count("SELECT count(*) FROM product_presences WHERE product_id = ? AND reported_by_user_id IS NULL",
                s.productId()));
        assertEquals(0, count("SELECT count(*) FROM reviews WHERE user_id = ? "
                + "AND (user_coordinates IS NOT NULL OR location_accuracy_meters IS NOT NULL)", d));

        // Preservado: avaliação, nota, helpful, comentários, denúncias, auditoria, mídia, check-in, conta empresarial
        assertEquals(preservedBefore, preservedState(s));

        assertPublicReadsStillWorkWithoutIdentity(s);
    }

    @Test
    @DisplayName("Idempotência: a segunda execução é no-op e o estado final é o mesmo")
    void purgeIsIdempotent() throws Exception {
        Scenario s = scenario();
        UUID d = s.deleted().id();

        assertTrue(purgeDeletedAccountUseCase.execute(d).changedAnything());
        Map<String, Object> afterFirst = purgedState(s);

        PurgeResult second = purgeDeletedAccountUseCase.execute(d);
        assertFalse(second.changedAnything(), second.toString());
        assertEquals(afterFirst, purgedState(s));
        assertEquals(preservedState(s), preservedState(s));
    }

    @Test
    @DisplayName("Reserva de e-mail: depois do purge o endereço original continua indisponível e o domínio reservado é recusado")
    void emailStaysReservedAfterPurge() throws Exception {
        Scenario s = scenario();
        purgeDeletedAccountUseCase.execute(s.deleted().id());

        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest(s.deleted().email().toUpperCase(), "Senha@reuso1", "reuso_" + suffix(),
                                "Tentativa de reuso"))))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));

        // O valor reservado não pode ser registrado (sem colisão nem tomada antecipada da reserva)
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest(User.reservedEmailFor("outra." + suffix() + "@rewit.test"),
                                "Senha@reserva1", "reserva_" + suffix(), "Tentativa no domínio reservado"))))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INVALID_EMAIL"));

        // Nem o handle reservado (fora do padrão do cadastro)
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest("handle." + suffix() + "@rewit.test", "Senha@handle1",
                                Profile.deletedHandleFor(UUID.randomUUID()), "Tentativa de handle reservado"))))
                .andExpect(status().isBadRequest());

        // A conta purgada não autentica
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest(s.deleted().email(), s.deleted().password()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("Precondição: só DELETED é purgada; conta ativa 409 sem efeito, inexistente 404")
    void purgeRequiresDeletedAccount() throws Exception {
        TestUser active = register("purge_ativa");
        BusinessException activeEx = assertThrows(BusinessException.class, () -> purgeDeletedAccountUseCase.execute(active.id()));
        assertEquals(HttpStatus.CONFLICT, activeEx.getStatus());
        assertEquals("ACCOUNT_NOT_DELETED", activeEx.getErrorCode());
        assertEquals(active.email(), jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, active.id()));
        assertEquals(1, count("SELECT count(*) FROM auth_sessions WHERE user_id = ?", active.id()));

        BusinessException missingEx = assertThrows(BusinessException.class,
                () -> purgeDeletedAccountUseCase.execute(UUID.randomUUID()));
        assertEquals(HttpStatus.NOT_FOUND, missingEx.getStatus());
    }

    @Test
    @DisplayName("Concorrência: dois purges da mesma conta são serializados pelo lock; o segundo vira no-op")
    void concurrentPurgesAreSerialized() throws Exception {
        Scenario s = scenario();
        UUID d = s.deleted().id();
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // O primeiro purge termina o trabalho mas segura a transação (e o lock da conta) aberta
            Future<PurgeResult> first = executor.submit(() -> tx.execute(status -> {
                PurgeResult result = purgeDeletedAccountUseCase.execute(d);
                firstDone.countDown();
                try {
                    assertTrue(releaseFirst.await(30, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return result;
            }));
            assertTrue(firstDone.await(30, TimeUnit.SECONDS));

            Future<PurgeResult> second = executor.submit(() -> purgeDeletedAccountUseCase.execute(d));
            awaitLockWaiter();
            releaseFirst.countDown();

            assertTrue(first.get(30, TimeUnit.SECONDS).changedAnything());
            PurgeResult secondResult = second.get(30, TimeUnit.SECONDS);
            assertFalse(secondResult.changedAnything(), "o segundo enxerga a conta já minimizada: " + secondResult);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
        assertEquals(User.reservedEmailFor(s.deleted().email()),
                jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, d));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Leituras públicas depois do purge
    // ---------------------------------------------------------------------------------------------------------

    private void assertPublicReadsStillWorkWithoutIdentity(Scenario s) throws Exception {
        String reserved = Profile.deletedHandleFor(s.deleted().id());
        List<String> responses = List.of(
                body(get("/api/v1/reviews/{id}", s.deletedReviewId()), s.viewer()),
                body(get("/api/v1/targets/{id}/reviews", s.targetId()), s.viewer()),
                body(get("/api/v1/reviews/{id}/discussions", s.activeReviewId()), s.viewer()),
                body(get("/api/v1/discussions/{id}/replies", s.activeRootId()), s.viewer()),
                body(get("/api/v2/feed").param("size", "50"), s.viewer()),
                body(get("/api/v1/reviews/{id}/media", s.deletedReviewId()), s.viewer()),
                body(get("/api/v1/users/{id}/followers", s.active().id()), s.viewer()),
                body(get("/api/v1/me/notifications").param("size", "50"), s.active()));
        for (String response : responses) {
            for (String identifier : List.of(s.deleted().id().toString(), s.deleted().email(), s.deleted().handle(),
                    s.deleted().displayName(), reserved, User.reservedEmailFor(s.deleted().email()))) {
                assertFalse(response.contains(identifier), "identidade exposta (" + identifier + "): " + response);
            }
        }

        JsonNode detail = objectMapper.readTree(responses.get(0));
        assertEquals("Usuário excluído", detail.get("author").get("displayName").asText());
        assertTrue(detail.get("author").get("id").isNull());
        assertEquals("Avaliação histórica da conta excluída", detail.get("experienceText").asText());
        assertEquals(1, detail.get("helpfulCount").asLong());
        assertTrue(detail.get("isVerifiedOnSite").asBoolean(), "o check-in verificado continua (decisão pendente)");

        JsonNode stats = read(get("/api/v1/targets/{id}/stats", s.targetId()), s.viewer());
        assertEquals(2, stats.get("reviewsCount").asInt());

        mockMvc.perform(get("/api/v1/users/{id}", s.deleted().id()).header(HttpHeaders.AUTHORIZATION, bearer(s.viewer())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Cenário: D com todos os vínculos; A ativa; V leitora; admin
    // ---------------------------------------------------------------------------------------------------------

    private Scenario scenario() throws Exception {
        TestUser deleted = register("purgada");
        TestUser active = register("purge_ativa");
        TestUser viewer = register("purge_leitora");
        TestUser admin = admin();
        UUID d = deleted.id();
        jdbcTemplate.update("UPDATE profiles SET bio = ?, avatar_url = ? WHERE user_id = ?",
                "Bio pessoal de " + deleted.displayName(), "https://cdn.rewit.test/avatars/" + d + ".webp", d);
        login(deleted); // segunda sessão

        Place place = placeRepository.save(new Place(null, "Local Purge " + suffix(), "local-purge-" + suffix(), "RESTAURANTE",
                "Descrição", "Rua Purge, 1", "1", "Centro", "Curitiba", "PR", "BR", -25.43, -49.27, 50, "USER", false,
                null, "ACTIVE"));
        UUID targetId = rateableTargetRepository.save(new RateableTarget(UUID.randomUUID(), TargetType.PLACE)).getId();

        // Avaliação de D no local, com coordenadas: gera check-in VERIFIED e user_coordinates
        UUID deletedReviewId = UUID.fromString(read(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON)
                .content(json(new CreateReviewRequest(place.getId(), "Avaliação histórica da conta excluída", false, "PUBLIC",
                        -25.43, -49.27, 5.0, List.of(new CreateReviewTargetRequest(targetId, new BigDecimal("2.0"), "Nota"))))),
                deleted, 201).get("id").asText());
        UUID activeReviewId = UUID.fromString(read(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON)
                .content(json(new CreateReviewRequest(place.getId(), "Avaliação da conta ativa", false, "PUBLIC",
                        List.of(new CreateReviewTargetRequest(targetId, new BigDecimal("4.0"), "Nota"))))), active, 201)
                .get("id").asText());

        uploadMedia(deleted, deletedReviewId);
        perform(post("/api/v1/reviews/{id}/helpful", deletedReviewId), active, 200);
        perform(post("/api/v1/reviews/{id}/helpful", activeReviewId), deleted, 200);

        follow(deleted, active);
        follow(active, deleted);
        follow(viewer, deleted);
        follow(viewer, active);

        UUID deletedRootId = comment(deleted, activeReviewId, "Comentário da conta excluída", null);
        comment(active, activeReviewId, "Resposta da conta ativa", deletedRootId);
        UUID activeRootId = comment(active, activeReviewId, "Comentário da conta ativa", null);
        comment(deleted, activeReviewId, "Resposta da conta excluída", activeRootId);

        perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON)
                .content(json(new CreateReportRequest(activeReviewId, ReportReason.SPAM, "Denúncia feita pela conta excluída"))),
                deleted, 201);
        perform(post("/api/v1/discussions/{id}/reports", activeRootId).contentType(MediaType.APPLICATION_JSON)
                .content(json(new ReportDiscussionRequest(ReportReason.SPAM, null))), deleted, 202);

        UUID productId = UUID.fromString(read(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                .content(json(new CreateProductRequest("Produto purge " + suffix(), "Marca", null, null, "BEBIDA", null))),
                deleted, 201).get("id").asText());
        perform(post("/api/v1/products/{id}/presence", productId).contentType(MediaType.APPLICATION_JSON)
                .content(json(new AssociateProductPresenceRequest(place.getId()))), deleted, 201);

        perform(get("/api/v1/users/{id}/reputation", d), viewer, 200); // materializa o snapshot

        // Sem fluxo HTTP: dados privados, conta empresarial e auditoria em que D foi moderadora
        jdbcTemplate.update("INSERT INTO saved_items (user_id, target_id, item_type) VALUES (?, ?, 'PLACE')", d, targetId);
        jdbcTemplate.update("INSERT INTO user_interests (user_id, interest_name, category_code) VALUES (?, 'Cafés', 'CAFE')", d);
        jdbcTemplate.update("INSERT INTO user_activities (user_id, activity_type, target_type, target_id) "
                + "VALUES (?, 'VIEW', 'PLACE', ?)", d, targetId);
        jdbcTemplate.update("INSERT INTO business_accounts (user_id, corporate_name, tax_id) VALUES (?, ?, ?)",
                d, "Empresa " + suffix(), UUID.randomUUID().toString().replace("-", ""));
        jdbcTemplate.update("INSERT INTO moderation_audit_logs (review_id, moderator_user_id, action, decision, reason_code, "
                + "justification, previous_review_status, new_review_status) "
                + "VALUES (?, ?, 'REMOVE_REVIEW', 'REJECTED', 'SPAM', 'Decisão histórica tomada pela conta', 'ACTIVE', 'ACTIVE')",
                activeReviewId, d);

        perform(delete("/api/v1/admin/users/{id}", d), admin, 204);
        assertEquals(2, count("SELECT count(*) FROM auth_sessions WHERE user_id = ?", d), "sessões revogadas, ainda não purgadas");
        assertNotEquals(0, count("SELECT count(*) FROM notifications WHERE user_id = ?", d));

        return new Scenario(deleted, active, viewer, admin, targetId, deletedReviewId, activeReviewId, deletedRootId,
                activeRootId, productId);
    }

    /** O que o purge preserva, lido diretamente do banco. */
    private Map<String, Object> preservedState(Scenario s) {
        UUID d = s.deleted().id();
        return Map.ofEntries(
                Map.entry("reviews", jdbcTemplate.queryForList(
                        "SELECT id, user_id, experience_text, status, visibility, is_verified_on_site, created_at, updated_at "
                                + "FROM reviews WHERE user_id = ? ORDER BY id", d).toString()),
                Map.entry("ratings", jdbcTemplate.queryForList(
                        "SELECT rt.id, rt.rating, rt.specific_comment FROM review_targets rt JOIN reviews r ON r.id = rt.review_id "
                                + "WHERE r.user_id = ? ORDER BY rt.id", d).toString()),
                Map.entry("helpful", count("SELECT count(*) FROM review_reactions WHERE user_id = ? "
                        + "OR review_id IN (SELECT id FROM reviews WHERE user_id = ?)", d, d)),
                Map.entry("discussions", jdbcTemplate.queryForList(
                        "SELECT id, user_id, parent_id, content, status FROM review_discussions WHERE user_id = ? ORDER BY id", d)
                        .toString()),
                Map.entry("replies", count("SELECT count(*) FROM review_discussions WHERE review_id = ?", s.activeReviewId())),
                Map.entry("reviewReports", count("SELECT count(*) FROM review_reports WHERE reporter_user_id = ?", d)),
                Map.entry("discussionReports", count("SELECT count(*) FROM discussion_reports WHERE reporter_user_id = ?", d)),
                Map.entry("moderationAudit", count("SELECT count(*) FROM moderation_audit_logs WHERE moderator_user_id = ?", d)),
                Map.entry("media", jdbcTemplate.queryForList(
                        "SELECT id, object_key, status FROM review_media WHERE user_id = ? ORDER BY id", d).toString()),
                Map.entry("checkIns", jdbcTemplate.queryForList(
                        "SELECT id, status, ST_AsText(coordinates::geometry) AS coords FROM check_ins WHERE user_id = ? ORDER BY id", d)
                        .toString()),
                Map.entry("businessAccounts", count("SELECT count(*) FROM business_accounts WHERE user_id = ?", d)),
                Map.entry("targetStats", jdbcTemplate.queryForList(
                        "SELECT reviews_count, average_rating FROM rateable_target_stats WHERE target_id = ?", s.targetId())
                        .toString()));
    }

    /** O que o purge altera, para comparar execuções. */
    private Map<String, Object> purgedState(Scenario s) {
        UUID d = s.deleted().id();
        return Map.of(
                "user", jdbcTemplate.queryForMap("SELECT email, password_hash, provider_user_id, updated_at FROM users WHERE id = ?", d)
                        .toString(),
                "profile", jdbcTemplate.queryForMap(
                        "SELECT handle, display_name, bio, avatar_url, reputation_score, updated_at FROM profiles WHERE user_id = ?", d)
                        .toString(),
                "relations", count("SELECT (SELECT count(*) FROM auth_sessions WHERE user_id = ?) "
                        + "+ (SELECT count(*) FROM user_follows WHERE follower_user_id = ? OR followed_user_id = ?) "
                        + "+ (SELECT count(*) FROM notifications WHERE user_id = ?)", d, d, d, d),
                "othersNotifications", jdbcTemplate.queryForList(
                        "SELECT id, metadata_json::text, action_url FROM notifications WHERE user_id = ? ORDER BY id", s.active().id())
                        .toString());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private void awaitLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (count("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock' AND query ILIKE '%from users%' AND query ILIKE '%for%update%'") > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("O segundo purge não chegou a esperar o lock da conta");
    }

    private UUID comment(TestUser author, UUID reviewId, String content, UUID parentId) throws Exception {
        return UUID.fromString(read(post("/api/v1/reviews/{id}/discussions", reviewId).contentType(MediaType.APPLICATION_JSON)
                .content(json(new CreateDiscussionRequest(content, parentId))), author, 201).get("id").asText());
    }

    private void follow(TestUser follower, TestUser target) throws Exception {
        perform(post("/api/v1/users/{id}/follow", target.id()), follower, 200);
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
        String suffix = suffix();
        String email = prefix + "." + suffix + "@rewit.test";
        String password = "Senha@" + suffix;
        String handle = (prefix + "_" + suffix).toLowerCase();
        String displayName = "Nome " + prefix + " " + suffix;
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest(email, password, handle, displayName))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return new TestUser(UUID.fromString(node.get("user").get("id").asText()), email, password, handle, displayName,
                node.get("accessToken").asText());
    }

    private TestUser login(TestUser user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isOk())
                .andReturn();
        return new TestUser(user.id(), user.email(), user.password(), user.handle(), user.displayName(),
                objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("accessToken").asText());
    }

    private TestUser admin() throws Exception {
        TestUser user = register("purge_admin");
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.id());
        return login(user);
    }

    private void perform(MockHttpServletRequestBuilder request, TestUser user, int expectedStatus) throws Exception {
        mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(user))).andExpect(status().is(expectedStatus));
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

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String bearer(TestUser user) {
        return "Bearer " + user.accessToken();
    }
}
