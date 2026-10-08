package com.rewit.application.usecase;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.AccountPurgeRepository;
import com.rewit.application.port.EmailReservation;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.usecase.PurgeEligibleAccountsUseCase.AccountPurgeRunResult;
import com.rewit.infrastructure.account.HmacEmailReservation;
import com.rewit.presentation.dto.auth.LoginRequest;
import com.rewit.presentation.dto.auth.RegisterRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Passada do job de purge (C2.3) com PostgreSQL real: a orquestração é montada diretamente (o agendamento está
 * desligado nos testes), com o caso de uso de purge real, transacional por conta.
 *
 * <p>Isolamento: a consulta de candidatas é global (todas as {@code DELETED} elegíveis), então o banco compartilhado
 * precisa estar sob controle:
 * <ul>
 *   <li>todos os testes usam o mesmo "agora" fixo ({@link #REFERENCE_NOW}), no passado distante; as contas do cenário
 *   recebem {@code deleted_at} relativo a ele, e o corte de 30 dias nunca alcança contas de outras suítes, excluídas
 *   com o relógio real;</li>
 *   <li>as contas desta classe têm e-mail marcado ({@link #FIXTURE_EMAIL_PREFIX}); antes de cada teste, sobras
 *   {@code DELETED} ainda não purgadas (por exemplo de uma execução interrompida antes do teardown) são removidas;
 *   depois de cada teste, as contas criadas por ele são removidas.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Job de purge de contas excluídas (C2.3): passada com PostgreSQL real")
class AccountPurgeJobIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;
    @Autowired private AccountPurgeRepository accountPurgeRepository;
    @Autowired private EmailReservation emailReservation;
    @Autowired private PurgeDeletedAccountUseCase purgeDeletedAccountUseCase;
    @Autowired private Environment environment;

    /** "Agora" fixo de todos os testes: nenhuma conta excluída pelo relógio real tem deleted_at tão antigo. */
    private static final Instant REFERENCE_NOW = Instant.parse("2000-01-15T12:00:00Z");
    /** Marca as contas criadas por esta classe, para a limpeza nunca alcançar dados de outras suítes. */
    private static final String FIXTURE_EMAIL_PREFIX = "purgejob.";
    private static final String FIXTURE_EMAIL_PATTERN = FIXTURE_EMAIL_PREFIX + "%@rewit.test";

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<UUID> createdUsers = new ArrayList<>();

    private record TestUser(UUID id, String email, String password, String accessToken) {}

    /**
     * Caso de uso real atrás de um registro das chamadas, com falha simulada opcional. Delega ao bean do Spring, então
     * cada conta continua na sua própria transação.
     */
    private static final class RecordingPurge extends PurgeDeletedAccountUseCase {

        final List<UUID> calls = new CopyOnWriteArrayList<>();
        private final PurgeDeletedAccountUseCase delegate;
        private final Set<UUID> failFor;

        RecordingPurge(PurgeDeletedAccountUseCase delegate, Set<UUID> failFor, UserRepository users,
                       ProfileRepository profiles, AccountPurgeRepository purges, EmailReservation reservation) {
            super(users, profiles, purges, reservation);
            this.delegate = delegate;
            this.failFor = failFor;
        }

        @Override
        public PurgeResult execute(UUID userId, Instant now) {
            calls.add(userId);
            if (failFor.contains(userId)) {
                throw new DataAccessResourceFailureException("falha simulada de acesso a dados");
            }
            return delegate.execute(userId, now);
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        jdbcTemplate.update("DELETE FROM users WHERE account_status = 'DELETED' AND email LIKE ?", FIXTURE_EMAIL_PATTERN);
    }

    @AfterEach
    void tearDown() {
        for (UUID id : createdUsers) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test
    @DisplayName("Passada: só DELETED fora do prazo vai ao purge; 29 dias ignorada, 30 e 31 purgadas; outros estados nunca")
    void runPurgesOnlyEligibleDeletedAccounts() throws Exception {
        Instant now = REFERENCE_NOW;
        TestUser days29 = deletedAt(register("job29"), now.minus(Duration.ofDays(29)));
        TestUser days30 = deletedAt(register("job30"), now.minus(Duration.ofDays(30)));
        TestUser days31 = deletedAt(register("job31"), now.minus(Duration.ofDays(31)));
        TestUser active = register("job_ativa");
        TestUser deactivated = register("job_desativada");
        TestUser suspended = register("job_suspensa");
        deactivate(deactivated);
        suspend(suspended);

        RecordingPurge purge = recording(Set.of());
        AccountPurgeRunResult result = job(purge).run(now);

        assertTrue(purge.calls.containsAll(List.of(days30.id(), days31.id())), purge.calls.toString());
        for (TestUser untouched : List.of(days29, active, deactivated, suspended)) {
            assertFalse(purge.calls.contains(untouched.id()), "não enviada ao purge: " + untouched.email());
            assertEquals(untouched.email(), email(untouched), "não alterada");
        }
        assertTrue(isMinimized(days30), "o limite exato (deleted_at = agora - 30 dias) já é elegível");
        assertTrue(isMinimized(days31));
        assertFalse(result.secretMissing());
        assertEquals(List.of("ACTIVE", "DEACTIVATED", "SUSPENDED"), List.of(accountStatus(active), accountStatus(deactivated), accountStatus(suspended)));
    }

    @Test
    @DisplayName("Isolamento: A sucesso, B falha, C sucesso; a falha de B não desfaz A nem impede C")
    void failureOfOneAccountDoesNotAffectOthers() throws Exception {
        Instant now = REFERENCE_NOW;
        TestUser a = deletedAt(register("job_a"), now.minus(Duration.ofDays(40)));
        TestUser b = deletedAt(register("job_b"), now.minus(Duration.ofDays(39)));
        TestUser c = deletedAt(register("job_c"), now.minus(Duration.ofDays(38)));

        RecordingPurge purge = recording(Set.of(b.id()));
        AccountPurgeRunResult result = job(purge).run(now);

        assertEquals(List.of(a.id(), b.id(), c.id()),
                purge.calls.stream().filter(id -> Set.of(a.id(), b.id(), c.id()).contains(id)).toList(),
                "as três foram tentadas, das mais antigas para as mais novas");
        assertTrue(isMinimized(a), "A foi confirmada na própria transação");
        assertFalse(isMinimized(b), "B ficou intacta");
        assertEquals(b.email(), email(b));
        assertEquals(1, count("SELECT count(*) FROM auth_sessions WHERE user_id = ?", b.id()), "nada de B foi removido");
        assertTrue(isMinimized(c), "C foi purgada depois da falha de B");
        assertEquals(1, result.failed());
        assertEquals(1, result.failuresByType().get("DataAccessResourceFailureException"));
    }

    @Test
    @DisplayName("Idempotência: a segunda passada não altera as contas já minimizadas")
    void secondRunDoesNotChangePurgedAccounts() throws Exception {
        Instant now = REFERENCE_NOW;
        TestUser account = deletedAt(register("job_idem"), now.minus(Duration.ofDays(31)));

        job(recording(Set.of())).run(now);
        assertTrue(isMinimized(account));
        Map<String, Object> afterFirst = jdbcTemplate.queryForMap(
                "SELECT u.email, u.updated_at, p.handle, p.updated_at AS profile_updated_at "
                        + "FROM users u JOIN profiles p ON p.user_id = u.id WHERE u.id = ?", account.id());

        RecordingPurge second = recording(Set.of());
        job(second).run(now);

        assertFalse(second.calls.contains(account.id()), "já minimizada: não volta ao lote");
        assertEquals(afterFirst, jdbcTemplate.queryForMap(
                "SELECT u.email, u.updated_at, p.handle, p.updated_at AS profile_updated_at "
                        + "FROM users u JOIN profiles p ON p.user_id = u.id WHERE u.id = ?", account.id()));
        // Mesmo chamada diretamente, o caso de uso continua no-op
        assertFalse(purgeDeletedAccountUseCase.execute(account.id(), now).changedAnything());
    }

    @Test
    @DisplayName("Sem segredo: com conta elegível a passada é bloqueada, nada muda e o log não traz e-mail nem segredo")
    void missingSecretBlocksRunWithoutChanges() throws Exception {
        Instant now = REFERENCE_NOW;
        TestUser eligible = deletedAt(register("job_sem_segredo"), now.minus(Duration.ofDays(31)));
        HmacEmailReservation withoutSecret = new HmacEmailReservation("");
        PurgeDeletedAccountUseCase purgeWithoutSecret =
                new PurgeDeletedAccountUseCase(userRepository, profileRepository, accountPurgeRepository, withoutSecret);
        RecordingPurge purge = new RecordingPurge(purgeWithoutSecret, Set.of(), userRepository, profileRepository,
                accountPurgeRepository, withoutSecret);

        ListAppender<ILoggingEvent> appender = captureLogs();
        AccountPurgeRunResult result;
        try {
            result = new PurgeEligibleAccountsUseCase(userRepository, purge, withoutSecret, 100, 10).run(now);
        } finally {
            detach(appender);
        }

        assertTrue(result.secretMissing());
        assertEquals(1, result.candidates(), "só a conta deste teste é candidata");
        assertEquals(0, result.purged());
        assertTrue(purge.calls.isEmpty(), "nenhuma conta foi enviada ao purge");
        assertEquals(eligible.email(), email(eligible));
        assertEquals(1, count("SELECT count(*) FROM auth_sessions WHERE user_id = ?", eligible.id()));

        String secret = environment.getProperty("rewit.account.email-reservation-secret");
        assertNotNull(secret);
        assertTrue(appender.list.stream().anyMatch(event ->
                event.getFormattedMessage().contains("ACCOUNT_EMAIL_RESERVATION_SECRET")), "falha operacional detectável");
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            assertFalse(message.contains(eligible.email()), message);
            assertFalse(message.contains(secret), "o segredo não aparece no log");
            assertFalse(message.contains(eligible.id().toString()), "sem identificador de conta no log (ADR-012)");
        }
    }

    @Test
    @DisplayName("Sem segredo e sem conta elegível: a passada não falha, porque nada é candidato")
    void missingSecretWithoutEligibleAccountsIsNotAFailure() throws Exception {
        Instant now = REFERENCE_NOW;
        TestUser withinGrace = deletedAt(register("job_prazo"), now.minus(Duration.ofDays(29)));
        assertEquals(List.of(), userRepository.findDeletedUserIdsPendingPurge(now.minus(Duration.ofDays(30)), 1),
                "pré-condição: nenhuma conta elegível no corte deste teste");
        HmacEmailReservation withoutSecret = new HmacEmailReservation("");
        RecordingPurge purge = new RecordingPurge(purgeDeletedAccountUseCase, Set.of(), userRepository, profileRepository,
                accountPurgeRepository, withoutSecret);

        AccountPurgeRunResult result = new PurgeEligibleAccountsUseCase(userRepository, purge, withoutSecret, 100, 10)
                .run(now);

        assertFalse(result.secretMissing());
        assertEquals(0, result.candidates());
        assertEquals(0, result.failed());
        assertTrue(purge.calls.isEmpty());
        assertEquals(withinGrace.email(), email(withinGrace));
    }

    // ---------------------------------------------------------------------------------------------------------

    private PurgeEligibleAccountsUseCase job(PurgeDeletedAccountUseCase purge) {
        return new PurgeEligibleAccountsUseCase(userRepository, purge, emailReservation, 100, 10);
    }

    private RecordingPurge recording(Set<UUID> failFor) {
        return new RecordingPurge(purgeDeletedAccountUseCase, failFor, userRepository, profileRepository,
                accountPurgeRepository, emailReservation);
    }

    /** Exclui a conta pelo fluxo administrativo real e posiciona deleted_at no passado de referência do teste. */
    private TestUser deletedAt(TestUser user, Instant deletedAt) throws Exception {
        mockMvc.perform(delete("/api/v1/admin/users/{id}", user.id()).header(HttpHeaders.AUTHORIZATION, bearer(admin())))
                .andExpect(status().isNoContent());
        // A exclusão revoga as sessões; uma linha de sessão permanece até o purge
        assertEquals(1, count("SELECT count(*) FROM auth_sessions WHERE user_id = ?", user.id()));
        jdbcTemplate.update("UPDATE users SET deleted_at = ? WHERE id = ?", Timestamp.from(deletedAt), user.id());
        return user;
    }

    private void deactivate(TestUser user) throws Exception {
        mockMvc.perform(post("/api/v1/me/deactivate").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());
    }

    private void suspend(TestUser user) throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/{id}/suspend", user.id()).header(HttpHeaders.AUTHORIZATION, bearer(admin())))
                .andExpect(status().isNoContent());
    }

    private boolean isMinimized(TestUser user) {
        String email = email(user);
        return email.endsWith("@deleted.invalid") && !email.equals(user.email())
                && count("SELECT count(*) FROM auth_sessions WHERE user_id = ?", user.id()) == 0;
    }

    private String email(TestUser user) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, user.id());
    }

    private String accountStatus(TestUser user) {
        return jdbcTemplate.queryForObject("SELECT account_status FROM users WHERE id = ?", String.class, user.id());
    }

    private TestUser register(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = FIXTURE_EMAIL_PREFIX + prefix + "." + suffix + "@rewit.test";
        String password = "Senha@" + suffix;
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, password, (prefix + "_" + suffix).toLowerCase(), "Conta " + suffix))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        UUID id = UUID.fromString(node.get("user").get("id").asText());
        createdUsers.add(id);
        return new TestUser(id, email, password, node.get("accessToken").asText());
    }

    private TestUser admin() throws Exception {
        TestUser user = register("job_admin");
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.id());
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isOk())
                .andReturn();
        return new TestUser(user.id(), user.email(), user.password(),
                objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("accessToken").asText());
    }

    private static ListAppender<ILoggingEvent> captureLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(PurgeEligibleAccountsUseCase.class)).addAppender(appender);
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(PurgeEligibleAccountsUseCase.class)).detachAppender(appender);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static String bearer(TestUser user) {
        return "Bearer " + user.accessToken();
    }
}
