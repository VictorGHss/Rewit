package com.rewit.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.business.BusinessDtos.PlaceClaimView;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.usecase.DecidePlaceClaimUseCase;
import com.rewit.application.usecase.RequestPlaceClaimUseCase;
import com.rewit.domain.enums.PlaceClaimDecision;
import com.rewit.domain.model.Place;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.config.PrivilegedTestDatabase;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V29 / ADR-014 contra o PostgreSQL real: quando {@code places.claimed_by_business_id} muda de fato, o trigger
 * {@code trg_places_owner_change_invalidates_commercial_data} apaga as configurações mestres de horários e comodidades
 * do local, e as FKs {@code ON DELETE CASCADE} removem intervalos e seleções, na mesma transação da mudança.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Integração PostgreSQL: invalidação de horários e comodidades na mudança de titularidade (V29, ADR-014)")
class PlaceCommercialDataOwnerChangeIntegrationTest {

    private static final String EVIDENCE = "Sou o proprietário; contrato social e alvará disponíveis para conferência.";
    private static final String REASON = "Documentação conferida e compatível com o local.";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RequestPlaceClaimUseCase requestPlaceClaim;
    @Autowired private DecidePlaceClaimUseCase decidePlaceClaim;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record TestUser(UUID id, String token) {}

    /** Contagens de [schedules, intervals, amenity configurations, amenity selections] de um local. */
    private record Counts(int schedules, int intervals, int amenityConfigurations, int amenitySelections) {
        static final Counts NONE = new Counts(0, 0, 0, 0);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("A → B apaga horários, intervalos, comodidades e seleções, sem tocar o local nem a presença comunitária")
    void transferFromAToBDeletesConfigurationsAndDependents() throws Exception {
        TestUser adminA = registerUser("v29_a");
        UUID placeId = createPlace();
        UUID accountA = approvedOwner(adminA, placeId);
        configure(adminA, placeId, accountA);
        UUID accountB = approvedAccount(registerUser("v29_b"));
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO rateable_targets (id, target_type) VALUES (?, 'PRODUCT')", productId);
        jdbcTemplate.update("INSERT INTO products (id, name, status) VALUES (?, 'Produto V29', 'ACTIVE')", productId);
        jdbcTemplate.update("INSERT INTO product_presences (product_id, place_id) VALUES (?, ?)", productId, placeId);
        Map<String, Object> placeBefore = placeRow(placeId);
        assertEquals(new Counts(1, 3, 1, 3), counts(placeId));

        changeOwner(placeId, accountB);

        assertEquals(Counts.NONE, counts(placeId));
        Map<String, Object> placeAfter = placeRow(placeId);
        assertEquals(accountB, placeAfter.remove("claimed_by_business_id"));
        placeBefore.remove("claimed_by_business_id");
        assertEquals(placeBefore, placeAfter, "o trigger não altera outros dados do local");
        assertEquals(1, countWhere("product_presences", placeId), "presença comunitária não é afetada");
        assertNotConfigured(adminA, placeId);
    }

    @Test
    @DisplayName("A → NULL (desvinculação) apaga horários e comodidades do local")
    void unlinkDeletesConfigurations() throws Exception {
        TestUser admin = registerUser("v29_unlink");
        UUID placeId = createPlace();
        UUID accountId = approvedOwner(admin, placeId);
        configure(admin, placeId, accountId);

        changeOwner(placeId, null);

        assertEquals(Counts.NONE, counts(placeId));
        assertNotConfigured(admin, placeId);
    }

    @Test
    @DisplayName("Nova reivindicação pelo fluxo oficial, pela titular anterior ou por outra conta, não restaura os dados")
    void reclaimDoesNotRestoreInvalidatedData() throws Exception {
        TestUser adminA = registerUser("v29_reclaim_a");
        UUID placeId = createPlace();
        UUID accountA = createAccount(adminA);
        claimViaOfficialFlow(adminA, accountA, placeId);
        configure(adminA, placeId, accountA);

        changeOwner(placeId, null);
        claimViaOfficialFlow(adminA, accountA, placeId);
        assertEquals(accountA, owner(placeId));
        assertEquals(Counts.NONE, counts(placeId));
        assertNotConfigured(adminA, placeId);

        TestUser adminC = registerUser("v29_reclaim_c");
        UUID accountC = createAccount(adminC);
        changeOwner(placeId, null);
        claimViaOfficialFlow(adminC, accountC, placeId);
        assertEquals(accountC, owner(placeId));
        assertEquals(Counts.NONE, counts(placeId));
        assertNotConfigured(adminC, placeId);
    }

    @Test
    @DisplayName("NULL → A também é mudança real de titularidade e apaga configuração existente sem titular anterior")
    void claimFromNoOwnerInvalidatesExistingConfiguration() throws Exception {
        TestUser admin = registerUser("v29_null_a");
        UUID placeId = createPlace();
        UUID accountId = createAccount(admin);
        // Estado semeado só para testar o banco: a API não grava configuração para local sem titular.
        jdbcTemplate.update("INSERT INTO place_opening_hours_schedules (place_id, business_account_id, updated_by_user_id)"
                + " VALUES (?, ?, ?)", placeId, accountId, admin.id());
        jdbcTemplate.update("INSERT INTO place_opening_hours_intervals (id, place_id, day_of_week, open_time, close_time)"
                + " VALUES (?, ?, 2, TIME '10:00', TIME '16:00')", UUID.randomUUID(), placeId);
        jdbcTemplate.update("INSERT INTO place_amenity_configurations (place_id, business_account_id, updated_by_user_id)"
                + " VALUES (?, ?, ?)", placeId, accountId, admin.id());
        jdbcTemplate.update("INSERT INTO place_amenity_selections (place_id, amenity_code) VALUES (?, 'WIFI')", placeId);
        assertEquals(new Counts(1, 1, 1, 1), counts(placeId));

        claimViaOfficialFlow(admin, accountId, placeId);

        assertEquals(accountId, owner(placeId));
        assertEquals(Counts.NONE, counts(placeId));
    }

    @Test
    @DisplayName("Sem mudança real de titular nada é apagado: mesmo titular, status, nome e inelegibilidade temporária")
    void updatesWithoutOwnerChangeKeepConfigurations() throws Exception {
        TestUser admin = registerUser("v29_keep");
        UUID placeId = createPlace();
        UUID accountId = approvedOwner(admin, placeId);
        configure(admin, placeId, accountId);
        Map<String, List<Map<String, Object>>> before = snapshot(placeId);

        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = claimed_by_business_id WHERE id = ?", placeId);
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", accountId, placeId);
        jdbcTemplate.update("UPDATE places SET status = 'INACTIVE' WHERE id = ?", placeId);
        jdbcTemplate.update("UPDATE places SET status = 'ACTIVE', name = name || ' (renomeado)' WHERE id = ?", placeId);
        jdbcTemplate.update("UPDATE users SET account_status = 'SUSPENDED', is_active = false WHERE id = ?", admin.id());
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'REJECTED' WHERE id = ?", accountId);
        assertNotConfigured(registerUser("v29_keep_viewer"), placeId);

        assertEquals(before, snapshot(placeId));
        jdbcTemplate.update("UPDATE users SET account_status = 'ACTIVE', is_active = true WHERE id = ?", admin.id());
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'APPROVED' WHERE id = ?", accountId);
        assertConfigured(admin, placeId);
        assertEquals(before, snapshot(placeId));
    }

    @Test
    @DisplayName("Rollback da troca de titular devolve o titular e todas as configurações, intervalos e seleções")
    void rollbackRestoresOwnerAndConfigurations() throws Exception {
        TestUser admin = registerUser("v29_rollback");
        UUID placeId = createPlace();
        UUID accountA = approvedOwner(admin, placeId);
        UUID accountB = approvedAccount(registerUser("v29_rollback_b"));
        configure(admin, placeId, accountA);
        Map<String, List<Map<String, Object>>> before = snapshot(placeId);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            changeOwner(placeId, accountB);
            assertEquals(Counts.NONE, counts(placeId), "dentro da transação a troca já apagou as configurações");
            throw new IllegalStateException("falha depois da troca de titular");
        }));

        assertEquals(accountA, owner(placeId));
        assertEquals(before, snapshot(placeId));
        assertConfigured(admin, placeId);
    }

    @Test
    @DisplayName("Mecanismo próprio: trigger e função da V29 independentes do T2 das ofertas (V28), que segue igual")
    void mechanismIsIndependentFromOfferInvalidation() {
        Set<String> triggers = jdbcTemplate.queryForList(
                "SELECT tgname FROM pg_trigger WHERE tgrelid = 'places'::regclass AND NOT tgisinternal", String.class)
                .stream().collect(Collectors.toSet());
        assertEquals(Set.of("trg_validate_place_specialization", "trg_places_owner_change_invalidates_offers",
                "trg_places_owner_change_invalidates_commercial_data"), triggers);

        String commercial = functionDefinition("fn_invalidate_place_commercial_data_on_owner_change");
        assertTrue(commercial.contains("place_opening_hours_schedules")
                && commercial.contains("place_amenity_configurations"));
        assertFalse(commercial.contains("place_product_offers")
                || commercial.contains("place_opening_hours_intervals")
                || commercial.contains("place_amenity_selections"), "dependentes saem pelas FKs em cascata");

        String offers = functionDefinition("fn_invalidate_place_product_offers_on_owner_change");
        assertTrue(offers.contains("place_product_offers"));
        assertFalse(offers.contains("place_opening_hours") || offers.contains("place_amenity"));

        String triggerDefinition = jdbcTemplate.queryForObject("SELECT pg_get_triggerdef(oid) FROM pg_trigger"
                + " WHERE tgname = 'trg_places_owner_change_invalidates_commercial_data'", String.class);
        assertTrue(triggerDefinition.contains("AFTER UPDATE OF claimed_by_business_id ON public.places FOR EACH ROW")
                && triggerDefinition.contains("(old.claimed_by_business_id IS DISTINCT FROM new.claimed_by_business_id)"),
                triggerDefinition);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private void configure(TestUser user, UUID placeId, UUID accountId) throws Exception {
        mockMvc.perform(withToken(put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessAccountId\":\"" + accountId + "\",\"days\":["
                                + "{\"dayOfWeek\":1,\"closed\":false,\"intervals\":["
                                + "{\"openTime\":\"08:00\",\"closeTime\":\"12:00\"},"
                                + "{\"openTime\":\"14:00\",\"closeTime\":\"18:00\"}]},"
                                + "{\"dayOfWeek\":6,\"closed\":false,\"intervals\":["
                                + "{\"openTime\":\"09:00\",\"closeTime\":\"13:00\"}]}]}"), user))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(put("/api/v1/places/{placeId}/amenities", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessAccountId\":\"" + accountId + "\","
                                + "\"amenities\":[\"WIFI\",\"PARKING\",\"PET_FRIENDLY\"]}"), user))
                .andExpect(status().isOk());
    }

    private void assertConfigured(TestUser viewer, UUID placeId) throws Exception {
        assertTrue(read(viewer, "/api/v1/places/{placeId}/opening-hours", placeId).get("configured").asBoolean());
        assertTrue(read(viewer, "/api/v1/places/{placeId}/amenities", placeId).get("configured").asBoolean());
    }

    private void assertNotConfigured(TestUser viewer, UUID placeId) throws Exception {
        assertFalse(read(viewer, "/api/v1/places/{placeId}/opening-hours", placeId).get("configured").asBoolean());
        assertFalse(read(viewer, "/api/v1/places/{placeId}/amenities", placeId).get("configured").asBoolean());
    }

    private JsonNode read(TestUser viewer, String path, UUID placeId) throws Exception {
        MvcResult result = mockMvc.perform(withToken(get(path, placeId), viewer))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void changeOwner(UUID placeId, UUID accountId) {
        assertEquals(1, jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?",
                accountId, placeId));
    }

    private UUID owner(UUID placeId) {
        return jdbcTemplate.queryForObject("SELECT claimed_by_business_id FROM places WHERE id = ?", UUID.class,
                placeId);
    }

    private Counts counts(UUID placeId) {
        return new Counts(countWhere("place_opening_hours_schedules", placeId),
                countWhere("place_opening_hours_intervals", placeId),
                countWhere("place_amenity_configurations", placeId),
                countWhere("place_amenity_selections", placeId));
    }

    private int countWhere(String table, UUID placeId) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE place_id = ?",
                Integer.class, placeId);
        return count == null ? 0 : count;
    }

    /** Linhas completas das quatro tabelas do local, em ordem estável. */
    private Map<String, List<Map<String, Object>>> snapshot(UUID placeId) {
        return Map.of(
                "schedules", jdbcTemplate.queryForList(
                        "SELECT * FROM place_opening_hours_schedules WHERE place_id = ?", placeId),
                "intervals", jdbcTemplate.queryForList(
                        "SELECT * FROM place_opening_hours_intervals WHERE place_id = ? ORDER BY day_of_week, open_time",
                        placeId),
                "amenityConfigurations", jdbcTemplate.queryForList(
                        "SELECT * FROM place_amenity_configurations WHERE place_id = ?", placeId),
                "amenitySelections", jdbcTemplate.queryForList(
                        "SELECT * FROM place_amenity_selections WHERE place_id = ? ORDER BY amenity_code", placeId));
    }

    private Map<String, Object> placeRow(UUID placeId) {
        return jdbcTemplate.queryForMap("SELECT id, name, slug, status, claimed_by_business_id, updated_at"
                + " FROM places WHERE id = ?", placeId);
    }

    private String functionDefinition(String name) {
        return jdbcTemplate.queryForObject("SELECT pg_get_functiondef(?::regproc)", String.class, name);
    }

    private void claimViaOfficialFlow(TestUser owner, UUID accountId, UUID placeId) throws Exception {
        UUID moderator = registerUser("v29_moderator").id();
        PrivilegedTestDatabase.jdbc().update("UPDATE users SET role = 'MODERATOR' WHERE id = ?", moderator);
        PlaceClaimView claim = requestPlaceClaim.execute(owner.id(), accountId, placeId, EVIDENCE);
        decidePlaceClaim.execute(moderator, claim.id(), PlaceClaimDecision.APPROVE, REASON, Instant.now());
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, TestUser user) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token());
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
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return new TestUser(UUID.fromString(node.get("user").get("id").asText()), node.get("accessToken").asText());
    }

    private UUID createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place saved = placeRepository.save(new Place(
                null, "Local V29 " + suffix, "v29-" + UUID.randomUUID(),
                "RESTAURANTE", "Descrição", "Rua da Titularidade, 29", "Curitiba", "PR", "BR",
                -25.43, -49.27, 50, "USER", false, null, "ACTIVE"));
        return saved.getId();
    }

    private UUID createAccount(TestUser user) throws Exception {
        MvcResult result = mockMvc.perform(withToken(post("/api/v1/business-accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "corporateName", "Empresa " + user.id().toString().substring(0, 8),
                                "taxId", randomTaxId()))), user))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asText());
    }

    private UUID approvedAccount(TestUser user) throws Exception {
        UUID accountId = createAccount(user);
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'APPROVED' WHERE id = ?", accountId);
        return accountId;
    }

    private UUID approvedOwner(TestUser user, UUID placeId) throws Exception {
        UUID accountId = approvedAccount(user);
        changeOwner(placeId, accountId);
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
