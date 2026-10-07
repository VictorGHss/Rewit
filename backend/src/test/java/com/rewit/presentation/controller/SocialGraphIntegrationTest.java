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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Perfil público e grafo social (C3) pela API real com PostgreSQL: perfil, follow/unfollow, listas paginadas,
 * contadores, visibilidade de contas não operacionais, navegação autor → perfil e concorrência.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Perfil público e grafo social (C3): API real com PostgreSQL")
class SocialGraphIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private RateableTargetRepository rateableTargetRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record TestUser(UUID id, String email, String password, String handle, String displayName, String accessToken) {}

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Perfil
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Perfil: próprio e de outro usuário, só campos públicos, contadores e isFollowing")
    void publicProfile() throws Exception {
        TestUser owner = register("perfil_dono");
        TestUser viewer = register("perfil_leitor");
        follow(viewer, owner).andExpect(status().isOk());

        JsonNode own = read(get("/api/v1/users/{id}", owner.id()), owner);
        assertFalse(own.get("isFollowing").asBoolean(), "o próprio perfil nunca segue a si mesmo");
        assertEquals(1, own.get("stats").get("followersCount").asLong());

        JsonNode other = read(get("/api/v1/users/{id}", owner.id()), viewer);
        assertTrue(other.get("isFollowing").asBoolean());
        assertEquals(owner.handle(), other.get("handle").asText());
        assertEquals(owner.displayName(), other.get("displayName").asText());

        // Só campos públicos: nenhum dado de conta ou credencial
        assertEquals(Set.of("id", "handle", "displayName", "bio", "avatarUrl", "stats", "isFollowing"), fieldNames(other));
        String body = other.toString();
        assertFalse(body.contains(owner.email()));
        assertFalse(body.contains("password") || body.contains("email") || body.contains("accountStatus") || body.contains("role"));
    }

    @Test
    @DisplayName("Perfil inexistente, DELETED, DEACTIVATED e SUSPENDED: mesmo 404 USER_NOT_FOUND, sem identidade")
    void hiddenProfilesAreIndistinguishable() throws Exception {
        TestUser viewer = register("perfil_oculto_leitor");
        TestUser deleted = register("perfil_excluido");
        TestUser deactivated = register("perfil_desativado");
        TestUser suspended = register("perfil_suspenso");
        TestUser admin = admin();
        perform(delete("/api/v1/admin/users/{id}", deleted.id()), admin).andExpect(status().isNoContent());
        perform(post("/api/v1/me/deactivate"), deactivated).andExpect(status().isNoContent());
        perform(post("/api/v1/admin/users/{id}/suspend", suspended.id()), admin).andExpect(status().isNoContent());

        JsonNode missing = notFound("/api/v1/users/{id}", UUID.randomUUID(), viewer);
        for (TestUser hidden : List.of(deleted, deactivated, suspended)) {
            for (String path : List.of("/api/v1/users/{id}", "/api/v1/users/{id}/followers", "/api/v1/users/{id}/following",
                    "/api/v1/users/{id}/follow")) {
                JsonNode body = notFound(path, hidden.id(), viewer);
                assertEquals(missing.get("code"), body.get("code"), path);
                assertEquals(missing.get("detail"), body.get("detail"), path);
                assertFalse(body.toString().contains(hidden.handle()), path);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Follow / unfollow
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Follow e unfollow: idempotentes, sem duplicar vínculo nem notificação; self-follow 400")
    void followAndUnfollow() throws Exception {
        TestUser follower = register("segue");
        TestUser target = register("seguido");

        follow(follower, target).andExpect(status().isOk()).andExpect(jsonPath("$.following").value(true));
        follow(follower, target).andExpect(status().isOk()).andExpect(jsonPath("$.following").value(true));
        assertEquals(1, follows(follower, target));
        assertEquals(1, count("SELECT count(*) FROM notifications WHERE user_id = ? AND notification_type = 'NEW_FOLLOWER'",
                target.id()), "a repetição não notifica de novo");
        perform(get("/api/v1/users/{id}/follow", target.id()), follower).andExpect(jsonPath("$.following").value(true));

        unfollow(follower, target).andExpect(status().isOk()).andExpect(jsonPath("$.following").value(false));
        unfollow(follower, target).andExpect(status().isOk()).andExpect(jsonPath("$.following").value(false));
        assertEquals(0, follows(follower, target));
        perform(get("/api/v1/users/{id}/follow", target.id()), follower).andExpect(jsonPath("$.following").value(false));

        follow(follower, follower).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SELF_FOLLOW_FORBIDDEN"));
        perform(get("/api/v1/users/{id}/follow", follower.id()), follower).andExpect(jsonPath("$.following").value(false));
    }

    @Test
    @DisplayName("Follow: ator não operacional 401 ACCOUNT_DISABLED; alvo DELETED ou inativo 404; unfollow de inativo segue permitido")
    void followEligibility() throws Exception {
        TestUser actor = register("ator");
        TestUser inactiveActor = register("ator_inativo");
        TestUser deletedTarget = register("alvo_excluido");
        TestUser deactivatedTarget = register("alvo_desativado");
        TestUser admin = admin();
        follow(actor, deactivatedTarget).andExpect(status().isOk());

        perform(post("/api/v1/me/deactivate"), inactiveActor).andExpect(status().isNoContent());
        follow(inactiveActor, actor).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        perform(delete("/api/v1/admin/users/{id}", deletedTarget.id()), admin).andExpect(status().isNoContent());
        follow(actor, deletedTarget).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        unfollow(actor, deletedTarget).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        perform(post("/api/v1/me/deactivate"), deactivatedTarget).andExpect(status().isNoContent());
        follow(actor, deactivatedTarget).andExpect(status().isNotFound());
        // O usuário ainda pode limpar o próprio grafo de uma conta desativada
        unfollow(actor, deactivatedTarget).andExpect(status().isOk()).andExpect(jsonPath("$.following").value(false));
        assertEquals(0, follows(actor, deactivatedTarget));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Listas
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Listas: paginação e totalElements filtrados no banco; DELETED invisível; contadores consistentes")
    void followListsPaginationAndCounters() throws Exception {
        TestUser target = register("lista_alvo");
        TestUser admin = admin();
        List<TestUser> followers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            TestUser follower = register("lista_seguidor");
            follow(follower, target).andExpect(status().isOk());
            followers.add(follower);
        }
        TestUser deletedFollower = followers.get(1);
        perform(delete("/api/v1/admin/users/{id}", deletedFollower.id()), admin).andExpect(status().isNoContent());
        follow(target, followers.get(0)).andExpect(status().isOk());
        follow(target, deletedFollower).andExpect(status().isNotFound());

        JsonNode page0 = read(get("/api/v1/users/{id}/followers", target.id()).param("page", "0").param("size", "2"), target);
        JsonNode page1 = read(get("/api/v1/users/{id}/followers", target.id()).param("page", "1").param("size", "2"), target);
        assertEquals(3, page0.get("totalElements").asLong(), "a conta excluída não conta");
        assertEquals(2, page0.get("totalPages").asInt());
        assertFalse(page0.get("isLast").asBoolean());
        assertEquals(2, page0.get("content").size());
        assertEquals(1, page1.get("content").size());
        assertTrue(page1.get("isLast").asBoolean());

        Set<String> listed = new TreeSet<>();
        for (JsonNode page : List.of(page0, page1)) {
            for (JsonNode item : page.get("content")) {
                listed.add(item.get("id").asText());
                assertEquals(Set.of("id", "handle", "displayName", "avatarUrl", "followedAt"), fieldNames(item));
            }
        }
        assertEquals(new TreeSet<>(List.of(followers.get(0).id().toString(), followers.get(2).id().toString(),
                followers.get(3).id().toString())), listed);
        assertFalse((page0.toString() + page1).contains(deletedFollower.id().toString()));

        JsonNode following = read(get("/api/v1/users/{id}/following", target.id()), target);
        assertEquals(1, following.get("totalElements").asLong());
        assertEquals(followers.get(0).id().toString(), following.get("content").get(0).get("id").asText());

        JsonNode profile = read(get("/api/v1/users/{id}", target.id()), followers.get(2));
        assertEquals(3, profile.get("stats").get("followersCount").asLong(), "contador igual ao total da lista");
        assertEquals(1, profile.get("stats").get("followingCount").asLong());
        assertTrue(profile.get("isFollowing").asBoolean());

        perform(get("/api/v1/users/{id}/followers", target.id()).param("size", "51"), target)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAGE_SIZE_EXCEEDED"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Navegação autor → perfil
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Autor visível de review leva ao perfil público; autor DELETED não tem id para navegar")
    void reviewAuthorLinksToProfile() throws Exception {
        TestUser author = register("autor_link");
        TestUser deletedAuthor = register("autor_link_excluido");
        TestUser viewer = register("autor_link_leitor");
        TestUser admin = admin();
        UUID visibleReview = createReview(author);
        UUID deletedReview = createReview(deletedAuthor);
        perform(delete("/api/v1/admin/users/{id}", deletedAuthor.id()), admin).andExpect(status().isNoContent());

        JsonNode visible = read(get("/api/v1/reviews/{id}", visibleReview), viewer).get("author");
        JsonNode profile = read(get("/api/v1/users/{id}", UUID.fromString(visible.get("id").asText())), viewer);
        assertEquals(author.handle(), profile.get("handle").asText());

        JsonNode hidden = read(get("/api/v1/reviews/{id}", deletedReview), viewer).get("author");
        assertTrue(hidden.get("id").isNull());
        assertTrue(hidden.get("handle").isNull());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Concorrência (o banco é a autoridade; sem locks na aplicação)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Concorrência: dois follows iguais geram um vínculo; o segundo espera o primeiro e responde 200")
    void concurrentDuplicateFollows() throws Exception {
        TestUser follower = register("conc_follow");
        TestUser target = register("conc_follow_alvo");

        runWhileHolding("INSERT INTO user_follows (id, follower_user_id, followed_user_id, created_at) VALUES (?, ?, ?, now())",
                List.of(UUID.randomUUID(), follower.id(), target.id()),
                () -> follow(follower, target).andReturn(), 200);

        assertEquals(1, follows(follower, target));
    }

    @Test
    @DisplayName("Concorrência: dois unfollows simultâneos; o segundo encontra a linha já removida e responde 200")
    void concurrentUnfollows() throws Exception {
        TestUser follower = register("conc_unfollow");
        TestUser target = register("conc_unfollow_alvo");
        follow(follower, target).andExpect(status().isOk());

        runWhileHolding("DELETE FROM user_follows WHERE follower_user_id = ? AND followed_user_id = ?",
                List.of(follower.id(), target.id()),
                () -> unfollow(follower, target).andReturn(), 200);

        assertEquals(0, follows(follower, target));
    }

    @Test
    @DisplayName("Concorrência: unfollow em curso e follow do mesmo par terminam sem erro e sem vínculo duplicado")
    void concurrentUnfollowAndFollow() throws Exception {
        TestUser follower = register("conc_misto");
        TestUser target = register("conc_misto_alvo");
        follow(follower, target).andExpect(status().isOk());

        runWhileHolding("DELETE FROM user_follows WHERE follower_user_id = ? AND followed_user_id = ?",
                List.of(follower.id(), target.id()),
                () -> follow(follower, target).andReturn(), 200);

        assertEquals(1, follows(follower, target), "o follow é ordenado depois do unfollow confirmado");
    }

    /**
     * Mantém uma escrita em {@code user_follows} sem confirmar, dispara a requisição em outra thread, espera que ela
     * fique bloqueada pelo lock da linha e só então confirma a escrita.
     */
    private void runWhileHolding(String sql, List<Object> params, ThrowingRequest request, int expectedStatus)
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    statement.setObject(i + 1, params.get(i));
                }
                assertEquals(1, statement.executeUpdate());
            }
            Future<MvcResult> future = executor.submit(request::run);
            awaitLockWaiter();
            holder.commit();
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            assertEquals(expectedStatus, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingRequest {
        MvcResult run() throws Exception;
    }

    private void awaitLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (count("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock' AND query ILIKE '%user_follows%'") > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("A requisição não chegou a esperar o lock do vínculo");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private UUID createReview(TestUser author) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = placeRepository.save(new Place(null, "Local Social " + suffix, "local-social-" + suffix, "RESTAURANTE",
                "Descrição", "Rua Social, 1", "1", "Centro", "Curitiba", "PR", "BR", -25.43, -49.27, 50, "USER", false,
                null, "ACTIVE"));
        RateableTarget target = rateableTargetRepository.save(new RateableTarget(UUID.randomUUID(), TargetType.PLACE));
        CreateReviewRequest request = new CreateReviewRequest(place.getId(), "Avaliação para navegação", false, "PUBLIC",
                List.of(new CreateReviewTargetRequest(target.getId(), new BigDecimal("4.0"), "Nota")));
        MvcResult result = perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)), author).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asText());
    }

    private ResultActions follow(TestUser actor, TestUser target) throws Exception {
        return perform(post("/api/v1/users/{id}/follow", target.id()), actor);
    }

    private ResultActions unfollow(TestUser actor, TestUser target) throws Exception {
        return perform(delete("/api/v1/users/{id}/follow", target.id()), actor);
    }

    private long follows(TestUser follower, TestUser target) {
        return count("SELECT count(*) FROM user_follows WHERE follower_user_id = ? AND followed_user_id = ?",
                follower.id(), target.id());
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, TestUser user) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()));
    }

    private JsonNode read(MockHttpServletRequestBuilder request, TestUser user) throws Exception {
        MvcResult result = perform(request, user).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode notFound(String path, UUID id, TestUser user) throws Exception {
        MvcResult result = perform(get(path, id), user)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private TestUser register(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "." + suffix + "@rewit.test";
        String password = "Senha@" + suffix;
        String handle = (prefix + "_" + suffix).toLowerCase();
        if (handle.length() > 30) {
            handle = handle.substring(handle.length() - 30);
        }
        String displayName = "Nome " + suffix;
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, password, handle, displayName))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return new TestUser(UUID.fromString(node.get("user").get("id").asText()), email, password, handle, displayName,
                node.get("accessToken").asText());
    }

    private TestUser admin() throws Exception {
        TestUser user = register("social_admin");
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.id());
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isOk())
                .andReturn();
        return new TestUser(user.id(), user.email(), user.password(), user.handle(), user.displayName(),
                objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("accessToken").asText());
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
