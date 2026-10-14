package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceRepository;
import com.rewit.domain.enums.PlaceAmenity;
import com.rewit.domain.model.Place;
import com.rewit.presentation.dto.auth.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comodidades de estabelecimentos (F2.2) contra o PostgreSQL real: contrato HTTP, autorização da Política B,
 * isolamento entre contas, visibilidade F2, auditoria, trava do local e paridade enum ↔ CHECK da V27.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Integração: comodidades de estabelecimentos (F2.2)")
class PlaceAmenitiesIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    // Catálogo e leitura
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Catálogo lista todos os códigos do enum com rótulos em português, na ordem declarada")
    void catalogListsEveryAmenityInDeclarationOrder() throws Exception {
        TestUser user = registerUser("catalog");

        JsonNode options = read(mockMvc.perform(withToken(get("/api/v1/places/amenity-options"), user))
                .andExpect(status().isOk())
                .andReturn()).get("options");

        PlaceAmenity[] amenities = PlaceAmenity.values();
        assertEquals(amenities.length, options.size());
        for (int i = 0; i < amenities.length; i++) {
            assertEquals(amenities[i].getCode(), options.get(i).get("code").asText());
            assertEquals(amenities[i].getLabel(), options.get(i).get("label").asText());
        }
    }

    @Test
    @DisplayName("Sem token: catálogo, GET e PUT respondem 401 AUTHENTICATION_REQUIRED")
    void unauthenticatedRequestsAreRejected() throws Exception {
        UUID placeId = createPlace();

        mockMvc.perform(get("/api/v1/places/amenity-options"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/v1/places/{placeId}/amenities", placeId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(put("/api/v1/places/{placeId}/amenities", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("Local sem configuração responde configured=false e lista vazia")
    void unconfiguredPlaceReadsAsNotConfigured() throws Exception {
        TestUser visitor = registerUser("reader");
        UUID placeId = createPlace();

        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/amenities", placeId), visitor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.amenities").isEmpty());
    }

    @Test
    @DisplayName("Local inexistente ou fora do catálogo responde 404 PLACE_NOT_FOUND no GET e no PUT, mesmo configurado")
    void missingOrInactivePlaceIsNotFound() throws Exception {
        TestUser admin = registerUser("inactive");
        UUID placeId = createPlace();
        UUID accountId = createApprovedAccountClaiming(admin, placeId);
        putAmenities(admin, placeId, accountId, "WIFI").andExpect(status().isOk());

        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/amenities", UUID.randomUUID()), admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));
        putAmenities(admin, UUID.randomUUID(), accountId, "WIFI")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));

        jdbcTemplate.update("UPDATE places SET status = 'INACTIVE' WHERE id = ?", placeId);
        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/amenities", placeId), admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));
        putAmenities(admin, placeId, accountId, "PARKING")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));
        assertEquals(List.of("WIFI"), selectedCodes(placeId));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Configuração e substituição integral
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Configuração inicial grava conta, autor e seleção; leitura devolve na ordem do catálogo")
    void initialConfigurationIsPersistedAndReadInCatalogOrder() throws Exception {
        TestUser admin = registerUser("initial");
        UUID placeId = createPlace();
        UUID accountId = createApprovedAccountClaiming(admin, placeId);

        putAmenities(admin, placeId, accountId, "PET_FRIENDLY", "WIFI", "PARKING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.amenities.length()").value(3))
                .andExpect(jsonPath("$.amenities[0].code").value("WIFI"))
                .andExpect(jsonPath("$.amenities[0].label").value("Wi-Fi para clientes"))
                .andExpect(jsonPath("$.amenities[1].code").value("PARKING"))
                .andExpect(jsonPath("$.amenities[2].code").value("PET_FRIENDLY"))
                .andExpect(jsonPath("$.businessAccountId").doesNotExist())
                .andExpect(jsonPath("$.updatedByUserId").doesNotExist());

        Map<String, Object> row = configurationRow(placeId);
        assertEquals(accountId, row.get("business_account_id"));
        assertEquals(admin.id(), row.get("updated_by_user_id"));
        assertEquals(Set.of("WIFI", "PARKING", "PET_FRIENDLY"), new HashSet<>(selectedCodes(placeId)));

        TestUser visitor = registerUser("initial_visitor");
        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/amenities", placeId), visitor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.amenities[0].code").value("WIFI"))
                .andExpect(jsonPath("$.amenities[1].code").value("PARKING"))
                .andExpect(jsonPath("$.amenities[2].code").value("PET_FRIENDLY"));
    }

    @Test
    @DisplayName("Nova gravação substitui a seleção inteira, preserva created_at e registra o novo autor")
    void updateReplacesWholeSelectionAndKeepsCreationAudit() throws Exception {
        TestUser admin = registerUser("replace");
        UUID placeId = createPlace();
        UUID accountId = createApprovedAccountClaiming(admin, placeId);
        putAmenities(admin, placeId, accountId, "WIFI", "PARKING").andExpect(status().isOk());
        Map<String, Object> before = configurationRow(placeId);

        // A administração da mesma conta comercial passa a outra pessoa: o autor da última gravação muda.
        TestUser newAdmin = registerUser("replace_new");
        jdbcTemplate.update("UPDATE business_accounts SET user_id = ? WHERE id = ?", newAdmin.id(), accountId);

        putAmenities(newAdmin, placeId, accountId, "OUTDOOR_SEATING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amenities.length()").value(1))
                .andExpect(jsonPath("$.amenities[0].code").value("OUTDOOR_SEATING"));

        Map<String, Object> after = configurationRow(placeId);
        assertEquals(List.of("OUTDOOR_SEATING"), selectedCodes(placeId));
        assertEquals(before.get("created_at"), after.get("created_at"));
        assertNotEquals(before.get("updated_at"), after.get("updated_at"));
        assertEquals(accountId, after.get("business_account_id"));
        assertEquals(newAdmin.id(), after.get("updated_by_user_id"));
    }

    @Test
    @DisplayName("Lista vazia é a declaração deliberada de nenhuma comodidade, distinta de não configurado")
    void emptyListIsDeliberateConfiguration() throws Exception {
        TestUser admin = registerUser("empty");
        UUID placeId = createPlace();
        UUID accountId = createApprovedAccountClaiming(admin, placeId);
        putAmenities(admin, placeId, accountId, "WIFI").andExpect(status().isOk());

        putAmenities(admin, placeId, accountId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.amenities").isEmpty());

        assertEquals(1, count("SELECT count(*) FROM place_amenity_configurations WHERE place_id = ?", placeId));
        assertEquals(List.of(), selectedCodes(placeId));
        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/amenities", placeId), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.amenities").isEmpty());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Validação do payload (RFC 7807) sem mutação parcial
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Payloads inválidos respondem 400 RFC 7807 e preservam seleção e auditoria anteriores")
    void invalidPayloadsAreRejectedWithoutTouchingStoredConfiguration() throws Exception {
        TestUser admin = registerUser("invalid");
        UUID placeId = createPlace();
        UUID accountId = createApprovedAccountClaiming(admin, placeId);
        putAmenities(admin, placeId, accountId, "WIFI", "PARKING").andExpect(status().isOk());
        Map<String, Object> before = configurationRow(placeId);

        putRaw(admin, placeId, "{\"businessAccountId\":\"" + accountId + "\",\"amenities\":[\"WIFI\",\"SAUNA\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_AMENITY_CODE"));
        putRaw(admin, placeId, "{\"businessAccountId\":\"" + accountId + "\",\"amenities\":[\"wifi\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMENITY_CODE"));
        putRaw(admin, placeId, "{\"businessAccountId\":\"" + accountId + "\",\"amenities\":[\"PARKING\",\"PARKING\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_AMENITY"));
        putRaw(admin, placeId, "{\"businessAccountId\":\"" + accountId + "\",\"amenities\":[\"WIFI\",null]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors['amenities[1]']").exists());
        putRaw(admin, placeId, "{\"businessAccountId\":\"" + accountId + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.amenities").exists());
        putRaw(admin, placeId, "{\"amenities\":[\"WIFI\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.businessAccountId").exists());
        putRaw(admin, placeId, "{\"businessAccountId\":\"" + accountId + "\",\"amenities\":\"WIFI\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        putRaw(admin, placeId, "{\"businessAccountId\":\"" + accountId + "\",\"amenities\":[")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        assertEquals(before, configurationRow(placeId));
        assertEquals(Set.of("WIFI", "PARKING"), new HashSet<>(selectedCodes(placeId)));
    }

    @Test
    @DisplayName("Código inválido é recusado antes de qualquer escrita: local nunca configurado continua sem linha")
    void invalidCodeOnFirstWriteCreatesNothing() throws Exception {
        TestUser admin = registerUser("invalid_first");
        UUID placeId = createPlace();
        UUID accountId = createApprovedAccountClaiming(admin, placeId);

        putAmenities(admin, placeId, accountId, "WIFI", "HELIPAD")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMENITY_CODE"));

        assertEquals(0, count("SELECT count(*) FROM place_amenity_configurations WHERE place_id = ?", placeId));
        assertEquals(0, count("SELECT count(*) FROM place_amenity_selections WHERE place_id = ?", placeId));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Autorização (Política B)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Ator inoperante responde 401 ACCOUNT_DISABLED e não altera a configuração")
    void nonOperationalActorIsRejected() throws Exception {
        TestUser admin = registerUser("suspended");
        UUID placeId = createPlace();
        UUID accountId = createApprovedAccountClaiming(admin, placeId);
        putAmenities(admin, placeId, accountId, "WIFI").andExpect(status().isOk());
        Map<String, Object> before = configurationRow(placeId);

        jdbcTemplate.update("UPDATE users SET account_status = 'SUSPENDED', is_active = false WHERE id = ?",
                admin.id());

        putAmenities(admin, placeId, accountId, "PARKING")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        assertEquals(before, configurationRow(placeId));
        assertEquals(List.of("WIFI"), selectedCodes(placeId));
    }

    @Test
    @DisplayName("Conta pendente ou rejeitada responde 409 BUSINESS_ACCOUNT_NOT_APPROVED sem gravar")
    void unapprovedAccountIsRejected() throws Exception {
        TestUser admin = registerUser("pending");
        UUID placeId = createPlace();
        UUID accountId = createAccount(admin);
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", accountId, placeId);

        putAmenities(admin, placeId, accountId, "WIFI")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_ACCOUNT_NOT_APPROVED"));

        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'REJECTED' WHERE id = ?", accountId);
        putAmenities(admin, placeId, accountId, "WIFI")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_ACCOUNT_NOT_APPROVED"));

        assertEquals(0, count("SELECT count(*) FROM place_amenity_configurations WHERE place_id = ?", placeId));
    }

    @Test
    @DisplayName("Conta comercial de outro usuário responde 404 BUSINESS_ACCOUNT_NOT_FOUND")
    void accountOfAnotherUserIsNotFound() throws Exception {
        TestUser owner = registerUser("owner");
        TestUser intruder = registerUser("intruder");
        UUID placeId = createPlace();
        UUID accountId = createApprovedAccountClaiming(owner, placeId);
        putAmenities(owner, placeId, accountId, "WIFI").andExpect(status().isOk());
        Map<String, Object> before = configurationRow(placeId);

        putAmenities(intruder, placeId, accountId, "PARKING")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BUSINESS_ACCOUNT_NOT_FOUND"));
        assertEquals(before, configurationRow(placeId));
        assertEquals(List.of("WIFI"), selectedCodes(placeId));
    }

    @Test
    @DisplayName("Conta A não grava em local reivindicado por B nem em local sem titular (403); dados de B intactos")
    void accountCannotWritePlaceItDoesNotRepresent() throws Exception {
        TestUser adminA = registerUser("iso_a");
        TestUser adminB = registerUser("iso_b");
        UUID placeA = createPlace();
        UUID placeB = createPlace();
        UUID accountA = createApprovedAccountClaiming(adminA, placeA);
        UUID accountB = createApprovedAccountClaiming(adminB, placeB);
        putAmenities(adminA, placeA, accountA, "WIFI").andExpect(status().isOk());
        putAmenities(adminB, placeB, accountB, "PARKING", "AIR_CONDITIONING").andExpect(status().isOk());
        Map<String, Object> beforeB = configurationRow(placeB);

        putAmenities(adminA, placeB, accountA, "PET_FRIENDLY")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BUSINESS_NOT_RESPONSIBLE"));

        UUID unclaimed = createPlace();
        putAmenities(adminA, unclaimed, accountA, "PET_FRIENDLY")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BUSINESS_NOT_RESPONSIBLE"));

        assertEquals(beforeB, configurationRow(placeB));
        assertEquals(Set.of("PARKING", "AIR_CONDITIONING"), new HashSet<>(selectedCodes(placeB)));
        assertEquals(List.of("WIFI"), selectedCodes(placeA));
        assertEquals(0, count("SELECT count(*) FROM place_amenity_configurations WHERE place_id = ?", unclaimed));

        TestUser visitor = registerUser("iso_visitor");
        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/amenities", placeA), visitor))
                .andExpect(jsonPath("$.amenities.length()").value(1))
                .andExpect(jsonPath("$.amenities[0].code").value("WIFI"));
        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/amenities", placeB), visitor))
                .andExpect(jsonPath("$.amenities.length()").value(2))
                .andExpect(jsonPath("$.amenities[0].code").value("PARKING"))
                .andExpect(jsonPath("$.amenities[1].code").value("AIR_CONDITIONING"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Visibilidade F2
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Inelegibilidade temporária oculta a configuração; a mesma representação elegível a revê sem regravar")
    void temporaryIneligibilityHidesAndRestoresWithoutRewrite() throws Exception {
        TestUser admin = registerUser("temp");
        UUID placeId = createPlace();
        UUID accountId = createApprovedAccountClaiming(admin, placeId);
        putAmenities(admin, placeId, accountId, "WIFI", "CUSTOMER_RESTROOM").andExpect(status().isOk());
        Map<String, Object> stored = configurationRow(placeId);
        TestUser visitor = registerUser("temp_visitor");

        // Administrador suspenso: única pessoa operante da conta.
        jdbcTemplate.update("UPDATE users SET account_status = 'SUSPENDED', is_active = false WHERE id = ?",
                admin.id());
        assertHidden(visitor, placeId);
        jdbcTemplate.update("UPDATE users SET account_status = 'ACTIVE', is_active = true WHERE id = ?", admin.id());
        assertVisible(visitor, placeId, "WIFI", "CUSTOMER_RESTROOM");

        // Aprovação revogada e restaurada.
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'REJECTED' WHERE id = ?", accountId);
        assertHidden(visitor, placeId);
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'APPROVED' WHERE id = ?", accountId);
        assertVisible(visitor, placeId, "WIFI", "CUSTOMER_RESTROOM");

        // Vínculo removido e devolvido à mesma conta.
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = NULL WHERE id = ?", placeId);
        assertHidden(visitor, placeId);
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", accountId, placeId);
        assertVisible(visitor, placeId, "WIFI", "CUSTOMER_RESTROOM");

        assertEquals(stored, configurationRow(placeId));
        assertEquals(Set.of("WIFI", "CUSTOMER_RESTROOM"), new HashSet<>(selectedCodes(placeId)));
    }

    @Test
    @DisplayName("Resposta oculta não revela seleção, conta, autor nem datas")
    void hiddenResponseExposesNoMetadata() throws Exception {
        TestUser admin = registerUser("private");
        UUID placeId = createPlace();
        UUID accountId = createApprovedAccountClaiming(admin, placeId);
        putAmenities(admin, placeId, accountId, "WIFI").andExpect(status().isOk());
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = NULL WHERE id = ?", placeId);

        TestUser visitor = registerUser("private_visitor");
        JsonNode body = read(mockMvc.perform(withToken(get("/api/v1/places/{placeId}/amenities", placeId), visitor))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(Set.of("placeId", "configured", "amenities"), fieldNames(body));
        assertEquals(placeId.toString(), body.get("placeId").asText());
        assertEquals(false, body.get("configured").asBoolean());
        assertEquals(0, body.get("amenities").size());
        String raw = body.toString();
        assertTrue(!raw.contains(accountId.toString()) && !raw.contains(admin.id().toString()) && !raw.contains("WIFI"),
                "resposta oculta não pode carregar identificadores nem códigos gravados");
    }

    @Test
    @DisplayName("Transferência: nova titular não herda, a anterior perde a escrita e a nova configura a sua")
    void transferHidesPreviousConfigurationUntilNewOwnerConfigures() throws Exception {
        TestUser adminA = registerUser("transfer_a");
        UUID placeId = createPlace();
        UUID accountA = createApprovedAccountClaiming(adminA, placeId);
        putAmenities(adminA, placeId, accountA, "WIFI", "PARKING").andExpect(status().isOk());

        TestUser adminB = registerUser("transfer_b");
        UUID accountB = createApprovedAccountClaiming(adminB, placeId);
        TestUser visitor = registerUser("transfer_visitor");

        assertHidden(visitor, placeId);
        putAmenities(adminA, placeId, accountA, "PET_FRIENDLY")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BUSINESS_NOT_RESPONSIBLE"));
        assertEquals(accountA, configurationRow(placeId).get("business_account_id"));

        putAmenities(adminB, placeId, accountB, "OUTDOOR_SEATING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.amenities.length()").value(1))
                .andExpect(jsonPath("$.amenities[0].code").value("OUTDOOR_SEATING"));
        assertVisible(visitor, placeId, "OUTDOOR_SEATING");

        Map<String, Object> row = configurationRow(placeId);
        assertEquals(accountB, row.get("business_account_id"));
        assertEquals(adminB.id(), row.get("updated_by_user_id"));
        assertEquals(List.of("OUTDOOR_SEATING"), selectedCodes(placeId));
    }

    @Test
    @DisplayName("Retorno à titular anterior: revê a própria configuração se ninguém regravou; não vê a da intermediária")
    void returnToPreviousOwnerFollowsStoredRepresentation() throws Exception {
        TestUser adminA = registerUser("return_a");
        UUID placeId = createPlace();
        UUID accountA = createApprovedAccountClaiming(adminA, placeId);
        putAmenities(adminA, placeId, accountA, "WIFI").andExpect(status().isOk());
        TestUser visitor = registerUser("return_visitor");

        // B assume e devolve sem configurar: a configuração gravada ainda é de A e volta a aparecer.
        TestUser adminB = registerUser("return_b");
        UUID accountB = createApprovedAccountClaiming(adminB, placeId);
        assertHidden(visitor, placeId);
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", accountA, placeId);
        assertVisible(visitor, placeId, "WIFI");

        // B assume e configura: a de A foi substituída; ao voltar para A, a de B fica oculta até A regravar.
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", accountB, placeId);
        putAmenities(adminB, placeId, accountB, "PARKING").andExpect(status().isOk());
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", accountA, placeId);
        assertHidden(visitor, placeId);
        putAmenities(adminA, placeId, accountA, "WIFI").andExpect(status().isOk());
        assertVisible(visitor, placeId, "WIFI");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Concorrência e consistência transacional
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Gravações concorrentes esperam a trava do local e terminam com uma seleção inteira, nunca mesclada")
    void concurrentWritesSerializeOnPlaceLock() throws Exception {
        TestUser admin = registerUser("concurrent");
        UUID placeId = createPlace();
        UUID accountId = createApprovedAccountClaiming(admin, placeId);
        putAmenities(admin, placeId, accountId, "AIR_CONDITIONING").andExpect(status().isOk());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            // FOR SHARE conflita com o FOR NO KEY UPDATE do caso de uso e não com o FOR KEY SHARE das FKs.
            Future<?> holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                holderPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                jdbcTemplate.queryForObject("SELECT id FROM places WHERE id = ? FOR SHARE", UUID.class, placeId);
                lockAcquired.countDown();
                try {
                    if (!releaseLock.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timeout aguardando liberação do lock de teste");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertTrue(lockAcquired.await(5, TimeUnit.SECONDS), "a transação de teste deve travar o local");

            Future<Integer> first = executor.submit(() -> putStatus(admin, placeId, accountId, "WIFI", "PARKING"));
            int firstPid = waitForPlaceLockWaiter(Set.of(holderPid.get()), Set.of());
            Future<Integer> second = executor.submit(() ->
                    putStatus(admin, placeId, accountId, "PET_FRIENDLY", "OUTDOOR_SEATING", "WHEELCHAIR_ACCESSIBLE"));
            waitForPlaceLockWaiter(Set.of(holderPid.get(), firstPid), Set.of(firstPid));

            // Enquanto travadas, nada mudou.
            assertEquals(List.of("AIR_CONDITIONING"), selectedCodes(placeId));

            releaseLock.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertEquals(200, first.get(10, TimeUnit.SECONDS));
            assertEquals(200, second.get(10, TimeUnit.SECONDS));
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }

        Set<String> finalCodes = new HashSet<>(selectedCodes(placeId));
        assertTrue(finalCodes.equals(Set.of("WIFI", "PARKING"))
                        || finalCodes.equals(Set.of("PET_FRIENDLY", "OUTDOOR_SEATING", "WHEELCHAIR_ACCESSIBLE")),
                "a seleção final deve ser exatamente uma das gravações: " + finalCodes);
        assertEquals(1, count("SELECT count(*) FROM place_amenity_configurations WHERE place_id = ?", placeId));
    }

    /**
     * Espera uma sessão, fora de {@code excluded}, parada em lock na leitura travada de places e bloqueada por alguma
     * de {@code blockers}. Devolve o PID dela.
     */
    private int waitForPlaceLockWaiter(Set<Integer> blockers, Set<Integer> excluded) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String sql = """
                SELECT pid, pg_blocking_pids(pid) AS blockers
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                  AND wait_event_type = 'Lock'
                  AND query ILIKE '%places%'
                  AND (query ILIKE '%for update%' OR query ILIKE '%for no key update%')
                  AND query NOT ILIKE 'INSERT%'
                """;
        while (System.nanoTime() < deadline) {
            for (Map<String, Object> row : jdbcTemplate.queryForList(sql)) {
                int pid = ((Number) row.get("pid")).intValue();
                if (excluded.contains(pid)) {
                    continue;
                }
                Object[] rowBlockers = (Object[]) toArray(row.get("blockers"));
                for (Object blocker : rowBlockers) {
                    if (blockers.contains(((Number) blocker).intValue())) {
                        return pid;
                    }
                }
            }
            Thread.sleep(25);
        }
        fail("a gravação deveria estar esperando a trava de places, bloqueada por " + blockers);
        return -1;
    }

    private static Object toArray(Object sqlArray) {
        try {
            return ((java.sql.Array) sqlArray).getArray();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Banco: paridade do catálogo e restrições da V27
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CHECK chk_place_amenity_code aceita exatamente os códigos do enum PlaceAmenity")
    void databaseCheckMatchesEnum() throws Exception {
        String definition = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_place_amenity_code'",
                String.class);
        Matcher matcher = Pattern.compile("'([A-Z_]+)'").matcher(definition);
        Set<String> checkCodes = new HashSet<>();
        while (matcher.find()) {
            checkCodes.add(matcher.group(1));
        }
        Set<String> enumCodes = Arrays.stream(PlaceAmenity.values()).map(PlaceAmenity::getCode)
                .collect(Collectors.toSet());
        assertEquals(enumCodes, checkCodes);

        // E o caminho real grava todos eles.
        TestUser admin = registerUser("parity");
        UUID placeId = createPlace();
        UUID accountId = createApprovedAccountClaiming(admin, placeId);
        putAmenities(admin, placeId, accountId, enumCodes.toArray(String[]::new))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amenities.length()").value(enumCodes.size()));
        assertEquals(enumCodes, new HashSet<>(selectedCodes(placeId)));
    }

    @Test
    @DisplayName("Banco recusa código fora do catálogo, seleção duplicada e seleção sem configuração")
    void databaseConstraintsGuardSelections() throws Exception {
        TestUser admin = registerUser("constraints");
        UUID placeId = createPlace();
        UUID accountId = createApprovedAccountClaiming(admin, placeId);
        putAmenities(admin, placeId, accountId, "WIFI").andExpect(status().isOk());

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO place_amenity_selections (place_id, amenity_code) VALUES (?, 'SAUNA')", placeId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO place_amenity_selections (place_id, amenity_code) VALUES (?, 'WIFI')", placeId));
        UUID unconfigured = createPlace();
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO place_amenity_selections (place_id, amenity_code) VALUES (?, 'WIFI')", unconfigured));
        assertEquals(List.of("WIFI"), selectedCodes(placeId));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private void assertHidden(TestUser viewer, UUID placeId) throws Exception {
        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/amenities", placeId), viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.amenities").isEmpty());
    }

    private void assertVisible(TestUser viewer, UUID placeId, String... codesInCatalogOrder) throws Exception {
        JsonNode body = read(mockMvc.perform(withToken(get("/api/v1/places/{placeId}/amenities", placeId), viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andReturn());
        List<String> codes = new java.util.ArrayList<>();
        body.get("amenities").forEach(node -> codes.add(node.get("code").asText()));
        assertEquals(List.of(codesInCatalogOrder), codes);
    }

    private ResultActions putAmenities(TestUser user, UUID placeId, UUID accountId, String... codes) throws Exception {
        return putRaw(user, placeId, objectMapper.writeValueAsString(Map.of(
                "businessAccountId", accountId.toString(),
                "amenities", List.of(codes))));
    }

    private ResultActions putRaw(TestUser user, UUID placeId, String body) throws Exception {
        return mockMvc.perform(withToken(put("/api/v1/places/{placeId}/amenities", placeId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body), user));
    }

    private int putStatus(TestUser user, UUID placeId, UUID accountId, String... codes) {
        try {
            return putAmenities(user, placeId, accountId, codes).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            return 500;
        }
    }

    private Map<String, Object> configurationRow(UUID placeId) {
        return jdbcTemplate.queryForMap("SELECT business_account_id, updated_by_user_id, created_at, updated_at "
                + "FROM place_amenity_configurations WHERE place_id = ?", placeId);
    }

    private List<String> selectedCodes(UUID placeId) {
        return jdbcTemplate.queryForList(
                "SELECT amenity_code FROM place_amenity_selections WHERE place_id = ? ORDER BY amenity_code",
                String.class, placeId);
    }

    private int count(String sql, UUID placeId) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, placeId);
        return value == null ? 0 : value;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
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

    private UUID createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place saved = placeRepository.save(new Place(
                null, "Local Comodidades " + suffix, "comodidades-" + UUID.randomUUID(),
                "RESTAURANTE", "Descrição", "Rua das Comodidades, 100", "Curitiba", "PR", "BR",
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
                                        "taxId", randomTaxId()))), user))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").asText());
    }

    private UUID createApprovedAccountClaiming(TestUser user, UUID placeId) throws Exception {
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
