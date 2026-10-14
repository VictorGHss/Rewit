package com.rewit.presentation.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceRepository;
import com.rewit.domain.model.Place;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.catalog.PlaceOpeningHoursPresentationDtos.DayScheduleRequest;
import com.rewit.presentation.dto.catalog.PlaceOpeningHoursPresentationDtos.TimeIntervalRequest;
import com.rewit.presentation.dto.catalog.PlaceOpeningHoursPresentationDtos.UpdatePlaceOpeningHoursRequest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Testes de segurança e integração com PostgreSQL real para gestão de horários de funcionamento (F2.1).
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Integração e Segurança: Gestão de Horários de Funcionamento (F2.1)")
class PlaceOpeningHoursSecurityIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private record TestUser(UUID id, String email, String password, String token) {}

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    // ---------------------------------------------------------------------------------------------------------
    // 1. Leitura de horários configurados e ausência de configuração
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Leitura de estabelecimento sem horários cadastrados responde 200 OK com configured = false")
    void shouldReturnUnconfiguredWhenNoHoursConfigured() throws Exception {
        TestUser user = registerUser("reader");
        UUID placeId = createPlace();

        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/opening-hours", placeId), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.days").isEmpty());
    }

    @Test
    @DisplayName("Configuração inicial e leitura de estabelecimento com múltiplos intervalos e dias fechados")
    void shouldConfigureAndReadWeeklySchedule() throws Exception {
        TestUser businessAdmin = registerUser("biz_admin");
        UUID placeId = createPlace();
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(
                        // Segunda a Sexta: 08:00 às 18:00
                        new DayScheduleRequest(1, false, List.of(
                                new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                        )),
                        new DayScheduleRequest(2, false, List.of(
                                new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                        )),
                        new DayScheduleRequest(3, false, List.of(
                                new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                        )),
                        new DayScheduleRequest(4, false, List.of(
                                new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                        )),
                        new DayScheduleRequest(5, false, List.of(
                                new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                        )),
                        // Sábado com múltiplos intervalos: 09:00-12:00 e 14:00-19:00
                        new DayScheduleRequest(6, false, List.of(
                                new TimeIntervalRequest(LocalTime.of(9, 0), LocalTime.of(12, 0)),
                                new TimeIntervalRequest(LocalTime.of(14, 0), LocalTime.of(19, 0))
                        )),
                        // Domingo fechado
                        new DayScheduleRequest(7, true, Collections.emptyList())
                )
        );

        // 1. Grava os horários com o administrador comercial
        MvcResult putResult = mockMvc.perform(withToken(
                        put("/api/v1/places/{placeId}/opening-hours", placeId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)), businessAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.days.length()").value(7))
                .andExpect(jsonPath("$.days[0].dayOfWeek").value(1))
                .andExpect(jsonPath("$.days[0].closed").value(false))
                .andExpect(jsonPath("$.days[0].intervals[0].openTime").value("08:00"))
                .andExpect(jsonPath("$.days[0].intervals[0].closeTime").value("18:00"))
                .andExpect(jsonPath("$.days[5].dayOfWeek").value(6))
                .andExpect(jsonPath("$.days[5].intervals.length()").value(2))
                .andExpect(jsonPath("$.days[5].intervals[0].openTime").value("09:00"))
                .andExpect(jsonPath("$.days[5].intervals[0].closeTime").value("12:00"))
                .andExpect(jsonPath("$.days[5].intervals[1].openTime").value("14:00"))
                .andExpect(jsonPath("$.days[5].intervals[1].closeTime").value("19:00"))
                .andExpect(jsonPath("$.days[6].dayOfWeek").value(7))
                .andExpect(jsonPath("$.days[6].closed").value(true))
                .andExpect(jsonPath("$.days[6].intervals").isEmpty())
                .andReturn();

        // 2. Comprova persistência real nas tabelas do PostgreSQL
        Integer scheduleCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_schedules WHERE place_id = ?",
                Integer.class, placeId);
        assertEquals(1, scheduleCount);

        Integer intervalsCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_intervals WHERE place_id = ?",
                Integer.class, placeId);
        // 5 dias x 1 intervalo + 1 dia x 2 intervalos + 1 dia fechado (0 intervalos) = 7 intervalos gravados
        assertEquals(7, intervalsCount);

        // 3. Consulta pública por outro usuário retorna os dados gravados
        TestUser visitor = registerUser("visitor");
        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/opening-hours", placeId), visitor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.days.length()").value(7))
                .andExpect(jsonPath("$.days[5].intervals.length()").value(2))
                .andExpect(jsonPath("$.days[6].closed").value(true));
    }

    @Test
    @DisplayName("Atualização atômica substitui todos os intervalos anteriores sem deixar resíduos")
    void shouldUpdateScheduleAtomically() throws Exception {
        TestUser businessAdmin = registerUser("update_admin");
        UUID placeId = createPlace();
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        // Configuração inicial: 3 dias abertos (dias 1, 2, 3)
        UpdatePlaceOpeningHoursRequest initialRequest = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(
                        new DayScheduleRequest(1, false, List.of(
                                new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(12, 0))
                        )),
                        new DayScheduleRequest(2, false, List.of(
                                new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(12, 0))
                        )),
                        new DayScheduleRequest(3, false, List.of(
                                new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(12, 0))
                        ))
                )
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initialRequest)), businessAdmin))
                .andExpect(status().isOk());

        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeId));

        // Nova configuração: apenas 1 dia aberto (dia 5), com 1 intervalo
        UpdatePlaceOpeningHoursRequest updatedRequest = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(
                        new DayScheduleRequest(5, false, List.of(
                                new TimeIntervalRequest(LocalTime.of(10, 0), LocalTime.of(16, 0))
                        ))
                )
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedRequest)), businessAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].closed").value(true)) // Segunda fechou
                .andExpect(jsonPath("$.days[4].dayOfWeek").value(5))
                .andExpect(jsonPath("$.days[4].closed").value(false))
                .andExpect(jsonPath("$.days[4].intervals[0].openTime").value("10:00"));

        // Comprova no banco que os intervalos anteriores foram removidos e só o novo existe
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeId));
    }

    // ---------------------------------------------------------------------------------------------------------
    // 2. Validações de domínio e rejeição de regras de negócio
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Rejeita intervalo com openTime >= closeTime (400 INVALID_OPENING_HOURS_INTERVAL)")
    void shouldRejectInvertedTimes() throws Exception {
        TestUser businessAdmin = registerUser("inv_admin");
        UUID placeId = createPlace();
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(
                        new DayScheduleRequest(1, false, List.of(
                                new TimeIntervalRequest(LocalTime.of(18, 0), LocalTime.of(8, 0))
                        ))
                )
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)), businessAdmin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OPENING_HOURS_INTERVAL"));
    }

    @Test
    @DisplayName("Rejeita intervalos sobrepostos no mesmo dia (400 OPENING_HOURS_OVERLAPPING_INTERVALS)")
    void shouldRejectOverlappingIntervals() throws Exception {
        TestUser businessAdmin = registerUser("ovl_admin");
        UUID placeId = createPlace();
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(
                        new DayScheduleRequest(1, false, List.of(
                                new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(14, 0)),
                                new TimeIntervalRequest(LocalTime.of(12, 0), LocalTime.of(18, 0))
                        ))
                )
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)), businessAdmin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OPENING_HOURS_OVERLAPPING_INTERVALS"));
    }

    @Test
    @DisplayName("Rejeita dias duplicados na requisição (400 DUPLICATE_DAY_OF_WEEK)")
    void shouldRejectDuplicateDaysInRequest() throws Exception {
        TestUser businessAdmin = registerUser("dup_admin");
        UUID placeId = createPlace();
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(
                        new DayScheduleRequest(1, false, List.of(
                                new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(12, 0))
                        )),
                        new DayScheduleRequest(1, false, List.of(
                                new TimeIntervalRequest(LocalTime.of(14, 0), LocalTime.of(18, 0))
                        ))
                )
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)), businessAdmin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DAY_OF_WEEK"));
    }

    @Test
    @DisplayName("Rejeita dia fechado que inclui intervalos (400 INVALID_OPENING_HOURS)")
    void shouldRejectClosedDayWithIntervals() throws Exception {
        TestUser businessAdmin = registerUser("cl_admin");
        UUID placeId = createPlace();
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(
                        new DayScheduleRequest(7, true, List.of(
                                new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(12, 0))
                        ))
                )
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)), businessAdmin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OPENING_HOURS"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // 3. Autorização e elegibilidade (BusinessRepresentationPolicy e C9/C11)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Rejeita quando usuário tenta usar conta comercial pertencente a outro usuário (404 BUSINESS_ACCOUNT_NOT_FOUND)")
    void shouldRejectWhenUserDoesNotAdministerBusinessAccount() throws Exception {
        TestUser legitimateAdmin = registerUser("legit_adm");
        TestUser unauthorizedUser = registerUser("intruder");
        UUID placeId = createPlace();
        UUID accountId = createAndApproveBusinessAccount(legitimateAdmin, placeId);

        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                )))
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)), unauthorizedUser))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BUSINESS_ACCOUNT_NOT_FOUND"));
    }

    @Test
    @DisplayName("Rejeita quando a conta comercial está com status PENDING (409 BUSINESS_ACCOUNT_NOT_APPROVED)")
    void shouldRejectWhenBusinessAccountIsNotApproved() throws Exception {
        TestUser businessAdmin = registerUser("pending_adm");
        UUID placeId = createPlace();
        UUID accountId = createAccount(businessAdmin); // cria sem aprovar (fica PENDING)
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", accountId, placeId);

        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                )))
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)), businessAdmin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_ACCOUNT_NOT_APPROVED"));
    }

    @Test
    @DisplayName("Rejeita quando o local não é reivindicado pela conta comercial (403 BUSINESS_NOT_RESPONSIBLE)")
    void shouldRejectWhenPlaceIsNotClaimedByAccount() throws Exception {
        TestUser businessAdmin = registerUser("other_adm");
        UUID placeIdA = createPlace();
        UUID placeIdB = createPlace();
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeIdA);

        // Tenta alterar placeIdB que não está vinculado à sua conta
        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                )))
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeIdB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)), businessAdmin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BUSINESS_NOT_RESPONSIBLE"));
    }

    @Test
    @DisplayName("Rejeita quando o local está com status INACTIVE (404 PLACE_NOT_FOUND)")
    void shouldRejectWhenPlaceIsInactive() throws Exception {
        TestUser businessAdmin = registerUser("inact_adm");
        UUID placeId = createPlace();
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        // Desativa o local no catálogo
        jdbcTemplate.update("UPDATE places SET status = 'INACTIVE' WHERE id = ?", placeId);

        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                )))
        );

        // PUT responde 404
        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)), businessAdmin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));

        // GET também responde 404 indistinguível
        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/opening-hours", placeId), businessAdmin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Rejeita quando a conta pessoal do ator está desativada/suspensa (401 ACCOUNT_DISABLED)")
    void shouldRejectWhenActorPersonalAccountIsDisabled() throws Exception {
        TestUser businessAdmin = registerUser("disabled_adm");
        UUID placeId = createPlace();
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        // Suspende a conta pessoal do usuário
        jdbcTemplate.update("UPDATE users SET account_status = 'SUSPENDED', is_active = false WHERE id = ?", businessAdmin.id());

        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                )))
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)), businessAdmin))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    @DisplayName("Sem token de autenticação: GET e PUT respondem 401 AUTHENTICATION_REQUIRED")
    void shouldRejectUnauthenticatedRequests() throws Exception {
        UUID placeId = createPlace();

        mockMvc.perform(get("/api/v1/places/{placeId}/opening-hours", placeId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // 4. Concorrência
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Atualização concorrente bloqueia no SELECT FOR UPDATE do local e conclui com sucesso após liberação (A4)")
    void concurrentUpdateBlocksOnPlaceLockAndCompletesAfterRelease() throws Exception {
        TestUser businessAdmin = registerUser("conc_admin");
        UUID placeId = createPlace();
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(12, 0))
                )))
        );

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger holderPidRef = new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // 1. Transação de teste obtém o PID da sua conexão e adquire lock FOR SHARE sobre o estabelecimento.
            // O lock FOR SHARE conflita com o FOR UPDATE (ou FOR NO KEY UPDATE) da leitura do caso de uso,
            // mas é compatível com o FOR KEY SHARE executado pelo PostgreSQL na validação de FK durante INSERTs.
            Future<?> lockHolder = executor.submit(() -> tx.executeWithoutResult(status -> {
                Integer pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
                if (pid == null) {
                    throw new IllegalStateException("Não foi possível obter pg_backend_pid da conexão de teste");
                }
                holderPidRef.set(pid);
                jdbcTemplate.queryForObject("SELECT id FROM places WHERE id = ? FOR SHARE", UUID.class, placeId);
                lockAcquired.countDown();
                try {
                    if (!releaseLock.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timeout aguardando liberação do lock de teste");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }));

            assertTrue(lockAcquired.await(5, TimeUnit.SECONDS), "Transação inicial deve adquirir o lock FOR SHARE do local");
            int holderPid = holderPidRef.get();
            assertTrue(holderPid > 0, "PID da sessão bloqueadora deve ser positivo");

            // 2. Operação de atualização de horários é iniciada por outra transação
            Future<Integer> updateResult = executor.submit(() -> {
                try {
                    MvcResult res = mockMvc.perform(withToken(
                            put("/api/v1/places/{placeId}/opening-hours", placeId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)), businessAdmin)).andReturn();
                    return res.getResponse().getStatus();
                } catch (Exception e) {
                    return 500;
                }
            });

            // 3. Comprova positivamente que a requisição está bloqueada no SELECT com lock pessimista na tabela places,
            // tendo como bloqueador direto o PID da transação de teste (holderPid)
            waitForPlaceLockWaiter(holderPid);

            // 4. Libera a primeira transação (commit que libera o lock)
            releaseLock.countDown();
            lockHolder.get(10, TimeUnit.SECONDS);

            // 5. Atualização desbloqueia e termina com sucesso (200 OK)
            Integer httpStatus = updateResult.get(10, TimeUnit.SECONDS);
            assertEquals(200, httpStatus, "Atualização deve concluir com 200 OK após liberação do lock");

            // 6. Estado final do banco consistente
            Integer scheduleCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM place_opening_hours_schedules WHERE place_id = ?", Integer.class, placeId);
            assertEquals(1, scheduleCount);

            Integer intervalCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeId);
            assertEquals(1, intervalCount);

            Integer dayOfWeek = jdbcTemplate.queryForObject(
                    "SELECT day_of_week FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeId);
            assertEquals(1, dayOfWeek);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * Espera e comprova de forma determinística que a requisição de atualização está bloqueada
     * especificamente no SELECT com lock pessimista na tabela places, sendo bloqueada pela sessão de teste (holderPid).
     */
    private void waitForPlaceLockWaiter(int holderPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String sql = """
                SELECT count(*)
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                  AND ? = ANY(pg_blocking_pids(pid))
                  AND wait_event_type = 'Lock'
                  AND query ILIKE '%places%'
                  AND (query ILIKE '%for update%' OR query ILIKE '%for no key update%')
                  AND query NOT ILIKE 'INSERT%'
                """;
        while (System.nanoTime() < deadline) {
            Integer waiters = jdbcTemplate.queryForObject(sql, Integer.class, holderPid);
            if (waiters != null && waiters > 0) {
                return;
            }
            Thread.sleep(25);
        }
        fail("A atualização de horários deveria estar bloqueada aguardando o lock pessimista da tabela places pelo PID " + holderPid);
    }

    @Test
    @DisplayName("Rejeita quando usuário da conta A tenta configurar horários de local da conta B (403 BUSINESS_NOT_RESPONSIBLE) e preserva dados")
    void shouldRejectWhenUserOfAccountATriesToConfigurePlaceOfAccountB() throws Exception {
        // 1. Conta B reivindica placeB e configura horários válidos
        TestUser adminB = registerUser("biz_b");
        UUID placeB = createPlace();
        UUID accountB = createAndApproveBusinessAccount(adminB, placeB);

        UpdatePlaceOpeningHoursRequest initialRequestB = new UpdatePlaceOpeningHoursRequest(
                accountB,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                )))
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initialRequestB)), adminB))
                .andExpect(status().isOk());

        // Confirma 1 intervalo salvo no banco para placeB
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeB));

        // 2. Conta A (usuário A) possui sua própria conta aprovada e seu próprio local placeA
        TestUser adminA = registerUser("biz_a");
        UUID placeA = createPlace();
        UUID accountA = createAndApproveBusinessAccount(adminA, placeA);

        // 3. Usuário A tenta alterar os horários de placeB informando sua conta accountA
        UpdatePlaceOpeningHoursRequest attackRequest = new UpdatePlaceOpeningHoursRequest(
                accountA,
                List.of(new DayScheduleRequest(2, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(10, 0), LocalTime.of(16, 0))
                )))
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attackRequest)), adminA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BUSINESS_NOT_RESPONSIBLE"));

        // 4. Asserção direta no banco: os horários de placeB continuam intactos (1 intervalo de Segunda-feira)
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeB));
        Integer dayOfWeek = jdbcTemplate.queryForObject(
                "SELECT day_of_week FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeB);
        assertEquals(1, dayOfWeek);
    }

    @Test
    @DisplayName("Rejeita quando conta comercial está no estado REJECTED (409 BUSINESS_ACCOUNT_NOT_APPROVED) e não grava no banco")
    void shouldRejectWhenBusinessAccountIsRejected() throws Exception {
        TestUser businessAdmin = registerUser("rej_adm");
        UUID placeId = createPlace();
        UUID accountId = createAccount(businessAdmin);

        // Marca a conta comercial expressamente como REJECTED
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'REJECTED' WHERE id = ?", accountId);
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", accountId, placeId);

        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                )))
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)), businessAdmin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_ACCOUNT_NOT_APPROVED"));

        // Comprova ausência total de gravação no banco
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_schedules WHERE place_id = ?", Integer.class, placeId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeId));
    }

    @Test
    @DisplayName("Rejeita atualização quando local perde vínculo comercial superveniente e preserva horários anteriores")
    void shouldRejectWhenPlaceLosesClaimLinkAfterBeingConfigured() throws Exception {
        // 1. Configura horários válidos inicialmente
        TestUser businessAdmin = registerUser("former_adm");
        UUID placeId = createPlace();
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        UpdatePlaceOpeningHoursRequest initialRequest = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                )))
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initialRequest)), businessAdmin))
                .andExpect(status().isOk());

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeId));

        // 2. Local perde o vínculo comercial (ex.: desvinculação administrativa ou transferência)
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = NULL WHERE id = ?", placeId);

        // 3. Antigo responsável tenta atualizar os horários
        UpdatePlaceOpeningHoursRequest newRequest = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(new DayScheduleRequest(3, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(9, 0), LocalTime.of(17, 0))
                )))
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newRequest)), businessAdmin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BUSINESS_NOT_RESPONSIBLE"));

        // 4. Asserção: horários anteriores continuam rigorosamente preservados no banco
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeId));
        Integer preservedDay = jdbcTemplate.queryForObject(
                "SELECT day_of_week FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeId);
        assertEquals(1, preservedDay);
    }

    @Test
    @DisplayName("Audita business_account_id e updated_by_user_id na inserção inicial e na atualização subsequente")
    void shouldAuditBusinessAccountIdAndUpdatedByUserIdOnCreationAndSubsequentUpdate() throws Exception {
        TestUser businessAdmin = registerUser("audit_adm");
        UUID placeId = createPlace();
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        // 1. Inserção inicial
        UpdatePlaceOpeningHoursRequest initialRequest = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(12, 0))
                )))
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initialRequest)), businessAdmin))
                .andExpect(status().isOk());

        Map<String, Object> initialAudit = jdbcTemplate.queryForMap(
                "SELECT business_account_id, updated_by_user_id, created_at, updated_at FROM place_opening_hours_schedules WHERE place_id = ?",
                placeId);

        assertEquals(accountId, initialAudit.get("business_account_id"));
        assertEquals(businessAdmin.id(), initialAudit.get("updated_by_user_id"));
        assertNotNull(initialAudit.get("created_at"));
        assertNotNull(initialAudit.get("updated_at"));

        // 2. Atualização subsequente
        UpdatePlaceOpeningHoursRequest updatedRequest = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(9, 0), LocalTime.of(17, 0))
                )))
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedRequest)), businessAdmin))
                .andExpect(status().isOk());

        Map<String, Object> subsequentAudit = jdbcTemplate.queryForMap(
                "SELECT business_account_id, updated_by_user_id, created_at, updated_at FROM place_opening_hours_schedules WHERE place_id = ?",
                placeId);

        assertEquals(accountId, subsequentAudit.get("business_account_id"));
        assertEquals(businessAdmin.id(), subsequentAudit.get("updated_by_user_id"));
        assertEquals(initialAudit.get("created_at"), subsequentAudit.get("created_at"));
    }

    @Test
    @DisplayName("Rejeita payload com dias contendo elemento nulo (400 VALIDATION_ERROR) e não altera dados existentes (F1)")
    void shouldRejectPutWithNullDaysElementPreservingExistingSchedule() throws Exception {
        TestUser businessAdmin = registerUser("null_days_adm");
        UUID placeId = createPlace();
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        // 1. Cadastra agenda inicial válida
        UpdatePlaceOpeningHoursRequest initialRequest = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                )))
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initialRequest)), businessAdmin))
                .andExpect(status().isOk());

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeId));

        // 2. Envia payload com days: [null]
        String invalidPayload = """
                {
                    "businessAccountId": "%s",
                    "days": [null]
                }
                """.formatted(accountId);

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload)
                        .accept(MediaType.APPLICATION_JSON), businessAdmin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 3. Comprova no banco que os dados anteriores permanecem 100% intactos
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_schedules WHERE place_id = ?", Integer.class, placeId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeId));
    }

    @Test
    @DisplayName("Rejeita payload com intervalos contendo elemento nulo (400 VALIDATION_ERROR) e não altera dados existentes (F1)")
    void shouldRejectPutWithNullIntervalsElementPreservingExistingSchedule() throws Exception {
        TestUser businessAdmin = registerUser("null_int_adm");
        UUID placeId = createPlace();
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        // 1. Cadastra agenda inicial válida
        UpdatePlaceOpeningHoursRequest initialRequest = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                )))
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initialRequest)), businessAdmin))
                .andExpect(status().isOk());

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeId));

        // 2. Envia payload com intervals: [null]
        String invalidPayload = """
                {
                    "businessAccountId": "%s",
                    "days": [
                        {
                            "dayOfWeek": 1,
                            "closed": false,
                            "intervals": [null]
                        }
                    ]
                }
                """.formatted(accountId);

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload)
                        .accept(MediaType.APPLICATION_JSON), businessAdmin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 3. Comprova no banco que os dados anteriores permanecem 100% intactos
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_schedules WHERE place_id = ?", Integer.class, placeId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeId));
    }

    // ---------------------------------------------------------------------------------------------------------
    // 5. Decisão F2: Elegibilidade da Representação e Visibilidade dos Horários
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Oculta horários quando administrador é suspenso e restaura visibilidade automaticamente ao reativar (F2)")
    void shouldHideOpeningHoursWhenAdminIsSuspendedAndRestoreAutomaticallyWhenReintegrated() throws Exception {
        TestUser businessAdmin = registerUser("f2_admin");
        UUID placeId = createPlace();
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        // 1. Conta A configura horários e continua elegível: GET retorna horários de A
        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                )))
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)), businessAdmin))
                .andExpect(status().isOk());

        // Snapshot dos timestamps originais no banco
        Map<String, Object> originalSchedule = jdbcTemplate.queryForMap(
                "SELECT created_at, updated_at FROM place_opening_hours_schedules WHERE place_id = ?", placeId);
        Object origCreatedAt = originalSchedule.get("created_at");
        Object origUpdatedAt = originalSchedule.get("updated_at");

        // GET confirma visibilidade pública inicial
        TestUser visitor = registerUser("visitor_f2");
        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/opening-hours", placeId), visitor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.days[0].closed").value(false))
                .andExpect(jsonPath("$.days[0].intervals[0].openTime").value("08:00"));

        // 2. Administrador da conta A é temporariamente suspenso
        jdbcTemplate.update("UPDATE users SET account_status = 'SUSPENDED', is_active = false WHERE id = ?", businessAdmin.id());

        // GET passa a retornar configured = false e days = [] (oculto, sem expor dados)
        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/opening-hours", placeId), visitor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.days").isEmpty());

        // Comprova no banco que os dados continuam persistidos intactos
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_schedules WHERE place_id = ?", Integer.class, placeId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeId));

        // 3. Administrador volta a ser operacional (reativado)
        jdbcTemplate.update("UPDATE users SET account_status = 'ACTIVE', is_active = true WHERE id = ?", businessAdmin.id());

        // GET volta a retornar a configuração anterior automaticamente sem nova gravação
        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/opening-hours", placeId), visitor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.days[0].closed").value(false))
                .andExpect(jsonPath("$.days[0].intervals[0].openTime").value("08:00"));

        // Comprova que timestamps no banco não foram alterados
        Map<String, Object> restoredSchedule = jdbcTemplate.queryForMap(
                "SELECT created_at, updated_at FROM place_opening_hours_schedules WHERE place_id = ?", placeId);
        assertEquals(origCreatedAt, restoredSchedule.get("created_at"), "created_at não deve mudar");
        assertEquals(origUpdatedAt, restoredSchedule.get("updated_at"), "updated_at não deve mudar");
    }

    @Test
    @DisplayName("Transferência de local para conta B oculta horários de A até que B configure os seus (F2)")
    void shouldHideOpeningHoursWhenPlaceIsTransferredToAccountBUntilBConfiguresOwnSchedule() throws Exception {
        // 1. Conta A configura horários para o local
        TestUser adminA = registerUser("trans_adm_a");
        UUID placeId = createPlace();
        UUID accountA = createAndApproveBusinessAccount(adminA, placeId);

        UpdatePlaceOpeningHoursRequest requestA = new UpdatePlaceOpeningHoursRequest(
                accountA,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(12, 0))
                )))
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestA)), adminA))
                .andExpect(status().isOk());

        // 2. Conta B aprovada é criada e passa a reivindicar o local (transferência)
        TestUser adminB = registerUser("trans_adm_b");
        UUID accountB = createAccount(adminB);
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'APPROVED' WHERE id = ?", accountB);
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", accountB, placeId);

        // 3. Enquanto B não configura seus próprios horários:
        // GET retorna configured = false e days = [] (não revela horários de A)
        TestUser visitor = registerUser("trans_visitor");
        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/opening-hours", placeId), visitor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.days").isEmpty());

        // Conta A tenta atualizar após perder a titularidade -> 403 Forbidden
        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestA)), adminA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BUSINESS_NOT_RESPONSIBLE"));

        // 4. Conta B configura sua própria grade semanal
        UpdatePlaceOpeningHoursRequest requestB = new UpdatePlaceOpeningHoursRequest(
                accountB,
                List.of(new DayScheduleRequest(2, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(10, 0), LocalTime.of(20, 0))
                )))
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestB)), adminB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.days[0].closed").value(true)) // Segunda de A foi substituída
                .andExpect(jsonPath("$.days[1].dayOfWeek").value(2))
                .andExpect(jsonPath("$.days[1].closed").value(false))
                .andExpect(jsonPath("$.days[1].intervals[0].openTime").value("10:00"));

        // 5. Asserções diretas no banco para a nova configuração
        Map<String, Object> currentSchedule = jdbcTemplate.queryForMap(
                "SELECT business_account_id, updated_by_user_id FROM place_opening_hours_schedules WHERE place_id = ?",
                placeId);
        assertEquals(accountB, currentSchedule.get("business_account_id"));
        assertEquals(adminB.id(), currentSchedule.get("updated_by_user_id"));

        Integer intervalCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeId);
        assertEquals(1, intervalCount);

        Integer dayOfWeek = jdbcTemplate.queryForObject(
                "SELECT day_of_week FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeId);
        assertEquals(2, dayOfWeek, "Intervalo salvo deve ser o de Terça-feira configurado por B");
    }

    @Test
    @DisplayName("Resposta de horários ocultos não revela intervalos, conta proprietária ou metadados de auditoria (F2)")
    void shouldNotExposeAnyInternalDetailsWhenScheduleIsHidden() throws Exception {
        TestUser businessAdmin = registerUser("priv_adm");
        UUID placeId = createPlace();
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                )))
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)), businessAdmin))
                .andExpect(status().isOk());

        // Inelegibilidade por desvinculação
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = NULL WHERE id = ?", placeId);

        TestUser visitor = registerUser("auditor_v");
        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/opening-hours", placeId), visitor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.days").isEmpty())
                .andExpect(jsonPath("$.businessAccountId").doesNotExist())
                .andExpect(jsonPath("$.updatedByUserId").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist());
    }

    @Test
    @DisplayName("GET responde 404 PLACE_NOT_FOUND para local inexistente ou inativo mesmo se houver schedule no banco (Regressão)")
    void shouldReturn404WhenPlaceIsInactiveOrNonExistentEvenIfScheduleExistsInDatabase() throws Exception {
        TestUser businessAdmin = registerUser("inact_reg_adm");
        UUID placeId = createPlace();
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                )))
        );

        mockMvc.perform(withToken(
                put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)), businessAdmin))
                .andExpect(status().isOk());

        // 1. Local inexistente -> 404
        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/opening-hours", UUID.randomUUID()), businessAdmin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));

        // 2. Local inativado no catálogo -> 404
        jdbcTemplate.update("UPDATE places SET status = 'INACTIVE' WHERE id = ?", placeId);
        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/opening-hours", placeId), businessAdmin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, TestUser user) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private TestUser registerUser(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "." + suffix + "@rewit.test";
        String password = "Senha@" + suffix;
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(
                                email, password, prefix + "_" + suffix, "Nome " + suffix
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = read(result);
        return new TestUser(
                UUID.fromString(node.get("user").get("id").asText()),
                email,
                password,
                node.get("accessToken").asText()
        );
    }

    private UUID createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place saved = placeRepository.save(new Place(
                null, "Local Horário " + suffix, "horario-" + UUID.randomUUID(),
                "RESTAURANTE", "Descrição", "Rua dos Horários, 100", "Curitiba", "PR", "BR",
                -25.43, -49.27, 50, "USER", false, null, "ACTIVE"
        ));
        return saved.getId();
    }

    private UUID createAccount(TestUser user) throws Exception {
        MvcResult result = mockMvc.perform(withToken(
                post("/api/v1/business-accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "corporateName", "Empresa " + user.id().toString().substring(0, 8),
                                "taxId", randomTaxId()
                        ))), user))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").asText());
    }

    private UUID createAndApproveBusinessAccount(TestUser user, UUID placeId) throws Exception {
        UUID accountId = createAccount(user);
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'APPROVED' WHERE id = ?", accountId);
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", accountId, placeId);
        return accountId;
    }

    private static String randomTaxId() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            builder.append(ThreadLocalRandom.current().nextInt(10));
        }
        return builder.toString();
    }
}
