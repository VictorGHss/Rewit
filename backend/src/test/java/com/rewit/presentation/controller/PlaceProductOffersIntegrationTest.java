package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.rewit.application.dto.business.BusinessDtos.PlaceClaimView;
import com.rewit.application.dto.catalog.PlaceProductOfferDtos.UpsertPlaceProductOfferCommand;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProductRepository;
import com.rewit.application.usecase.DecidePlaceClaimUseCase;
import com.rewit.application.usecase.RequestPlaceClaimUseCase;
import com.rewit.application.usecase.UpsertPlaceProductOfferUseCase;
import com.rewit.domain.enums.OfferAvailability;
import com.rewit.domain.enums.PlaceClaimDecision;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Product;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.config.PrivilegedTestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ofertas comerciais (F2.3) contra o PostgreSQL real: contrato HTTP, autorização da Política B, visibilidade, decisão
 * T2 da V28 (troca de titular apaga as ofertas anteriores), rollback e trava do local.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Integração: ofertas comerciais de produtos por estabelecimento (F2.3)")
class PlaceProductOffersIntegrationTest {

    private static final String EVIDENCE = "Sou o proprietário; contrato social e alvará disponíveis para conferência.";
    private static final String REASON = "Documentação conferida e compatível com o local.";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RequestPlaceClaimUseCase requestPlaceClaim;
    @Autowired private DecidePlaceClaimUseCase decidePlaceClaim;
    @Autowired private UpsertPlaceProductOfferUseCase upsertOffer;

    private MockMvc mockMvc;
    // Decimais exatos: por padrão a árvore JSON remove zeros à direita e esconderia a escala enviada pela API.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

    private record TestUser(UUID id, String token) {}

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Escrita: criação, atualização idempotente e preço
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("PUT cria, repete sem regravar, atualiza preservando a criação e limpa o preço divulgado")
    void putCreatesIsIdempotentUpdatesAndClearsPrice() throws Exception {
        TestUser admin = registerUser("offer_put");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = approvedOwner(admin, placeId);
        UUID productId = product("ACTIVE");

        MvcResult createdResult = putOffer(admin, placeId, productId, body(accountId, "AVAILABLE", "12.9", null))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();
        assertTrue(createdResult.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("\"price\":12.90"),
                "a API envia o preço com duas casas");
        JsonNode created = read(createdResult);
        assertEquals(productId.toString(), created.get("product").get("id").asText());
        assertEquals("AVAILABLE", created.get("availability").asText());
        assertEquals(new BigDecimal("12.90"), created.get("price").decimalValue());
        assertEquals("BRL", created.get("currency").asText());
        assertTrue(created.get("businessAccountId") == null && created.get("updatedAt") == null);

        Map<String, Object> first = offerRow(placeId, productId);
        assertEquals(accountId, first.get("business_account_id"));
        assertEquals(admin.id(), first.get("updated_by_user_id"));
        assertEquals(0, new BigDecimal("12.90").compareTo((BigDecimal) first.get("price")));

        // Mesmo estado (12.90 == 12.9): nada é regravado.
        putOffer(admin, placeId, productId, body(accountId, "AVAILABLE", "12.90", "\"BRL\""))
                .andExpect(status().isOk());
        assertEquals(first, offerRow(placeId, productId));

        putOffer(admin, placeId, productId, body(accountId, "OUT_OF_STOCK", "0", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("OUT_OF_STOCK"));
        Map<String, Object> updated = offerRow(placeId, productId);
        assertEquals(first.get("id"), updated.get("id"));
        assertEquals(first.get("created_at"), updated.get("created_at"));
        assertNotEquals(first.get("updated_at"), updated.get("updated_at"));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) updated.get("price")), "0.00 é gratuito, não ausente");

        JsonNode cleared = read(putOffer(admin, placeId, productId, body(accountId, "OUT_OF_STOCK", null, null))
                .andExpect(status().isOk()).andReturn());
        assertTrue(cleared.get("price").isNull(), "preço omitido = não divulgado");
        assertNull(offerRow(placeId, productId).get("price"));

        putOffer(admin, placeId, productId, body(accountId, "AVAILABLE", "null", null)).andExpect(status().isOk());
        assertNull(offerRow(placeId, productId).get("price"));
        assertEquals(1, offerCount(placeId));
    }

    @Test
    @DisplayName("Payload inválido responde 400 RFC 7807 antes de gravar e preserva a oferta existente")
    void invalidPayloadIsRejectedWithoutTouchingStoredOffer() throws Exception {
        TestUser admin = registerUser("offer_invalid");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = approvedOwner(admin, placeId);
        UUID productId = product("ACTIVE");
        putOffer(admin, placeId, productId, body(accountId, "AVAILABLE", "5.50", null)).andExpect(status().isOk());
        Map<String, Object> before = offerRow(placeId, productId);

        Map<String, String> invalid = Map.of(
                body(accountId, "AVAILABLE", "-0.01", null), "INVALID_OFFER_PRICE",
                body(accountId, "AVAILABLE", "1.234", null), "INVALID_OFFER_PRICE",
                body(accountId, "AVAILABLE", "10000000000.00", null), "INVALID_OFFER_PRICE",
                body(accountId, "AVAILABLE", "1.00", "\"USD\""), "UNSUPPORTED_CURRENCY",
                body(accountId, "AVAILABLE", "1.00", "\"brl\""), "UNSUPPORTED_CURRENCY",
                body(accountId, "SOLD_OUT", "1.00", null), "INVALID_OFFER_AVAILABILITY",
                body(accountId, "available", "1.00", null), "INVALID_OFFER_AVAILABILITY",
                body(accountId, null, "1.00", null), "VALIDATION_ERROR",
                "{\"availability\":\"AVAILABLE\"}", "VALIDATION_ERROR",
                body(accountId, "AVAILABLE", "\"abc\"", null), "MALFORMED_REQUEST");
        for (Map.Entry<String, String> entry : invalid.entrySet()) {
            putOffer(admin, placeId, productId, entry.getKey())
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value(entry.getValue()));
        }
        assertEquals(before, offerRow(placeId, productId));

        UUID untouched = product("ACTIVE");
        putOffer(admin, placeId, untouched, body(accountId, "AVAILABLE", "-1", null))
                .andExpect(status().isBadRequest());
        assertEquals(List.of(), offerRows(placeId, untouched));
    }

    @Test
    @DisplayName("Produto global inexistente, inativo ou descontinuado responde 404 PRODUCT_NOT_FOUND sem gravar")
    void missingOrInactiveProductIsNotFound() throws Exception {
        TestUser admin = registerUser("offer_product");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = approvedOwner(admin, placeId);

        for (UUID productId : List.of(UUID.randomUUID(), product("INACTIVE"), product("DISCONTINUED"))) {
            putOffer(admin, placeId, productId, body(accountId, "AVAILABLE", "1.00", null))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        }
        assertEquals(0, offerCount(placeId));
    }

    @Test
    @DisplayName("Local inexistente ou inativo responde 404 PLACE_NOT_FOUND no GET, PUT e DELETE")
    void missingOrInactivePlaceIsNotFound() throws Exception {
        TestUser admin = registerUser("offer_place");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = approvedOwner(admin, placeId);
        UUID productId = product("ACTIVE");
        putOffer(admin, placeId, productId, body(accountId, "AVAILABLE", "3.00", null)).andExpect(status().isOk());

        jdbcTemplate.update("UPDATE places SET status = 'INACTIVE' WHERE id = ?", placeId);
        for (UUID target : List.of(placeId, UUID.randomUUID())) {
            mockMvc.perform(withToken(get("/api/v1/places/{placeId}/offers", target), admin))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));
            putOffer(admin, target, productId, body(accountId, "OUT_OF_STOCK", null, null))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));
            deleteOffer(admin, target, productId, accountId)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));
        }
        assertEquals(1, offerCount(placeId), "status do local não apaga ofertas");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Autorização (Política B) e isolamento entre contas
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Conta inexistente ou de outro usuário (404), pendente ou rejeitada (409) e ator inoperante (401)")
    void accountAndActorChecks() throws Exception {
        TestUser owner = registerUser("offer_owner");
        TestUser intruder = registerUser("offer_intruder");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = approvedOwner(owner, placeId);
        UUID productId = product("ACTIVE");
        putOffer(owner, placeId, productId, body(accountId, "AVAILABLE", "8.00", null)).andExpect(status().isOk());
        Map<String, Object> before = offerRow(placeId, productId);

        expectCode(putOffer(owner, placeId, productId, body(UUID.randomUUID(), "AVAILABLE", null, null)),
                404, "BUSINESS_ACCOUNT_NOT_FOUND");
        expectCode(putOffer(intruder, placeId, productId, body(accountId, "AVAILABLE", null, null)),
                404, "BUSINESS_ACCOUNT_NOT_FOUND");
        expectCode(deleteOffer(intruder, placeId, productId, accountId), 404, "BUSINESS_ACCOUNT_NOT_FOUND");

        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'PENDING' WHERE id = ?", accountId);
        expectCode(putOffer(owner, placeId, productId, body(accountId, "OUT_OF_STOCK", null, null)),
                409, "BUSINESS_ACCOUNT_NOT_APPROVED");
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'REJECTED' WHERE id = ?", accountId);
        expectCode(deleteOffer(owner, placeId, productId, accountId), 409, "BUSINESS_ACCOUNT_NOT_APPROVED");
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'APPROVED' WHERE id = ?", accountId);

        jdbcTemplate.update("UPDATE users SET account_status = 'SUSPENDED', is_active = false WHERE id = ?", owner.id());
        expectCode(putOffer(owner, placeId, productId, body(accountId, "OUT_OF_STOCK", null, null)),
                401, "ACCOUNT_DISABLED");
        expectCode(deleteOffer(owner, placeId, productId, accountId), 401, "ACCOUNT_DISABLED");

        assertEquals(before, offerRow(placeId, productId));
    }

    @Test
    @DisplayName("Conta A não cria, altera nem apaga ofertas do local de B (403); dados de B intactos")
    void accountCannotManageOffersOfPlaceItDoesNotRepresent() throws Exception {
        TestUser adminA = registerUser("offer_iso_a");
        TestUser adminB = registerUser("offer_iso_b");
        UUID placeA = createPlace("ACTIVE");
        UUID placeB = createPlace("ACTIVE");
        UUID accountA = approvedOwner(adminA, placeA);
        UUID accountB = approvedOwner(adminB, placeB);
        UUID productId = product("ACTIVE");
        putOffer(adminB, placeB, productId, body(accountB, "AVAILABLE", "20.00", null)).andExpect(status().isOk());
        Map<String, Object> beforeB = offerRow(placeB, productId);

        expectCode(putOffer(adminA, placeB, productId, body(accountA, "OUT_OF_STOCK", "1.00", null)),
                403, "BUSINESS_NOT_RESPONSIBLE");
        expectCode(deleteOffer(adminA, placeB, productId, accountA), 403, "BUSINESS_NOT_RESPONSIBLE");
        expectCode(putOffer(adminA, placeB, product("ACTIVE"), body(accountA, "AVAILABLE", null, null)),
                403, "BUSINESS_NOT_RESPONSIBLE");
        UUID unclaimed = createPlace("ACTIVE");
        expectCode(putOffer(adminA, unclaimed, productId, body(accountA, "AVAILABLE", null, null)),
                403, "BUSINESS_NOT_RESPONSIBLE");

        assertEquals(beforeB, offerRow(placeB, productId));
        assertEquals(1, offerCount(placeB));
        assertEquals(0, offerCount(placeA) + offerCount(unclaimed));
    }

    @Test
    @DisplayName("Sem token: GET, PUT e DELETE respondem 401; DELETE sem conta responde 400 MISSING_PARAMETER")
    void unauthenticatedAndMissingAccountParameter() throws Exception {
        UUID placeId = createPlace("ACTIVE");
        UUID productId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/places/{placeId}/offers", placeId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(put("/api/v1/places/{placeId}/offers/{productId}", placeId, productId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(delete("/api/v1/places/{placeId}/offers/{productId}", placeId, productId)
                        .param("businessAccountId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        TestUser user = registerUser("offer_param");
        mockMvc.perform(withToken(delete("/api/v1/places/{placeId}/offers/{productId}", placeId, productId), user))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Leitura e DELETE
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("GET: vazio, ordenado pelo nome do produto, paginado, e esconde produto global desativado")
    void listShowsVisibleOffersInProductOrder() throws Exception {
        TestUser admin = registerUser("offer_list");
        TestUser visitor = registerUser("offer_visitor");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = approvedOwner(admin, placeId);

        JsonNode empty = list(visitor, placeId, 0, 20);
        assertEquals(0, empty.get("content").size());
        assertEquals(0, empty.get("totalElements").asLong());

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID banana = product("Banana " + suffix, "ACTIVE");
        UUID abacaxi = product("Abacaxi " + suffix, "ACTIVE");
        UUID caju = product("Caju " + suffix, "ACTIVE");
        putOffer(admin, placeId, banana, body(accountId, "OUT_OF_STOCK", null, null)).andExpect(status().isOk());
        putOffer(admin, placeId, abacaxi, body(accountId, "AVAILABLE", "7.5", null)).andExpect(status().isOk());
        putOffer(admin, placeId, caju, body(accountId, "AVAILABLE", "0", null)).andExpect(status().isOk());

        JsonNode page = list(visitor, placeId, 0, 2);
        assertEquals(List.of(abacaxi.toString(), banana.toString()), productIds(page));
        assertEquals(3, page.get("totalElements").asLong());
        assertEquals(2, page.get("totalPages").asInt());
        assertEquals(new BigDecimal("7.50"), page.get("content").get(0).get("price").decimalValue());
        assertEquals("Abacaxi " + suffix, page.get("content").get(0).get("product").get("name").asText());
        assertTrue(page.get("content").get(1).get("price").isNull());
        assertEquals("OUT_OF_STOCK", page.get("content").get(1).get("availability").asText());
        assertEquals(Set.of("product", "availability", "price", "currency"),
                fieldNames(page.get("content").get(0)));
        assertEquals(List.of(caju.toString()), productIds(list(visitor, placeId, 1, 2)));

        // Desativar o produto global oculta a oferta sem apagá-la; reativar devolve.
        jdbcTemplate.update("UPDATE products SET status = 'INACTIVE' WHERE id = ?", abacaxi);
        assertEquals(List.of(banana.toString(), caju.toString()), productIds(list(visitor, placeId, 0, 20)));
        assertEquals(3, offerCount(placeId));
        jdbcTemplate.update("UPDATE products SET status = 'ACTIVE' WHERE id = ?", abacaxi);
        assertEquals(3, list(visitor, placeId, 0, 20).get("totalElements").asLong());

        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/offers", placeId).param("size", "51"), visitor))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"));
        mockMvc.perform(withToken(get("/api/v1/places/{placeId}/offers", placeId).param("page", "-1"), visitor))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAGE"));
    }

    @Test
    @DisplayName("DELETE retira a oferta (204), não toca produto global nem presença comunitária; repetir dá 404")
    void deleteRemovesOnlyTheOffer() throws Exception {
        TestUser admin = registerUser("offer_delete");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = approvedOwner(admin, placeId);
        UUID productId = product("ACTIVE");
        jdbcTemplate.update("INSERT INTO product_presences (product_id, place_id, reported_by_user_id) VALUES (?, ?, ?)",
                productId, placeId, admin.id());
        putOffer(admin, placeId, productId, body(accountId, "AVAILABLE", "9.99", null)).andExpect(status().isOk());
        Map<String, Object> productBefore = jdbcTemplate.queryForMap("SELECT * FROM products WHERE id = ?", productId);
        Map<String, Object> presenceBefore = jdbcTemplate.queryForMap(
                "SELECT * FROM product_presences WHERE product_id = ? AND place_id = ?", productId, placeId);

        deleteOffer(admin, placeId, productId, accountId).andExpect(status().isNoContent());
        assertEquals(List.of(), offerRows(placeId, productId));
        assertEquals(productBefore, jdbcTemplate.queryForMap("SELECT * FROM products WHERE id = ?", productId));
        assertEquals(presenceBefore, jdbcTemplate.queryForMap(
                "SELECT * FROM product_presences WHERE product_id = ? AND place_id = ?", productId, placeId));

        expectCode(deleteOffer(admin, placeId, productId, accountId), 404, "PLACE_PRODUCT_OFFER_NOT_FOUND");
        expectCode(deleteOffer(admin, placeId, UUID.randomUUID(), accountId), 404, "PLACE_PRODUCT_OFFER_NOT_FOUND");

        // Produto desativado depois de ofertado: a oferta some da vitrine e ainda pode ser retirada.
        UUID retired = product("ACTIVE");
        putOffer(admin, placeId, retired, body(accountId, "AVAILABLE", null, null)).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE products SET status = 'DISCONTINUED' WHERE id = ?", retired);
        deleteOffer(admin, placeId, retired, accountId).andExpect(status().isNoContent());
        assertEquals(0, offerCount(placeId));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Visibilidade e decisão T2
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Inelegibilidade temporária sem troca de titular oculta e devolve as ofertas sem alterar o banco")
    void temporaryIneligibilityHidesWithoutDestroyingOffers() throws Exception {
        TestUser admin = registerUser("offer_temp");
        TestUser visitor = registerUser("offer_temp_visitor");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = approvedOwner(admin, placeId);
        UUID productId = product("ACTIVE");
        putOffer(admin, placeId, productId, body(accountId, "AVAILABLE", "4.20", null)).andExpect(status().isOk());
        Map<String, Object> stored = offerRow(placeId, productId);

        List<Consumer<Boolean>> toggles = List.of(
                hidden -> jdbcTemplate.update("UPDATE users SET account_status = ?, is_active = ? WHERE id = ?",
                        hidden ? "SUSPENDED" : "ACTIVE", !hidden, admin.id()),
                hidden -> jdbcTemplate.update("UPDATE business_accounts SET verification_status = ? WHERE id = ?",
                        hidden ? "REJECTED" : "APPROVED", accountId),
                hidden -> jdbcTemplate.update("UPDATE business_accounts SET verification_status = ? WHERE id = ?",
                        hidden ? "PENDING" : "APPROVED", accountId));
        for (Consumer<Boolean> toggle : toggles) {
            toggle.accept(true);
            JsonNode hidden = list(visitor, placeId, 0, 20);
            assertEquals(0, hidden.get("content").size());
            assertEquals(0, hidden.get("totalElements").asLong());
            assertTrue(!hidden.toString().contains("4.20") && !hidden.toString().contains(productId.toString()));
            toggle.accept(false);
            assertEquals(List.of(productId.toString()), productIds(list(visitor, placeId, 0, 20)));
        }

        // Status do local e regravação do mesmo titular também não apagam nada.
        jdbcTemplate.update("UPDATE places SET status = 'CLOSED' WHERE id = ?", placeId);
        jdbcTemplate.update("UPDATE places SET status = 'ACTIVE' WHERE id = ?", placeId);
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = claimed_by_business_id WHERE id = ?", placeId);
        assertEquals(List.of(productId.toString()), productIds(list(visitor, placeId, 0, 20)));
        assertEquals(stored, offerRow(placeId, productId));
    }

    @Test
    @DisplayName("T2: A → B invalida as ofertas de A; B → A não as reexibe e A precisa recadastrar")
    void ownerChangeInvalidatesOffersDefinitively() throws Exception {
        TestUser adminA = registerUser("t2_a");
        TestUser adminB = registerUser("t2_b");
        TestUser visitor = registerUser("t2_visitor");
        UUID placeId = createPlace("ACTIVE");
        UUID accountA = createAccount(adminA);
        claimViaOfficialFlow(adminA, accountA, placeId);
        UUID accountB = createAccount(adminB);
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'APPROVED' WHERE id = ?", accountB);
        UUID productA1 = product("ACTIVE");
        UUID productA2 = product("ACTIVE");
        putOffer(adminA, placeId, productA1, body(accountA, "AVAILABLE", "15.00", null)).andExpect(status().isOk());
        putOffer(adminA, placeId, productA2, body(accountA, "OUT_OF_STOCK", "2.00", null)).andExpect(status().isOk());
        assertEquals(2, offerCount(placeId));

        // A → B: não há fluxo oficial de transferência; a mudança direta simula qualquer caminho futuro.
        transferOwnership(placeId, accountB);
        assertEquals(0, offerCount(placeId), "T2 apaga as ofertas de A na própria mudança de titular");
        assertEquals(0, list(visitor, placeId, 0, 20).get("totalElements").asLong());
        expectCode(putOffer(adminA, placeId, productA1, body(accountA, "AVAILABLE", "15.00", null)),
                403, "BUSINESS_NOT_RESPONSIBLE");

        UUID productB = product("ACTIVE");
        putOffer(adminB, placeId, productB, body(accountB, "AVAILABLE", "30.00", null)).andExpect(status().isOk());
        assertEquals(List.of(productB.toString()), productIds(list(visitor, placeId, 0, 20)));

        // B → A: as ofertas de B são apagadas e as antigas de A não voltam.
        transferOwnership(placeId, accountA);
        assertEquals(0, offerCount(placeId));
        assertEquals(0, list(visitor, placeId, 0, 20).get("totalElements").asLong());

        putOffer(adminA, placeId, productA1, body(accountA, "AVAILABLE", "16.00", null)).andExpect(status().isOk());
        JsonNode recreated = list(visitor, placeId, 0, 20);
        assertEquals(List.of(productA1.toString()), productIds(recreated));
        assertEquals(new BigDecimal("16.00"), recreated.get("content").get(0).get("price").decimalValue());
    }

    @Test
    @DisplayName("T2: a troca de titular apaga todas as ofertas do local, inclusive linha já associada à conta que entra")
    void ownerChangeRemovesEveryOfferEvenOfIncomingAccount() throws Exception {
        TestUser adminA = registerUser("t2_all_a");
        UUID placeId = createPlace("ACTIVE");
        UUID accountA = approvedOwner(adminA, placeId);
        TestUser adminB = registerUser("t2_all_b");
        UUID accountB = createAccount(adminB);
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'APPROVED' WHERE id = ?", accountB);
        UUID productOfA = product("ACTIVE");
        putOffer(adminA, placeId, productOfA, body(accountA, "AVAILABLE", "10.00", null)).andExpect(status().isOk());

        // Estado semeado só para testar a garantia do banco: a API nunca grava oferta de conta que não é a titular.
        UUID productOfB = product("ACTIVE");
        jdbcTemplate.update("INSERT INTO place_product_offers (id, place_id, product_id, business_account_id,"
                        + " availability, price, currency, updated_by_user_id) VALUES (?, ?, ?, ?, 'AVAILABLE', 1.00, 'BRL', ?)",
                UUID.randomUUID(), placeId, productOfB, accountB, adminB.id());
        assertEquals(2, offerCount(placeId));

        transferOwnership(placeId, accountB);

        assertEquals(0, offerCount(placeId), "nenhuma oferta presente na troca sobrevive, nem a da conta que entra");
        assertEquals(0, list(adminB, placeId, 0, 20).get("totalElements").asLong());
    }

    @Test
    @DisplayName("T2: desvincular e reivindicar de novo pelo fluxo oficial não recupera as ofertas da mesma conta")
    void unlinkAndOfficialReclaimDoNotRestoreOffers() throws Exception {
        TestUser admin = registerUser("t2_reclaim");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = createAccount(admin);
        claimViaOfficialFlow(admin, accountId, placeId);
        UUID productId = product("ACTIVE");
        putOffer(admin, placeId, productId, body(accountId, "AVAILABLE", "1.99", null)).andExpect(status().isOk());

        transferOwnership(placeId, null);
        assertEquals(0, offerCount(placeId));

        claimViaOfficialFlow(admin, accountId, placeId);
        assertEquals(accountId, jdbcTemplate.queryForObject(
                "SELECT claimed_by_business_id FROM places WHERE id = ?", UUID.class, placeId));
        assertEquals(0, list(admin, placeId, 0, 20).get("totalElements").asLong());
        assertEquals(0, offerCount(placeId));
    }

    @Test
    @DisplayName("T2 é atômica: troca de titular desfeita por rollback devolve titular e ofertas juntos")
    void ownerChangeRollbackRestoresOffersAtomically() throws Exception {
        TestUser admin = registerUser("t2_atomic");
        UUID placeId = createPlace("ACTIVE");
        UUID accountA = approvedOwner(admin, placeId);
        UUID accountB = createAccount(registerUser("t2_atomic_b"));
        putOffer(admin, placeId, product("ACTIVE"), body(accountA, "AVAILABLE", "3.00", null)).andExpect(status().isOk());
        putOffer(admin, placeId, product("ACTIVE"), body(accountA, "AVAILABLE", null, null)).andExpect(status().isOk());
        List<Map<String, Object>> before = jdbcTemplate.queryForList(
                "SELECT * FROM place_product_offers WHERE place_id = ? ORDER BY id", placeId);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", accountB, placeId);
            assertEquals(0, offerCount(placeId), "dentro da transação a troca já apagou as ofertas");
            throw new IllegalStateException("falha depois da troca de titular");
        }));
        assertEquals("falha depois da troca de titular", failure.getMessage());

        assertEquals(accountA, jdbcTemplate.queryForObject(
                "SELECT claimed_by_business_id FROM places WHERE id = ?", UUID.class, placeId));
        assertEquals(before, jdbcTemplate.queryForList(
                "SELECT * FROM place_product_offers WHERE place_id = ? ORDER BY id", placeId));
    }

    @Test
    @DisplayName("Falha depois da gravação desfaz a oferta inteira (transação única do caso de uso)")
    void failedUpdateRollsBackEntirely() throws Exception {
        TestUser admin = registerUser("offer_rollback");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = approvedOwner(admin, placeId);
        UUID existing = product("ACTIVE");
        UUID fresh = product("ACTIVE");
        putOffer(admin, placeId, existing, body(accountId, "AVAILABLE", "11.00", null)).andExpect(status().isOk());
        Map<String, Object> before = offerRow(placeId, existing);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            upsertOffer.execute(new UpsertPlaceProductOfferCommand(admin.id(), placeId, existing, accountId,
                    "OUT_OF_STOCK", new BigDecimal("1.00"), "BRL"));
            upsertOffer.execute(new UpsertPlaceProductOfferCommand(admin.id(), placeId, fresh, accountId,
                    "AVAILABLE", null, null));
            assertEquals(2, offerCount(placeId));
            throw new IllegalStateException("falha depois de gravar");
        }));
        assertEquals("falha depois de gravar", failure.getMessage());

        assertEquals(before, offerRow(placeId, existing));
        assertEquals(List.of(), offerRows(placeId, fresh));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Concorrência (padrão A4)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Gravações concorrentes no mesmo local esperam a trava e terminam com uma oferta inteira")
    void concurrentWritesSerializeOnPlaceLock() throws Exception {
        TestUser admin = registerUser("offer_conc");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = approvedOwner(admin, placeId);
        UUID productId = product("ACTIVE");
        putOffer(admin, placeId, productId, body(accountId, "AVAILABLE", "1.00", null)).andExpect(status().isOk());
        Map<String, Object> before = offerRow(placeId, productId);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            // FOR SHARE conflita com o FOR NO KEY UPDATE do caso de uso e não com o FOR KEY SHARE das FKs.
            Future<?> holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                holderPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                jdbcTemplate.queryForObject("SELECT id FROM places WHERE id = ? FOR SHARE", UUID.class, placeId);
                locked.countDown();
                awaitRelease(release);
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));

            Future<Integer> first = executor.submit(() ->
                    putStatus(admin, placeId, productId, body(accountId, "OUT_OF_STOCK", "2.00", null)));
            int firstPid = waitForPlaceLockWaiter(Set.of(holderPid.get()), Set.of());
            Future<Integer> second = executor.submit(() ->
                    putStatus(admin, placeId, productId, body(accountId, "AVAILABLE", null, null)));
            waitForPlaceLockWaiter(Set.of(holderPid.get(), firstPid), Set.of(firstPid));
            assertEquals(before, offerRow(placeId, productId), "nada muda enquanto as gravações esperam");

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertEquals(200, first.get(10, TimeUnit.SECONDS));
            assertEquals(200, second.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        Map<String, Object> after = offerRow(placeId, productId);
        String terms = after.get("availability") + "|" + after.get("price");
        assertTrue(Set.of("OUT_OF_STOCK|2.00", "AVAILABLE|null").contains(terms), "termos misturados: " + terms);
        assertEquals(1, offerCount(placeId));
    }

    @Test
    @DisplayName("Troca de titular concorrente: o PUT espera a trava e, após o commit da troca, recebe 403 sem gravar")
    void writeWaitsForConcurrentOwnerChangeAndIsRefused() throws Exception {
        TestUser adminA = registerUser("t2_conc_a");
        UUID placeId = createPlace("ACTIVE");
        UUID accountA = approvedOwner(adminA, placeId);
        UUID accountB = createAccount(registerUser("t2_conc_b"));
        UUID productId = product("ACTIVE");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                holderPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", accountB, placeId);
                changed.countDown();
                awaitRelease(release);
            }));
            assertTrue(changed.await(5, TimeUnit.SECONDS));

            Future<Integer> write = executor.submit(() ->
                    putStatus(adminA, placeId, productId, body(accountA, "AVAILABLE", "5.00", null)));
            waitForPlaceLockWaiter(Set.of(holderPid.get()), Set.of());

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertEquals(403, write.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertEquals(0, offerCount(placeId), "nenhuma oferta de A sobrevive à troca de titular");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Banco: paridade e restrições da V28
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CHECK de disponibilidade espelha o enum; banco recusa preço negativo, outra moeda e duplicidade")
    void databaseConstraintsMatchDomain() throws Exception {
        String definition = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_place_product_offer_availability'",
                String.class);
        Matcher matcher = Pattern.compile("'([A-Z_]+)'").matcher(definition);
        Set<String> codes = new HashSet<>();
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
        assertEquals(Arrays.stream(OfferAvailability.values()).map(Enum::name).collect(Collectors.toSet()), codes);

        TestUser admin = registerUser("offer_db");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = approvedOwner(admin, placeId);
        UUID productId = product("ACTIVE");
        putOffer(admin, placeId, productId, body(accountId, "AVAILABLE", "1.00", null)).andExpect(status().isOk());

        String insert = "INSERT INTO place_product_offers (id, place_id, product_id, business_account_id, availability,"
                + " price, currency, updated_by_user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        assertViolates("chk_place_product_offer_price", () -> jdbcTemplate.update(insert, UUID.randomUUID(),
                placeId, product("ACTIVE"), accountId, "AVAILABLE", new BigDecimal("-1.00"), "BRL", admin.id()));
        assertViolates("chk_place_product_offer_currency", () -> jdbcTemplate.update(insert, UUID.randomUUID(),
                placeId, product("ACTIVE"), accountId, "AVAILABLE", null, "USD", admin.id()));
        assertViolates("chk_place_product_offer_availability", () -> jdbcTemplate.update(insert, UUID.randomUUID(),
                placeId, product("ACTIVE"), accountId, "RESERVED", null, "BRL", admin.id()));
        assertViolates("uq_place_product_offer", () -> jdbcTemplate.update(insert, UUID.randomUUID(),
                placeId, productId, accountId, "AVAILABLE", null, "BRL", admin.id()));
        assertEquals(1, offerCount(placeId));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Espera uma sessão, fora de {@code excluded}, parada em lock na leitura travada de places e bloqueada por alguma
     * de {@code blockers}; devolve o PID dela.
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
                for (Object blocker : sqlArray(row.get("blockers"))) {
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

    /** Cada linha inválida deve ser recusada pela constraint específica, não por outra violação qualquer. */
    private static void assertViolates(String constraint, Executable insert) {
        DataIntegrityViolationException violation = assertThrows(DataIntegrityViolationException.class, insert);
        String cause = String.valueOf(violation.getMostSpecificCause().getMessage());
        assertTrue(cause.contains(constraint), "esperada violação de " + constraint + ", recebido: " + cause);
    }

    private static Object[] sqlArray(Object value) {
        try {
            return (Object[]) ((java.sql.Array) value).getArray();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
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

    private void claimViaOfficialFlow(TestUser owner, UUID accountId, UUID placeId) throws Exception {
        UUID moderator = registerUser("t2_moderator").id();
        PrivilegedTestDatabase.jdbc().update("UPDATE users SET role = 'MODERATOR' WHERE id = ?", moderator);
        PlaceClaimView claim = requestPlaceClaim.execute(owner.id(), accountId, placeId, EVIDENCE);
        decidePlaceClaim.execute(moderator, claim.id(), PlaceClaimDecision.APPROVE, REASON, Instant.now());
    }

    private void transferOwnership(UUID placeId, UUID newAccountId) {
        assertEquals(1, jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?",
                newAccountId, placeId));
    }

    private void expectCode(ResultActions actions, int status, String code) throws Exception {
        actions.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    private static String body(UUID accountId, String availability, String priceLiteral, String currencyLiteral) {
        StringBuilder json = new StringBuilder("{\"businessAccountId\":\"").append(accountId).append('"');
        if (availability != null) {
            json.append(",\"availability\":\"").append(availability).append('"');
        }
        if (priceLiteral != null) {
            json.append(",\"price\":").append(priceLiteral);
        }
        if (currencyLiteral != null) {
            json.append(",\"currency\":").append(currencyLiteral);
        }
        return json.append('}').toString();
    }

    private ResultActions putOffer(TestUser user, UUID placeId, UUID productId, String body) throws Exception {
        return mockMvc.perform(withToken(put("/api/v1/places/{placeId}/offers/{productId}", placeId, productId)
                .contentType(MediaType.APPLICATION_JSON).content(body), user));
    }

    private int putStatus(TestUser user, UUID placeId, UUID productId, String body) {
        try {
            return putOffer(user, placeId, productId, body).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            return 500;
        }
    }

    private ResultActions deleteOffer(TestUser user, UUID placeId, UUID productId, UUID accountId) throws Exception {
        return mockMvc.perform(withToken(delete("/api/v1/places/{placeId}/offers/{productId}", placeId, productId)
                .param("businessAccountId", accountId.toString()), user));
    }

    private JsonNode list(TestUser user, UUID placeId, int page, int size) throws Exception {
        return read(mockMvc.perform(withToken(get("/api/v1/places/{placeId}/offers", placeId)
                        .param("page", String.valueOf(page)).param("size", String.valueOf(size)), user))
                .andExpect(status().isOk())
                .andReturn());
    }

    private static List<String> productIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("content").forEach(node -> ids.add(node.get("product").get("id").asText()));
        return ids;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private Map<String, Object> offerRow(UUID placeId, UUID productId) {
        return jdbcTemplate.queryForMap("SELECT id, business_account_id, availability, price, currency,"
                + " updated_by_user_id, created_at, updated_at FROM place_product_offers"
                + " WHERE place_id = ? AND product_id = ?", placeId, productId);
    }

    private List<Map<String, Object>> offerRows(UUID placeId, UUID productId) {
        return jdbcTemplate.queryForList("SELECT id FROM place_product_offers WHERE place_id = ? AND product_id = ?",
                placeId, productId);
    }

    private int offerCount(UUID placeId) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM place_product_offers WHERE place_id = ?",
                Integer.class, placeId);
        return count == null ? 0 : count;
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

    private UUID createPlace(String status) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place saved = placeRepository.save(new Place(
                null, "Local Ofertas " + suffix, "ofertas-" + UUID.randomUUID(),
                "MERCADO", "Descrição", "Rua das Ofertas, 100", "Curitiba", "PR", "BR",
                -25.43, -49.27, 50, "USER", false, null, status));
        return saved.getId();
    }

    private UUID product(String status) {
        return product("Produto " + UUID.randomUUID().toString().substring(0, 8), status);
    }

    private UUID product(String name, String status) {
        return productRepository.save(new Product(null, name, "Marca", "Modelo", "Descrição", "BEBIDA", null, status))
                .getId();
    }

    private UUID createAccount(TestUser user) throws Exception {
        MvcResult result = mockMvc.perform(withToken(post("/api/v1/business-accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "corporateName", "Empresa " + user.id().toString().substring(0, 8),
                                "taxId", randomTaxId()))), user))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").asText());
    }

    private UUID approvedOwner(TestUser user, UUID placeId) throws Exception {
        UUID accountId = createAccount(user);
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'APPROVED' WHERE id = ?", accountId);
        transferOwnership(placeId, accountId);
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
