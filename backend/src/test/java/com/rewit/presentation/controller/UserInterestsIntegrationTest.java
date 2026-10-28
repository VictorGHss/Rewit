package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.rewit.application.port.UserInterestProfileRepository;
import com.rewit.domain.enums.InterestCategory;
import com.rewit.domain.enums.InterestGroup;
import com.rewit.domain.model.UserInterestProfile;
import com.rewit.presentation.dto.auth.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Questionário inicial e interesses do usuário contra o PostgreSQL real: contrato HTTP, situação do questionário,
 * rascunho e retomada, conclusão e edição, isolamento entre contas, sessão inválida, conta inoperante, trava por
 * usuário e paridade enum ↔ CHECK da V40.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Integração: questionário inicial e interesses do usuário")
class UserInterestsIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private UserInterestProfileRepository userInterestProfileRepository;

    @Value("${rewit.security.jwt-secret}")
    private String jwtSecret;

    @Value("${rewit.security.jwt-issuer:rewit-api}")
    private String jwtIssuer;

    @Value("${rewit.security.jwt-audience:rewit-clients}")
    private String jwtAudience;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record TestUser(UUID id, String token) {}

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Catálogo
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Catálogo lista as perguntas na ordem do questionário, cada uma com suas opções, e o mínimo para concluir")
    void catalogListsQuestionsAndOptionsInOrder() throws Exception {
        TestUser user = registerUser("catalog");

        JsonNode body = read(mockMvc.perform(withToken(get("/api/v1/interests/catalog"), user))
                .andExpect(status().isOk())
                .andReturn());

        assertEquals(UserInterestProfile.MIN_INTERESTS_TO_COMPLETE, body.get("minInterestsToComplete").asInt());
        JsonNode questions = body.get("questions");
        InterestGroup[] groups = InterestGroup.values();
        assertEquals(groups.length, questions.size());
        for (int i = 0; i < groups.length; i++) {
            JsonNode question = questions.get(i);
            assertEquals(groups[i].getCode(), question.get("code").asText());
            assertEquals(groups[i].getTitle(), question.get("title").asText());
            assertEquals(groups[i].getQuestion(), question.get("question").asText());
            List<InterestCategory> expected = InterestCategory.ofGroup(groups[i]);
            assertEquals(expected.size(), question.get("options").size());
            for (int j = 0; j < expected.size(); j++) {
                assertEquals(expected.get(j).getCode(), question.get("options").get(j).get("code").asText());
                assertEquals(expected.get(j).getLabel(), question.get("options").get(j).get("label").asText());
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Autenticação e conta
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Sem token, com token inválido ou expirado: catálogo, GET e PUT respondem 401 AUTHENTICATION_REQUIRED")
    void missingInvalidOrExpiredTokenIsRejected() throws Exception {
        TestUser user = registerUser("expired");
        String expired = signedAccessToken(user.id(), Instant.now().minusSeconds(3600));
        String body = interestsBody(false, "CAFE");

        for (String header : new String[]{null, "Bearer token-invalido", "Bearer " + expired}) {
            for (MockHttpServletRequestBuilder request : List.of(
                    get("/api/v1/interests/catalog"),
                    get("/api/v1/me/interests"),
                    put("/api/v1/me/interests").contentType(MediaType.APPLICATION_JSON).content(body))) {
                if (header != null) {
                    request.header(HttpHeaders.AUTHORIZATION, header);
                }
                mockMvc.perform(request)
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
            }
        }
        assertEquals(0, profileRows(user.id()), "nenhuma requisição recusada grava perfil");
        assertEquals(0, interestRows(user.id()));
    }

    @Test
    @DisplayName("Conta desativada ou suspensa: GET e PUT respondem 401 ACCOUNT_DISABLED sem alterar o perfil")
    void nonOperationalAccountIsRejected() throws Exception {
        for (String accountStatus : new String[]{"DEACTIVATED", "SUSPENDED"}) {
            TestUser user = registerUser("disabled");
            putInterests(user, false, "CAFE").andExpect(status().isOk());
            Map<String, Object> before = profileRow(user.id());

            jdbcTemplate.update("UPDATE users SET account_status = ?, is_active = false WHERE id = ?",
                    accountStatus, user.id());

            mockMvc.perform(withToken(get("/api/v1/me/interests"), user))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
            putInterests(user, true, "CAFE", "BAR", "PARK")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
            assertEquals(before, profileRow(user.id()));
            assertEquals(List.of("CAFE"), storedCodes(user.id()));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Situação do questionário: não iniciado, rascunho, retomada e conclusão
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Conta nova começa NOT_STARTED, sem linha de perfil e sem interesses")
    void newAccountHasNotStarted() throws Exception {
        TestUser user = registerUser("fresh");

        mockMvc.perform(withToken(get("/api/v1/me/interests"), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.onboardingCompletedAt").doesNotExist())
                .andExpect(jsonPath("$.interests").isEmpty());
        assertEquals(0, profileRows(user.id()));
    }

    @Test
    @DisplayName("Rascunho grava respostas parciais (IN_PROGRESS) e a consulta seguinte as devolve para a retomada")
    void draftIsPersistedAndRestored() throws Exception {
        TestUser user = registerUser("draft");

        putInterests(user, false, "BAR", "CAFE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.onboardingCompletedAt").doesNotExist());

        // Retomada: nova consulta (como depois de reabrir o app) devolve as respostas na ordem do catálogo
        JsonNode body = read(mockMvc.perform(withToken(get("/api/v1/me/interests"), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingStatus").value("IN_PROGRESS"))
                .andReturn());
        assertEquals(List.of("CAFE", "BAR"), codes(body));
        assertEquals("Cafés e cafeterias", body.get("interests").get(0).get("label").asText());

        // Persistência: rótulo, código e peso 1.00 em user_interests (V1)
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT interest_name, weight FROM user_interests "
                + "WHERE user_id = ? AND category_code = 'CAFE'", user.id());
        assertEquals("Cafés e cafeterias", row.get("interest_name"));
        assertEquals(0, new java.math.BigDecimal("1.00").compareTo((java.math.BigDecimal) row.get("weight")));

        // Rascunho vazio também é rascunho: o usuário avançou sem escolher
        putInterests(user, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.interests").isEmpty());
        assertEquals(0, interestRows(user.id()));
        assertEquals(1, profileRows(user.id()));
    }

    @Test
    @DisplayName("Concluir abaixo do mínimo responde 422 e preserva o rascunho; sem rascunho, nada é criado")
    void completionBelowMinimumIsRejectedWithoutWriting() throws Exception {
        TestUser fresh = registerUser("belowmin");
        putInterests(fresh, true, "CAFE", "BAR")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INTERESTS_BELOW_MINIMUM"));
        assertEquals(0, profileRows(fresh.id()), "a criação do perfil é desfeita com a transação");

        TestUser drafting = registerUser("belowmindraft");
        putInterests(drafting, false, "PARK").andExpect(status().isOk());
        Map<String, Object> before = profileRow(drafting.id());
        putInterests(drafting, true, "CAFE", "BAR")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INTERESTS_BELOW_MINIMUM"));
        assertEquals(before, profileRow(drafting.id()));
        assertEquals(List.of("PARK"), storedCodes(drafting.id()));
    }

    @Test
    @DisplayName("Conclusão grava COMPLETED e a data; reenvio idêntico é idempotente e preserva a data da conclusão")
    void completionIsPersistedAndResendIsIdempotent() throws Exception {
        TestUser user = registerUser("complete");
        putInterests(user, false, "CAFE").andExpect(status().isOk());

        JsonNode completed = read(putInterests(user, true, "CAFE", "BAR", "PARK")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingStatus").value("COMPLETED"))
                .andReturn());
        String completedAt = completed.get("onboardingCompletedAt").asText();
        assertNotNull(jdbcTemplate.queryForObject("SELECT onboarding_completed_at FROM user_interest_profiles "
                + "WHERE user_id = ?", java.sql.Timestamp.class, user.id()));

        JsonNode resent = read(putInterests(user, true, "CAFE", "BAR", "PARK")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingStatus").value("COMPLETED"))
                .andReturn());
        assertEquals(completedAt, resent.get("onboardingCompletedAt").asText());
        assertEquals(List.of("CAFE", "BAR", "PARK"), codes(resent));
        assertEquals(3, interestRows(user.id()));

        // Concluído não volta ao questionário: a consulta seguinte (novo login) continua COMPLETED
        mockMvc.perform(withToken(get("/api/v1/me/interests"), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.onboardingCompletedAt").value(completedAt));
    }

    @Test
    @DisplayName("Edição depois da conclusão substitui a seleção, preserva interesses mantidos e a data; "
            + "completeOnboarding=false não desfaz; abaixo do mínimo responde 422")
    void editingAfterCompletion() throws Exception {
        TestUser user = registerUser("edit");
        String completedAt = read(putInterests(user, true, "CAFE", "BAR", "PARK").andReturn())
                .get("onboardingCompletedAt").asText();
        Object cafeCreatedAt = jdbcTemplate.queryForObject("SELECT created_at FROM user_interests "
                + "WHERE user_id = ? AND category_code = 'CAFE'", Object.class, user.id());

        JsonNode edited = read(putInterests(user, false, "CAFE", "RESTAURANT", "GAMES", "TRAVEL")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingStatus").value("COMPLETED"))
                .andReturn());
        assertEquals(completedAt, edited.get("onboardingCompletedAt").asText());
        assertEquals(List.of("RESTAURANT", "CAFE", "GAMES", "TRAVEL"), codes(edited));
        assertEquals(List.of("CAFE", "GAMES", "RESTAURANT", "TRAVEL"), storedCodes(user.id()));
        assertEquals(cafeCreatedAt, jdbcTemplate.queryForObject("SELECT created_at FROM user_interests "
                + "WHERE user_id = ? AND category_code = 'CAFE'", Object.class, user.id()),
                "interesse mantido não é regravado");

        putInterests(user, true, "CAFE", "BAR")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INTERESTS_BELOW_MINIMUM"));
        assertEquals(List.of("CAFE", "GAMES", "RESTAURANT", "TRAVEL"), storedCodes(user.id()));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Validação de entrada
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Payloads inválidos respondem 400 RFC 7807 e não alteram o rascunho gravado")
    void invalidPayloadsAreRejectedWithoutWriting() throws Exception {
        TestUser user = registerUser("invalid");
        putInterests(user, false, "CAFE").andExpect(status().isOk());
        Map<String, Object> before = profileRow(user.id());

        putRaw(user, interestsBody(false, "CAFE", "SAUNA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INTEREST_CODE"));
        putRaw(user, interestsBody(false, "cafe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INTEREST_CODE"));
        putRaw(user, interestsBody(true, "CAFE", "BAR", "PARK", "CAFE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_INTEREST"));
        putRaw(user, "{\"interests\": [\"CAFE\", null], \"completeOnboarding\": false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        putRaw(user, "{\"completeOnboarding\": false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        putRaw(user, "{\"interests\": [\"CAFE\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        putRaw(user, "{\"interests\": \"CAFE\", \"completeOnboarding\": false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        List<String> oversized = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            oversized.add("CAFE");
        }
        putRaw(user, objectMapper.writeValueAsString(Map.of("interests", oversized, "completeOnboarding", false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertEquals(before, profileRow(user.id()));
        assertEquals(List.of("CAFE"), storedCodes(user.id()));
    }

    @Test
    @DisplayName("Código inválido na primeira gravação é recusado antes de qualquer escrita: nenhuma linha criada")
    void invalidCodeOnFirstWriteCreatesNothing() throws Exception {
        TestUser user = registerUser("firstinvalid");
        putRaw(user, interestsBody(false, "SAUNA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INTEREST_CODE"));
        assertEquals(0, profileRows(user.id()));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Isolamento entre contas
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Cada conta vê e grava só os próprios interesses; userId no corpo é ignorado (sem IDOR)")
    void interestsAreIsolatedPerUser() throws Exception {
        TestUser alice = registerUser("alice");
        TestUser bruno = registerUser("bruno");
        putInterests(bruno, true, "GAMES", "TRAVEL", "FITNESS").andExpect(status().isOk());
        Map<String, Object> brunoBefore = profileRow(bruno.id());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", bruno.id().toString());
        body.put("id", bruno.id().toString());
        body.put("interests", List.of("CAFE", "BAR", "PARK"));
        body.put("completeOnboarding", true);
        putRaw(alice, objectMapper.writeValueAsString(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingStatus").value("COMPLETED"));

        assertEquals(List.of("BAR", "CAFE", "PARK"), storedCodes(alice.id()));
        assertEquals(List.of("FITNESS", "GAMES", "TRAVEL"), storedCodes(bruno.id()));
        assertEquals(brunoBefore, profileRow(bruno.id()));

        assertEquals(List.of("CAFE", "BAR", "PARK"),
                codes(read(mockMvc.perform(withToken(get("/api/v1/me/interests"), alice)).andReturn())));
        assertEquals(List.of("GAMES", "TRAVEL", "FITNESS").stream().sorted().toList(),
                codes(read(mockMvc.perform(withToken(get("/api/v1/me/interests"), bruno)).andReturn()))
                        .stream().sorted().toList());
    }

    @Test
    @DisplayName("Porta do Feed V2: findByUserId devolve os interesses gravados pelo próprio usuário; vazio se nunca gravou")
    void feedPortReadsInterestsOfTheUser() throws Exception {
        TestUser user = registerUser("feedport");
        TestUser other = registerUser("feedother");
        assertTrue(userInterestProfileRepository.findByUserId(user.id()).isEmpty());

        putInterests(user, true, "CAFE", "BAR", "PARK").andExpect(status().isOk());
        putInterests(other, true, "GAMES", "TRAVEL", "FITNESS").andExpect(status().isOk());

        UserInterestProfile profile = userInterestProfileRepository.findByUserId(user.id()).orElseThrow();
        assertEquals(Set.of(InterestCategory.CAFE, InterestCategory.BAR, InterestCategory.PARK), profile.getInterests());
        assertTrue(profile.isOnboardingCompleted());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Concorrência
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Gravações concorrentes do mesmo usuário esperam a trava do perfil e terminam com uma seleção inteira")
    void concurrentWritesSerializeOnProfileLock() throws Exception {
        TestUser user = registerUser("concurrent");
        putInterests(user, false, "HEALTH").andExpect(status().isOk());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            // FOR SHARE conflita com o FOR NO KEY UPDATE do caso de uso
            Future<?> holder = executor.submit(() -> tx.executeWithoutResult(s -> {
                holderPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                jdbcTemplate.queryForObject("SELECT user_id FROM user_interest_profiles WHERE user_id = ? FOR SHARE",
                        UUID.class, user.id());
                lockAcquired.countDown();
                awaitRelease(releaseLock);
            }));
            assertTrue(lockAcquired.await(5, TimeUnit.SECONDS), "a transação de teste deve travar o perfil");

            Future<Integer> first = executor.submit(() -> putStatus(user, true, "CAFE", "BAR", "PARK"));
            Future<Integer> second = executor.submit(() -> putStatus(user, true, "GAMES", "TRAVEL", "FITNESS", "PET"));
            waitForProfileLockWaiters(holderPid.get(), 2);
            assertEquals(List.of("HEALTH"), storedCodes(user.id()), "enquanto travadas, nada mudou");

            releaseLock.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertEquals(200, first.get(10, TimeUnit.SECONDS));
            assertEquals(200, second.get(10, TimeUnit.SECONDS));
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }

        Set<String> finalCodes = new HashSet<>(storedCodes(user.id()));
        assertTrue(finalCodes.equals(Set.of("CAFE", "BAR", "PARK"))
                        || finalCodes.equals(Set.of("GAMES", "TRAVEL", "FITNESS", "PET")),
                "a seleção final deve ser exatamente uma das gravações: " + finalCodes);
        assertEquals(1, profileRows(user.id()));
    }

    @Test
    @DisplayName("Primeiras gravações concorrentes não falham por chave duplicada: esperam a linha em criação e "
            + "serializam, mesmo quando a transação que a criava é desfeita")
    void concurrentFirstWritesDoNotConflict() throws Exception {
        TestUser user = registerUser("firstrace");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch rowInserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            // Linha inserida e não confirmada: as duas gravações esperam o desfecho dela no ON CONFLICT
            Future<?> holder = executor.submit(() -> tx.executeWithoutResult(s -> {
                holderPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                jdbcTemplate.update("INSERT INTO user_interest_profiles (user_id) VALUES (?)", user.id());
                rowInserted.countDown();
                awaitRelease(release);
                s.setRollbackOnly();
            }));
            assertTrue(rowInserted.await(5, TimeUnit.SECONDS));

            Future<Integer> first = executor.submit(() -> putStatus(user, false, "CAFE"));
            Future<Integer> second = executor.submit(() -> putStatus(user, false, "BAR", "PARK"));
            waitForProfileLockWaiters(holderPid.get(), 2);

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertEquals(200, first.get(10, TimeUnit.SECONDS));
            assertEquals(200, second.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        Set<String> finalCodes = new HashSet<>(storedCodes(user.id()));
        assertTrue(finalCodes.equals(Set.of("CAFE")) || finalCodes.equals(Set.of("BAR", "PARK")),
                "a seleção final deve ser exatamente uma das gravações: " + finalCodes);
        assertEquals(1, profileRows(user.id()));
        assertNull(jdbcTemplate.queryForObject("SELECT onboarding_completed_at FROM user_interest_profiles "
                + "WHERE user_id = ?", java.sql.Timestamp.class, user.id()));
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timeout aguardando liberação do lock de teste");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Espera {@code expected} sessões paradas em lock de user_interest_profiles, bloqueadas pela de teste ou entre si. */
    private void waitForProfileLockWaiters(int holderPid, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String sql = """
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database()
                  AND pid NOT IN (pg_backend_pid(), ?)
                  AND wait_event_type = 'Lock'
                  AND query ILIKE '%user_interest_profiles%'
                """;
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(sql, Integer.class, holderPid);
            if (waiting != null && waiting >= expected) {
                return;
            }
            Thread.sleep(25);
        }
        fail("as gravações deveriam estar esperando a trava de user_interest_profiles");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Banco: paridade do catálogo e restrições da V40
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CHECK chk_user_interest_category_code aceita exatamente os códigos do enum InterestCategory")
    void databaseCheckMatchesEnum() throws Exception {
        String definition = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_user_interest_category_code'",
                String.class);
        Matcher matcher = Pattern.compile("'([A-Z_]+)'").matcher(definition);
        Set<String> checkCodes = new HashSet<>();
        while (matcher.find()) {
            checkCodes.add(matcher.group(1));
        }
        Set<String> enumCodes = Arrays.stream(InterestCategory.values()).map(InterestCategory::getCode)
                .collect(Collectors.toSet());
        assertEquals(enumCodes, checkCodes);

        // E o caminho real grava todos eles
        TestUser user = registerUser("parity");
        putInterests(user, true, enumCodes.toArray(String[]::new))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interests.length()").value(enumCodes.size()));
        assertEquals(enumCodes, new HashSet<>(storedCodes(user.id())));
    }

    @Test
    @DisplayName("Banco recusa interesse fora do catálogo e interesse repetido para o mesmo usuário")
    void databaseConstraintsGuardInterests() throws Exception {
        TestUser user = registerUser("constraints");
        putInterests(user, false, "CAFE").andExpect(status().isOk());

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO user_interests (user_id, interest_name, category_code) VALUES (?, 'Sauna', 'SAUNA')",
                user.id()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO user_interests (user_id, interest_name, category_code) VALUES (?, 'Cafés', 'CAFE')",
                user.id()));
        assertEquals(List.of("CAFE"), storedCodes(user.id()));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private ResultActions putInterests(TestUser user, boolean complete, String... codes) throws Exception {
        return putRaw(user, interestsBody(complete, codes));
    }

    private String interestsBody(boolean complete, String... codes) throws Exception {
        return objectMapper.writeValueAsString(Map.of("interests", List.of(codes), "completeOnboarding", complete));
    }

    private ResultActions putRaw(TestUser user, String body) throws Exception {
        return mockMvc.perform(withToken(put("/api/v1/me/interests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body), user));
    }

    private int putStatus(TestUser user, boolean complete, String... codes) {
        try {
            return putInterests(user, complete, codes).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            return 500;
        }
    }

    private Map<String, Object> profileRow(UUID userId) {
        return jdbcTemplate.queryForMap("SELECT onboarding_completed_at, created_at, updated_at "
                + "FROM user_interest_profiles WHERE user_id = ?", userId);
    }

    private int profileRows(UUID userId) {
        Integer value = jdbcTemplate.queryForObject("SELECT count(*) FROM user_interest_profiles WHERE user_id = ?",
                Integer.class, userId);
        return value == null ? 0 : value;
    }

    private int interestRows(UUID userId) {
        Integer value = jdbcTemplate.queryForObject("SELECT count(*) FROM user_interests WHERE user_id = ?",
                Integer.class, userId);
        return value == null ? 0 : value;
    }

    private List<String> storedCodes(UUID userId) {
        return jdbcTemplate.queryForList(
                "SELECT category_code FROM user_interests WHERE user_id = ? ORDER BY category_code",
                String.class, userId);
    }

    private static List<String> codes(JsonNode body) {
        List<String> codes = new ArrayList<>();
        body.get("interests").forEach(node -> codes.add(node.get("code").asText()));
        return codes;
    }

    private String signedAccessToken(UUID userId, Instant expiresAt) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(jwtIssuer)
                .subject(userId.toString())
                .audience(jwtAudience)
                .issueTime(Date.from(expiresAt.minusSeconds(900)))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(jwtSecret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, TestUser user) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private TestUser registerUser(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(
                                prefix + "." + suffix + "@rewit.test", "Senha@" + suffix,
                                prefix + "_" + suffix, "Nome " + suffix))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = read(result);
        return new TestUser(UUID.fromString(node.get("user").get("id").asText()), node.get("accessToken").asText());
    }
}
