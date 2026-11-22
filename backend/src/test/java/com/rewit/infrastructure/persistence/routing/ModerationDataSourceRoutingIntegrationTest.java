package com.rewit.infrastructure.persistence.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.AppTransactionPort;
import com.rewit.application.port.MediaLifecycleTransactionPort;
import com.rewit.application.port.ModerationTransactionPort;
import com.rewit.config.PrivilegedTestDatabase;
import com.rewit.presentation.dto.auth.LoginRequest;
import com.rewit.presentation.dto.auth.RegisterRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testes de integração do roteamento transacional entre {@code rewit_app} e {@code rewit_mod} (S3-A13-F2 §6.3).
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("S3-A13-F2: roteamento transacional de DataSource (rewit_app vs rewit_mod)")
class ModerationDataSourceRoutingIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AppTransactionPort appTransactionPort;
    @Autowired private MediaLifecycleTransactionPort mediaLifecycleTransactionPort;
    @Autowired private ModerationTransactionPort moderationTransactionPort;
    @Autowired private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<UUID> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        DatabaseRoleContext.clear();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @AfterEach
    void tearDown() {
        DatabaseRoleContext.clear();
        for (UUID userId : createdUserIds) {
            PrivilegedTestDatabase.jdbc().update("DELETE FROM auth_sessions WHERE user_id = ?", userId);
            PrivilegedTestDatabase.jdbc().update("DELETE FROM users WHERE id = ?", userId);
        }
        createdUserIds.clear();
    }

    @Test
    @DisplayName("Transação pública executa como rewit_app; @ModerationTransactional executa como rewit_mod na mesma conexão física")
    void routesPublicToAppAndModerationToModOnSingleConnection() {
        assertEquals(DatabaseRole.APP, DatabaseRoleContext.current());

        Map<String, Object> appIdentity = appTransactionPort.execute(() -> {
            assertEquals(DatabaseRole.APP, DatabaseRoleContext.current());
            return jdbcTemplate.queryForMap(
                    "SELECT session_user::text AS su, current_user::text AS cu, pg_backend_pid() AS pid");
        });
        assertEquals("rewit_app", appIdentity.get("su"));
        assertEquals("rewit_app", appIdentity.get("cu"));
        assertEquals(DatabaseRole.APP, DatabaseRoleContext.current());

        Map<String, Object> modIdentity = moderationTransactionPort.execute(() -> {
            assertEquals(DatabaseRole.MODERATION, DatabaseRoleContext.current());
            Map<String, Object> first = jdbcTemplate.queryForMap(
                    "SELECT session_user::text AS su, current_user::text AS cu, pg_backend_pid() AS pid, txid_current() AS txid");
            Map<String, Object> second = jdbcTemplate.queryForMap(
                    "SELECT session_user::text AS su, current_user::text AS cu, pg_backend_pid() AS pid, txid_current() AS txid");
            assertEquals(first.get("pid"), second.get("pid"),
                    "Comandos dentro da mesma @ModerationTransactional devem compartilhar a mesma conexão física");
            assertEquals(first.get("txid"), second.get("txid"),
                    "Comandos dentro da mesma @ModerationTransactional devem compartilhar o mesmo txid");
            return first;
        });

        assertEquals("rewit_mod", modIdentity.get("su"));
        assertEquals("rewit_mod", modIdentity.get("cu"));
        assertNotEquals(appIdentity.get("pid"), modIdentity.get("pid"),
                "Pools de rewit_app e rewit_mod devem usar conexões físicas distintas");
        assertEquals(DatabaseRole.APP, DatabaseRoleContext.current(),
                "Contexto deve voltar para APP imediatamente após o encerramento da transação de moderação");

        Map<String, Object> mediaIdentity = mediaLifecycleTransactionPort.execute(() -> {
            assertEquals(DatabaseRole.MEDIA_LIFECYCLE, DatabaseRoleContext.current());
            Map<String, Object> first = jdbcTemplate.queryForMap(
                    "SELECT session_user::text AS su, current_user::text AS cu, pg_backend_pid() AS pid, txid_current() AS txid");
            Map<String, Object> second = jdbcTemplate.queryForMap(
                    "SELECT session_user::text AS su, current_user::text AS cu, pg_backend_pid() AS pid, txid_current() AS txid");
            assertEquals(first.get("pid"), second.get("pid"),
                    "Comandos dentro da mesma @MediaLifecycleTransactional devem compartilhar a mesma conexão física");
            assertEquals(first.get("txid"), second.get("txid"),
                    "Comandos dentro da mesma @MediaLifecycleTransactional devem compartilhar o mesmo txid");
            return first;
        });

        assertEquals("rewit_media", mediaIdentity.get("su"));
        assertEquals("rewit_media", mediaIdentity.get("cu"));
        assertNotEquals(appIdentity.get("pid"), mediaIdentity.get("pid"),
                "Pools de rewit_app e rewit_media devem usar conexões físicas distintas");
        assertNotEquals(modIdentity.get("pid"), mediaIdentity.get("pid"),
                "Pools de rewit_mod e rewit_media devem usar conexões físicas distintas");
        assertEquals(DatabaseRole.APP, DatabaseRoleContext.current(),
                "Contexto deve voltar para APP imediatamente após o encerramento da transação de mídia");

        // Próxima chamada na mesma thread volta a executar como rewit_app
        String afterUser = jdbcTemplate.queryForObject("SELECT session_user::text", String.class);
        assertEquals("rewit_app", afterUser);
    }

    @Test
    @DisplayName("Exceção em @ModerationTransactional limpa o contexto de thread e não vaza rewit_mod para a chamada seguinte")
    void exceptionInModerationTransactionRollsBackAndRestoresAppContext() {
        UUID targetId = PrivilegedTestDatabase.createUser("routing_rollback", "USER");
        createdUserIds.add(targetId);

        assertThrows(RuntimeException.class, () -> moderationTransactionPort.run(() -> {
            assertEquals("rewit_mod", jdbcTemplate.queryForObject("SELECT session_user::text", String.class));
            jdbcTemplate.update("UPDATE users SET account_status = 'SUSPENDED', is_active = FALSE WHERE id = ?", targetId);
            throw new IllegalStateException("Falha simulada após escrita em rewit_mod");
        }));

        assertEquals(DatabaseRole.APP, DatabaseRoleContext.current(),
                "DatabaseRoleContext deve ser limpo em finally mesmo após exceção");
        assertFalse(DatabaseRoleContext.isModerationActive());

        // Rollback efetivado: usuário permanece ACTIVE
        String status = PrivilegedTestDatabase.jdbc().queryForObject(
                "SELECT account_status FROM users WHERE id = ?", String.class, targetId);
        assertEquals("ACTIVE", status);

        // Próxima transação na mesma thread usa rewit_app
        String nextSessionUser = appTransactionPort.execute(
                () -> jdbcTemplate.queryForObject("SELECT session_user::text", String.class));
        assertEquals("rewit_app", nextSessionUser);
    }

    @Test
    @DisplayName("Fail-closed: entrar em @ModerationTransactional dentro de transação ativa de rewit_app lança IllegalStateException")
    void nestedModerationInsideActiveAppTransactionFailsClosed() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                appTransactionPort.run(() -> {
                    assertEquals("rewit_app", jdbcTemplate.queryForObject("SELECT session_user::text", String.class));
                    moderationTransactionPort.run(() ->
                            jdbcTemplate.queryForObject("SELECT session_user::text", String.class));
                })
        );

        assertTrue(ex.getMessage().contains("APP") || ex.getMessage().contains("transação ativa"),
                "Mensagem de erro deve indicar bloqueio fail-closed: " + ex.getMessage());
        assertEquals(DatabaseRole.APP, DatabaseRoleContext.current());
        assertEquals("rewit_app", jdbcTemplate.queryForObject("SELECT session_user::text", String.class));

        // Fail-closed também para APP -> MEDIA_LIFECYCLE, MEDIA_LIFECYCLE -> MODERATION e MODERATION -> MEDIA_LIFECYCLE
        assertThrows(IllegalStateException.class, () ->
                appTransactionPort.run(() -> mediaLifecycleTransactionPort.run(() -> {})));
        assertThrows(IllegalStateException.class, () ->
                mediaLifecycleTransactionPort.run(() -> moderationTransactionPort.run(() -> {})));
        assertThrows(IllegalStateException.class, () ->
                moderationTransactionPort.run(() -> mediaLifecycleTransactionPort.run(() -> {})));
        assertEquals(DatabaseRole.APP, DatabaseRoleContext.current());
    }

    @Test
    @DisplayName("JWT de USER comum e JWT obsoleto de ADMIN rebaixado não ativam rewit_mod e não persistem alterações")
    void regularUserAndDemotedAdminJwtsCannotExecuteModeration() throws Exception {
        TestUser regularUser = registerAndLogin("routing_regular");
        TestUser target = registerAndLogin("routing_target");
        TestUser admin = registerAndLogin("routing_demoted_admin");
        PrivilegedTestDatabase.assignRole(admin.userId(), "ADMIN");
        String staleAdminJwt = login(admin.email(), admin.password());
        // Rebaixa o admin no banco após emissão do JWT
        PrivilegedTestDatabase.assignRole(admin.userId(), "USER");

        // USER comum recebe 403
        mockMvc.perform(post("/api/v1/admin/users/{id}/suspend", target.userId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + regularUser.accessToken()))
                .andExpect(status().isForbidden());
        assertEquals(DatabaseRole.APP, DatabaseRoleContext.current());

        // JWT obsoleto de ADMIN rebaixado recebe 403
        mockMvc.perform(post("/api/v1/admin/users/{id}/suspend", target.userId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + staleAdminJwt))
                .andExpect(status().isForbidden());
        assertEquals(DatabaseRole.APP, DatabaseRoleContext.current());

        String targetStatus = PrivilegedTestDatabase.jdbc().queryForObject(
                "SELECT account_status FROM users WHERE id = ?", String.class, target.userId());
        assertEquals("ACTIVE", targetStatus);
        Integer auditCount = PrivilegedTestDatabase.jdbc().queryForObject(
                "SELECT COUNT(*) FROM account_moderation_audit_logs WHERE target_user_id = ?",
                Integer.class, target.userId());
        assertEquals(0, auditCount);
    }

    @Test
    @DisplayName("Escalação cruzada via SET ROLE é bloqueada entre rewit_app, rewit_media e rewit_mod (todas as 6 direções)")
    void crossRoleSetRoleIsDeniedByPostgres() {
        assertInsufficientPrivilege(assertThrows(DataAccessException.class,
                () -> PrivilegedTestDatabase.appJdbc().execute("SET ROLE rewit_media")));
        assertInsufficientPrivilege(assertThrows(DataAccessException.class,
                () -> PrivilegedTestDatabase.appJdbc().execute("SET ROLE rewit_mod")));

        assertInsufficientPrivilege(assertThrows(DataAccessException.class,
                () -> PrivilegedTestDatabase.mediaJdbc().execute("SET ROLE rewit_app")));
        assertInsufficientPrivilege(assertThrows(DataAccessException.class,
                () -> PrivilegedTestDatabase.mediaJdbc().execute("SET ROLE rewit_mod")));

        assertInsufficientPrivilege(assertThrows(DataAccessException.class,
                () -> PrivilegedTestDatabase.modJdbc().execute("SET ROLE rewit_app")));
        assertInsufficientPrivilege(assertThrows(DataAccessException.class,
                () -> PrivilegedTestDatabase.modJdbc().execute("SET ROLE rewit_media")));
    }

    private static void assertInsufficientPrivilege(Throwable throwable) {
        Throwable cur = throwable;
        while (cur != null) {
            if (cur instanceof SQLException sqlEx && "42501".equals(sqlEx.getSQLState())) {
                return;
            }
            cur = cur.getCause();
        }
        throw new AssertionError("Esperado SQLState 42501 (insufficient_privilege), obtido: " + throwable, throwable);
    }

    private record TestUser(UUID userId, String email, String password, String accessToken) {}

    private TestUser registerAndLogin(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String email = prefix + "." + suffix + "@rewit.test";
        String password = "StrongPassword!123";
        String handle = prefix.substring(0, Math.min(prefix.length(), 10)) + "_" + suffix;
        RegisterRequest req = new RegisterRequest(email, password, handle, "User " + suffix);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID userId = UUID.fromString(json.get("user").get("id").asText());
        createdUserIds.add(userId);
        return new TestUser(userId, email, password, json.get("accessToken").asText());
    }

    private String login(String email, String password) throws Exception {
        LoginRequest req = new LoginRequest(email, password);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
