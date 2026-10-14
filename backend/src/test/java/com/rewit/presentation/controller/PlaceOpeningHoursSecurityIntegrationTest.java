package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceRepository;
import com.rewit.domain.model.Place;
import com.rewit.presentation.dto.auth.LoginRequest;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.catalog.PlaceOpeningHoursPresentationDtos.DayScheduleRequest;
import com.rewit.presentation.dto.catalog.PlaceOpeningHoursPresentationDtos.TimeIntervalRequest;
import com.rewit.presentation.dto.catalog.PlaceOpeningHoursPresentationDtos.UpdatePlaceOpeningHoursRequest;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    @DisplayName("Atualizações concorrentes no mesmo local se serializam via lock de banco e preservam consistência")
    void concurrentUpdatesSerializeAndPreserveConsistency() throws Exception {
        TestUser businessAdmin = registerUser("conc_admin");
        UUID placeId = createPlace();
        UUID accountId = createAndApproveBusinessAccount(businessAdmin, placeId);

        UpdatePlaceOpeningHoursRequest requestA = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(12, 0))
                )))
        );

        UpdatePlaceOpeningHoursRequest requestB = new UpdatePlaceOpeningHoursRequest(
                accountId,
                List.of(new DayScheduleRequest(2, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(14, 0), LocalTime.of(18, 0)),
                        new TimeIntervalRequest(LocalTime.of(19, 0), LocalTime.of(22, 0))
                )))
        );

        int threads = 2;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch startSignal = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneSignal = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicInteger statusA = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger statusB = new java.util.concurrent.atomic.AtomicInteger();

        executor.submit(() -> {
            try {
                startSignal.await();
                MvcResult res = mockMvc.perform(withToken(
                        put("/api/v1/places/{placeId}/opening-hours", placeId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestA)), businessAdmin)).andReturn();
                statusA.set(res.getResponse().getStatus());
            } catch (Exception e) {
                statusA.set(500);
            } finally {
                doneSignal.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startSignal.await();
                MvcResult res = mockMvc.perform(withToken(
                        put("/api/v1/places/{placeId}/opening-hours", placeId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestB)), businessAdmin)).andReturn();
                statusB.set(res.getResponse().getStatus());
            } catch (Exception e) {
                statusB.set(500);
            } finally {
                doneSignal.countDown();
            }
        });

        startSignal.countDown();
        doneSignal.await();
        executor.shutdown();

        assertEquals(200, statusA.get(), "Requisição A deve completar com 200 OK após serialização");
        assertEquals(200, statusB.get(), "Requisição B deve completar com 200 OK após serialização");

        // Demonstra serialização determinística e consistência do estado final:
        // Apenas 1 registro de schedule deve existir para o local
        Integer scheduleCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_schedules WHERE place_id = ?", Integer.class, placeId);
        assertEquals(1, scheduleCount);

        // A persistência final deve refletir exatamente um dos requests:
        // - se A venceu por último: exatamente 1 intervalo no dia 1 (08:00 - 12:00)
        // - se B venceu por último: exatamente 2 intervalos no dia 2 (14:00 - 18:00 e 19:00 - 22:00)
        Integer finalIntervalCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_opening_hours_intervals WHERE place_id = ?",
                Integer.class, placeId);
        assertTrue(finalIntervalCount == 1 || finalIntervalCount == 2,
                "A quantidade de intervalos gravada deve ser estritamente consistente com uma das duas atualizações atômicas");

        if (finalIntervalCount == 1) {
            Integer dayOfWeek = jdbcTemplate.queryForObject(
                    "SELECT day_of_week FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeId);
            assertEquals(1, dayOfWeek, "Se A foi o último a comitar, os intervalos devem pertencer exclusivamente ao dia 1");
        } else {
            List<Integer> days = jdbcTemplate.queryForList(
                    "SELECT day_of_week FROM place_opening_hours_intervals WHERE place_id = ?", Integer.class, placeId);
            assertTrue(days.stream().allMatch(d -> d == 2), "Se B foi o último a comitar, os intervalos devem pertencer exclusivamente ao dia 2");
        }
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
