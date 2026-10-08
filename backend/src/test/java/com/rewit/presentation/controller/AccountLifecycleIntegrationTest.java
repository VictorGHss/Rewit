package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ciclo de vida da conta (C2) pela API real, com Spring Security e PostgreSQL: transições do usuário e da
 * administração, revogação de sessões, tokens anteriores à mudança e o contrato que não revela o estado da conta.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Ciclo de vida da conta (C2): API, sessões e concorrência")
class AccountLifecycleIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record TestUser(UUID id, String email, String password, String accessToken, String refreshToken) {}

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Desativação e reativação pelo próprio usuário
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("ACTIVE -> DEACTIVATED: 204, sessões revogadas, token anterior não muta, refresh e login negados")
    void selfDeactivationRevokesSessionsAndBlocksStaleToken() throws Exception {
        TestUser user = register("desativa");
        TestUser target = register("desativa_alvo");
        login(user); // segunda sessão: toda sessão é revogada, não só a do token usado

        mockMvc.perform(post("/api/v1/me/deactivate").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());

        assertAccountState(user.id(), "DEACTIVATED", false, false);
        assertEquals(2, sessions(user.id()));
        assertEquals(0, activeSessions(user.id()));

        // O access token continua com assinatura válida até expirar, mas a mutação lê o estado atual da conta
        assertAccountDisabled(follow(user, target));
        assertAccountDisabled(mockMvc.perform(post("/api/v1/me/deactivate").header(HttpHeaders.AUTHORIZATION, bearer(user))));
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RefreshRequest(user.refreshToken()))))
                .andExpect(status().isUnauthorized());
        assertInvalidCredentials(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(new LoginRequest(user.email(), user.password())))));
        assertEquals(0, count("SELECT count(*) FROM user_follows WHERE follower_user_id = ?", user.id()));
    }

    @Test
    @DisplayName("DEACTIVATED -> ACTIVE: reativação com as credenciais emite tokens e a conta volta a operar")
    void selfReactivationRestoresAccount() throws Exception {
        TestUser user = register("reativa");
        TestUser target = register("reativa_alvo");
        deactivate(user);

        MvcResult result = reactivate(user.email(), user.password())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.id").value(user.id().toString()))
                .andReturn();

        assertAccountState(user.id(), "ACTIVE", true, false);
        assertEquals(1, activeSessions(user.id()), "só a sessão emitida na reativação");

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        TestUser reactivated = new TestUser(user.id(), user.email(), user.password(),
                body.get("accessToken").asText(), body.get("refreshToken").asText());
        follow(reactivated, target).andExpect(status().isOk());

        // O token emitido antes da desativação continua sem valor para refresh
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RefreshRequest(user.refreshToken()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Reativação não revela o estado: senha errada, suspensa, excluída e inexistente têm a mesma resposta")
    void reactivationDoesNotLeakAccountState() throws Exception {
        TestUser admin = admin("reativa_admin");
        TestUser deactivated = register("reativa_senha");
        TestUser suspended = register("reativa_suspensa");
        TestUser deleted = register("reativa_excluida");
        deactivate(deactivated);
        adminAction(admin, suspended, "suspend").andExpect(status().isNoContent());
        adminDelete(admin, deleted).andExpect(status().isNoContent());

        String wrongPassword = body(reactivate(deactivated.email(), "SenhaErrada123!"));
        String suspendedBody = body(reactivate(suspended.email(), suspended.password()));
        String deletedBody = body(reactivate(deleted.email(), deleted.password()));
        String missingBody = body(reactivate("inexistente." + UUID.randomUUID() + "@rewit.test", "Qualquer123!"));

        for (String response : new String[]{wrongPassword, suspendedBody, deletedBody, missingBody}) {
            JsonNode node = objectMapper.readTree(response);
            assertEquals(401, node.get("status").asInt());
            assertEquals("INVALID_CREDENTIALS", node.get("code").asText());
            assertEquals("Credenciais inválidas", node.get("detail").asText());
            assertFalse(response.contains("SUSPENDED") || response.contains("DELETED") || response.contains("DEACTIVATED"),
                    response);
        }
        assertAccountState(deactivated.id(), "DEACTIVATED", false, false);
        assertAccountState(suspended.id(), "SUSPENDED", false, false);
        assertAccountState(deleted.id(), "DELETED", false, true);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Ciclo de vida administrativo
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("ACTIVE -> SUSPENDED -> ACTIVE: suspensão revoga sessões e só a administração a reverte")
    void suspensionIsOnlyReversedByAdmin() throws Exception {
        TestUser admin = admin("suspende_admin");
        TestUser user = register("suspensa");
        TestUser target = register("suspensa_alvo");

        adminAction(admin, user, "suspend").andExpect(status().isNoContent());
        assertAccountState(user.id(), "SUSPENDED", false, false);
        assertEquals(0, activeSessions(user.id()));

        assertAccountDisabled(follow(user, target));
        // O usuário não reativa nem desativa uma conta suspensa
        assertInvalidCredentials(reactivate(user.email(), user.password()));
        assertAccountDisabled(mockMvc.perform(post("/api/v1/me/deactivate").header(HttpHeaders.AUTHORIZATION, bearer(user))));
        assertAccountState(user.id(), "SUSPENDED", false, false);

        // Idempotente
        adminAction(admin, user, "suspend").andExpect(status().isNoContent());

        adminAction(admin, user, "reinstate").andExpect(status().isNoContent());
        assertAccountState(user.id(), "ACTIVE", true, false);
        follow(login(user), target).andExpect(status().isOk());
    }

    @Test
    @DisplayName("DEACTIVATED -> SUSPENDED é permitido; a reversão administrativa não sobrepõe uma desativação")
    void suspensionAppliesToDeactivatedAccounts() throws Exception {
        TestUser admin = admin("desat_admin");
        TestUser user = register("desat_suspensa");
        deactivate(user);

        assertTransitionDenied(adminAction(admin, user, "reinstate"));
        assertAccountState(user.id(), "DEACTIVATED", false, false);

        adminAction(admin, user, "suspend").andExpect(status().isNoContent());
        assertAccountState(user.id(), "SUSPENDED", false, false);
        assertInvalidCredentials(reactivate(user.email(), user.password()));
    }

    @Test
    @DisplayName("ACTIVE, DEACTIVATED e SUSPENDED -> DELETED; DELETED não volta a nenhum estado")
    void deletionIsFinal() throws Exception {
        TestUser admin = admin("exclui_admin");
        TestUser active = register("exclui_ativa");
        TestUser deactivated = register("exclui_desativada");
        TestUser suspended = register("exclui_suspensa");
        deactivate(deactivated);
        adminAction(admin, suspended, "suspend").andExpect(status().isNoContent());

        for (TestUser user : new TestUser[]{active, deactivated, suspended}) {
            adminDelete(admin, user).andExpect(status().isNoContent());
            assertAccountState(user.id(), "DELETED", false, true);
            assertEquals(0, activeSessions(user.id()));
        }

        assertTransitionDenied(adminAction(admin, active, "reinstate"));
        assertTransitionDenied(adminAction(admin, active, "suspend"));
        assertInvalidCredentials(reactivate(active.email(), active.password()));
        adminDelete(admin, active).andExpect(status().isNoContent()); // idempotente
        assertAccountState(active.id(), "DELETED", false, true);
    }

    @Test
    @DisplayName("Endpoints administrativos: só ADMIN atual, não sobre a própria conta, alvo inexistente 404")
    void adminEndpointsAuthorization() throws Exception {
        TestUser admin = admin("autz_admin");
        TestUser moderator = register("autz_moderador");
        jdbcTemplate.update("UPDATE users SET role = 'MODERATOR' WHERE id = ?", moderator.id());
        moderator = login(moderator);
        TestUser user = register("autz_usuario");
        TestUser target = register("autz_alvo");

        adminAction(user, target, "suspend").andExpect(status().isForbidden());
        adminAction(moderator, target, "suspend").andExpect(status().isForbidden());
        adminDelete(user, target).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/users/{id}/suspend", target.id())).andExpect(status().isUnauthorized());
        assertAccountState(target.id(), "ACTIVE", true, false);

        adminAction(admin, admin, "suspend")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_ACCOUNT_LIFECYCLE_FORBIDDEN"));
        mockMvc.perform(post("/api/v1/admin/users/{id}/suspend", UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        // Role rebaixada no banco: o JWT ainda diz ADMIN, o caso de uso confirma a role atual
        jdbcTemplate.update("UPDATE users SET role = 'USER' WHERE id = ?", admin.id());
        adminAction(admin, target, "suspend")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertAccountState(target.id(), "ACTIVE", true, false);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Concorrência
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Concorrência: suspensão confirmada durante a reativação vence; a conta não volta a ACTIVE (sem lost update)")
    void concurrentSuspensionWinsOverReactivation() throws Exception {
        TestUser user = register("concorrencia");
        deactivate(user);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection suspension = dataSource.getConnection()) {
            suspension.setAutoCommit(false);
            try (PreparedStatement ps = suspension.prepareStatement(
                    "UPDATE users SET account_status = 'SUSPENDED' WHERE id = ? AND account_status = 'DEACTIVATED'")) {
                ps.setObject(1, user.id());
                assertEquals(1, ps.executeUpdate());
            }

            // A reativação lê a conta ainda DEACTIVATED (a suspensão não foi confirmada), verifica a senha e espera
            // o lock da linha. Sem o lock e a releitura, gravaria ACTIVE por cima da suspensão.
            Future<MvcResult> reactivation = executor.submit(() -> reactivate(user.email(), user.password()).andReturn());
            awaitLockWaiter();
            suspension.commit();

            MvcResult result = reactivation.get(30, TimeUnit.SECONDS);
            assertEquals(401, result.getResponse().getStatus());
            assertEquals("INVALID_CREDENTIALS",
                    objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText());
        } finally {
            executor.shutdownNow();
        }

        assertAccountState(user.id(), "SUSPENDED", false, false);
        assertEquals(0, activeSessions(user.id()));
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
        throw new AssertionError("A reativação não chegou a esperar o lock da conta");
    }

    private TestUser register(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "." + suffix + "@rewit.test";
        String password = "Senha@" + suffix;
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest(email, password, prefix + "_" + suffix, "Conta " + suffix))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(UUID.fromString(node.get("user").get("id").asText()), email, password,
                node.get("accessToken").asText(), node.get("refreshToken").asText());
    }

    private TestUser login(TestUser user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(user.id(), user.email(), user.password(),
                node.get("accessToken").asText(), node.get("refreshToken").asText());
    }

    private TestUser admin(String prefix) throws Exception {
        TestUser user = register(prefix);
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.id());
        return login(user);
    }

    private void deactivate(TestUser user) throws Exception {
        mockMvc.perform(post("/api/v1/me/deactivate").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());
    }

    private ResultActions reactivate(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/reactivate").contentType(MediaType.APPLICATION_JSON)
                .content(json(new LoginRequest(email, password))));
    }

    private ResultActions follow(TestUser actor, TestUser target) throws Exception {
        return mockMvc.perform(post("/api/v1/users/{id}/follow", target.id()).header(HttpHeaders.AUTHORIZATION, bearer(actor)));
    }

    private ResultActions adminAction(TestUser actor, TestUser target, String action) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/users/{id}/" + action, target.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(actor)));
    }

    private ResultActions adminDelete(TestUser actor, TestUser target) throws Exception {
        return mockMvc.perform(delete("/api/v1/admin/users/{id}", target.id()).header(HttpHeaders.AUTHORIZATION, bearer(actor)));
    }

    private void assertAccountState(UUID userId, String status, boolean isActive, boolean deleted) {
        var row = jdbcTemplate.queryForMap("SELECT account_status, is_active, deleted_at FROM users WHERE id = ?", userId);
        assertEquals(status, row.get("account_status"));
        assertEquals(isActive, row.get("is_active"));
        if (deleted) {
            assertNotNull(row.get("deleted_at"));
        } else {
            assertNull(row.get("deleted_at"));
        }
    }

    private static void assertAccountDisabled(ResultActions actions) throws Exception {
        actions.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    private static void assertInvalidCredentials(ResultActions actions) throws Exception {
        actions.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    private static void assertTransitionDenied(ResultActions actions) throws Exception {
        actions.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACCOUNT_STATUS_TRANSITION_DENIED"));
    }

    private static String body(ResultActions actions) throws Exception {
        return actions.andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
    }

    private long activeSessions(UUID userId) {
        return count("SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", userId);
    }

    private long sessions(UUID userId) {
        return count("SELECT count(*) FROM auth_sessions WHERE user_id = ?", userId);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static String bearer(TestUser user) {
        return "Bearer " + user.accessToken();
    }
}
