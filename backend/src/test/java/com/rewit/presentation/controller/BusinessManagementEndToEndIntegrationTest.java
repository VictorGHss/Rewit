package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rewit.config.PrivilegedTestDatabase;
import com.rewit.presentation.dto.auth.LoginRequest;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gestão comercial de ponta a ponta (F2.1–F2.5) pelos endpoints reais, contra o PostgreSQL real: local criado pela
 * comunidade, reivindicação solicitada e aprovada pela moderação, horários, comodidades, ofertas e perfil comercial,
 * leitura pública, suspensão e reintegração administrativas do titular, encerramento da titularidade e nova titular
 * pelo fluxo oficial.
 *
 * <p>Fora da API ficam só a atribuição da role ADMIN à equipe e o desvínculo do local: não existem rotas para isso, e
 * ambos são feitos com a credencial administrativa (migração) do banco descartável. O banco também é lido para
 * confirmar o que ficou gravado.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Integração E2E: gestão comercial do estabelecimento reivindicado (F2.1–F2.5)")
class BusinessManagementEndToEndIntegrationTest {

    private static final String EVIDENCE = "Sou o proprietário; contrato social e alvará disponíveis para conferência.";
    private static final String JUSTIFICATION = "Documentação conferida e compatível com o local.";
    private static final String DESCRIPTION = "Café colonial com fornada própria e mesas ao ar livre.";
    private static final String PHONE = "+55 (41) 3333-4444";
    private static final String SITE = "https://www.cafe-exemplo.com.br";
    private static final String INSTAGRAM = "https://www.instagram.com/cafe.exemplo";
    private static final String FACEBOOK = "https://www.facebook.com/cafe.exemplo";
    private static final String CONTACTS = "SELECT description, phone, website_url, instagram_url, facebook_url "
            + "FROM places WHERE id = ?";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record TestUser(UUID id, String email, String password, String token) {}

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("Reivindicação aprovada, gestão dos quatro domínios, leitura pública, suspensão, encerramento da "
            + "titularidade e nova titular pelo fluxo oficial")
    void fullBusinessManagementLifecycle() throws Exception {
        TestUser community = registerUser("e2e_comm");
        TestUser ownerA = registerUser("e2e_owner_a");
        TestUser ownerB = registerUser("e2e_owner_b");
        TestUser visitor = registerUser("e2e_visitor");
        TestUser staff = staffAdmin();

        // 1. Local criado pela comunidade; conta A pendente; reivindicação solicitada e aprovada pela moderação
        UUID placeId = createPlaceViaApi(community);
        UUID accountA = createBusinessAccount(ownerA);
        expectCode(putProfile(ownerA, placeId, profile(accountA, DESCRIPTION, PHONE, SITE, INSTAGRAM, FACEBOOK)),
                409, "BUSINESS_ACCOUNT_NOT_APPROVED");
        approveClaim(staff, requestClaim(ownerA, accountA, placeId));
        assertEquals(accountA, jdbcTemplate.queryForObject(
                "SELECT claimed_by_business_id FROM places WHERE id = ?", UUID.class, placeId));
        assertEquals("APPROVED", jdbcTemplate.queryForObject(
                "SELECT verification_status FROM business_accounts WHERE id = ?", String.class, accountA));

        // 2. Horários: sem configuração, gravação e leitura
        mockMvc.perform(withToken(get("/api/v1/places/{id}/opening-hours", placeId), visitor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false));
        mockMvc.perform(withToken(put("/api/v1/places/{id}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON).content(openingHours(accountA)), ownerA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true));
        JsonNode hours = read(mockMvc.perform(withToken(get("/api/v1/places/{id}/opening-hours", placeId), visitor))
                .andExpect(status().isOk()).andReturn());
        assertTrue(hours.get("configured").asBoolean());
        assertEquals("08:00", dayOf(hours, 1).get("intervals").get(0).get("openTime").asText());
        assertEquals("13:30", dayOf(hours, 1).get("intervals").get(1).get("openTime").asText());
        assertTrue(dayOf(hours, 7).get("closed").asBoolean());

        // 3. Comodidades: sem configuração, seleção, depois declaração explícita de nenhuma
        mockMvc.perform(withToken(get("/api/v1/places/{id}/amenities", placeId), visitor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false));
        putAmenities(ownerA, placeId, accountA, "WIFI", "PET_FRIENDLY").andExpect(status().isOk());
        mockMvc.perform(withToken(get("/api/v1/places/{id}/amenities", placeId), visitor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.amenities.length()").value(2));
        putAmenities(ownerA, placeId, accountA).andExpect(status().isOk());
        mockMvc.perform(withToken(get("/api/v1/places/{id}/amenities", placeId), visitor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.amenities.length()").value(0));
        putAmenities(ownerA, placeId, accountA, "WIFI").andExpect(status().isOk());

        // 4. Ofertas: produto global criado pela comunidade; publicação, atualização, retirada e republicação
        UUID productId = createProductViaApi(community);
        putOffer(ownerA, placeId, productId, offer(accountA, "AVAILABLE", "12.90"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.price").value(12.90));
        JsonNode outOfStock = read(putOffer(ownerA, placeId, productId, offer(accountA, "OUT_OF_STOCK", null))
                .andExpect(status().isOk()).andReturn());
        assertEquals("OUT_OF_STOCK", text(outOfStock, "availability"));
        assertNull(text(outOfStock, "price"), "preço omitido = não divulgado");
        mockMvc.perform(withToken(get("/api/v1/places/{id}/offers", placeId), visitor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].product.id").value(productId.toString()));
        mockMvc.perform(withToken(delete("/api/v1/places/{id}/offers/{productId}", placeId, productId)
                        .param("businessAccountId", accountA.toString()), ownerA))
                .andExpect(status().isNoContent());
        mockMvc.perform(withToken(get("/api/v1/places/{id}/offers", placeId), visitor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(withToken(get("/api/v1/products/{id}", productId), visitor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(productId.toString()));
        putOffer(ownerA, placeId, productId, offer(accountA, "AVAILABLE", "10.00")).andExpect(status().isOk());

        // 5–6. Perfil comercial: gravação e leitura pública com os quatro contatos
        JsonNode updated = read(putProfile(ownerA, placeId, profile(accountA, DESCRIPTION, PHONE, SITE, INSTAGRAM, FACEBOOK))
                .andExpect(status().isOk()).andReturn());
        assertContacts(updated, PHONE, SITE, INSTAGRAM, FACEBOOK);
        JsonNode publicRead = publicPlace(visitor, placeId);
        assertEquals(DESCRIPTION, text(publicRead, "description"));
        assertContacts(publicRead, PHONE, SITE, INSTAGRAM, FACEBOOK);
        Map<String, Object> storedProfile = row(DESCRIPTION, PHONE, SITE, INSTAGRAM, FACEBOOK);

        // 7. Suspensão administrativa do titular: tudo oculto na leitura pública, nada apagado
        mockMvc.perform(withToken(post("/api/v1/admin/users/{id}/suspend", ownerA.id()), staff))
                .andExpect(status().isNoContent());
        JsonNode hidden = publicPlace(visitor, placeId);
        assertContacts(hidden, null, null, null, null);
        assertEquals(DESCRIPTION, text(hidden, "description"), "a descrição nunca é ocultada");
        assertHiddenCommercialData(visitor, placeId);
        assertEquals(storedProfile, jdbcTemplate.queryForMap(CONTACTS, placeId));
        assertEquals(1, count("place_opening_hours_schedules", placeId));
        assertEquals(1, count("place_amenity_configurations", placeId));
        assertEquals(1, count("place_product_offers", placeId));
        expectCode(putProfile(ownerA, placeId, profile(accountA, DESCRIPTION, null, null, null, null)),
                401, "ACCOUNT_DISABLED");

        // Reintegração: a mesma representação volta a ser elegível e os dados preservados voltam a aparecer
        mockMvc.perform(withToken(post("/api/v1/admin/users/{id}/reinstate", ownerA.id()), staff))
                .andExpect(status().isNoContent());
        TestUser ownerAAgain = login(ownerA);
        assertContacts(publicPlace(visitor, placeId), PHONE, SITE, INSTAGRAM, FACEBOOK);
        mockMvc.perform(withToken(get("/api/v1/places/{id}/opening-hours", placeId), visitor))
                .andExpect(jsonPath("$.configured").value(true));
        mockMvc.perform(withToken(get("/api/v1/places/{id}/amenities", placeId), visitor))
                .andExpect(jsonPath("$.configured").value(true));
        mockMvc.perform(withToken(get("/api/v1/places/{id}/offers", placeId), visitor))
                .andExpect(jsonPath("$.totalElements").value(1));

        // 8. Encerramento definitivo da titularidade (operação administrativa, sem API): V28, V29 e V39
        assertEquals(1, PrivilegedTestDatabase.jdbc().update(
                "UPDATE places SET claimed_by_business_id = NULL WHERE id = ?", placeId));
        assertEquals(row(DESCRIPTION, null, null, null, null), jdbcTemplate.queryForMap(CONTACTS, placeId));
        assertEquals(0, count("place_opening_hours_schedules", placeId));
        assertEquals(0, count("place_amenity_configurations", placeId));
        assertEquals(0, count("place_product_offers", placeId));
        JsonNode afterEnd = publicPlace(visitor, placeId);
        assertContacts(afterEnd, null, null, null, null);
        assertEquals(DESCRIPTION, text(afterEnd, "description"));

        // 9. A conta anterior não grava mais em nenhum dos quatro domínios
        expectCode(mockMvc.perform(withToken(put("/api/v1/places/{id}/opening-hours", placeId)
                .contentType(MediaType.APPLICATION_JSON).content(openingHours(accountA)), ownerAAgain)),
                403, "BUSINESS_NOT_RESPONSIBLE");
        expectCode(putAmenities(ownerAAgain, placeId, accountA, "WIFI"), 403, "BUSINESS_NOT_RESPONSIBLE");
        expectCode(putOffer(ownerAAgain, placeId, productId, offer(accountA, "AVAILABLE", "1.00")),
                403, "BUSINESS_NOT_RESPONSIBLE");
        expectCode(mockMvc.perform(withToken(delete("/api/v1/places/{id}/offers/{productId}", placeId, productId)
                .param("businessAccountId", accountA.toString()), ownerAAgain)), 403, "BUSINESS_NOT_RESPONSIBLE");
        expectCode(putProfile(ownerAAgain, placeId, profile(accountA, "Descrição de A", PHONE, null, null, null)),
                403, "BUSINESS_NOT_RESPONSIBLE");
        assertEquals(row(DESCRIPTION, null, null, null, null), jdbcTemplate.queryForMap(CONTACTS, placeId));

        // 10. Nova titular pelo fluxo oficial: não herda contatos nem dados comerciais, e cadastra os próprios
        UUID accountB = createBusinessAccount(ownerB);
        approveClaim(staff, requestClaim(ownerB, accountB, placeId));
        assertContacts(publicPlace(visitor, placeId), null, null, null, null);
        mockMvc.perform(withToken(get("/api/v1/places/{id}/opening-hours", placeId), visitor))
                .andExpect(jsonPath("$.configured").value(false));
        mockMvc.perform(withToken(get("/api/v1/places/{id}/offers", placeId), visitor))
                .andExpect(jsonPath("$.totalElements").value(0));
        putProfile(ownerB, placeId, profile(accountB, DESCRIPTION, "(41) 98888-7777", null, null, null))
                .andExpect(status().isOk());
        assertContacts(publicPlace(visitor, placeId), "(41) 98888-7777", null, null, null);
        expectCode(putProfile(ownerAAgain, placeId, profile(accountA, DESCRIPTION, PHONE, null, null, null)),
                403, "BUSINESS_NOT_RESPONSIBLE");
    }

    @Test
    @DisplayName("Contrato de erros nas rotas de gestão: 401 sem token, 404 para local inexistente, 400 RFC 7807 para "
            + "payload inválido e 403 para conta aprovada que não representa o local")
    void errorContractAcrossManagementRoutes() throws Exception {
        TestUser community = registerUser("e2e_err_comm");
        TestUser owner = registerUser("e2e_err_owner");
        TestUser outsider = registerUser("e2e_err_out");
        TestUser staff = staffAdmin();
        UUID placeId = createPlaceViaApi(community);
        UUID accountId = createBusinessAccount(owner);
        approveClaim(staff, requestClaim(owner, accountId, placeId));
        UUID otherPlace = createPlaceViaApi(community);
        UUID outsiderAccount = createBusinessAccount(outsider);
        approveClaim(staff, requestClaim(outsider, outsiderAccount, otherPlace));
        UUID missing = UUID.randomUUID();
        UUID productId = createProductViaApi(community);

        String[] routes = {"/api/v1/places/{id}/opening-hours", "/api/v1/places/{id}/amenities",
                "/api/v1/places/{id}/business-profile"};
        String[] bodies = {openingHours(accountId), amenities(accountId, "WIFI"),
                profile(accountId, DESCRIPTION, PHONE, null, null, null)};
        for (int i = 0; i < routes.length; i++) {
            mockMvc.perform(put(routes[i], placeId).contentType(MediaType.APPLICATION_JSON).content(bodies[i]))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
            expectCode(mockMvc.perform(withToken(put(routes[i], missing)
                    .contentType(MediaType.APPLICATION_JSON).content(bodies[i]), owner)), 404, "PLACE_NOT_FOUND");
        }
        expectCode(putOffer(owner, missing, productId, offer(accountId, "AVAILABLE", null)), 404, "PLACE_NOT_FOUND");
        expectCode(putOffer(owner, placeId, missing, offer(accountId, "AVAILABLE", null)), 404, "PRODUCT_NOT_FOUND");
        mockMvc.perform(withToken(get("/api/v1/places/{id}", missing), owner))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));

        expectCode(mockMvc.perform(withToken(put("/api/v1/places/{id}/opening-hours", placeId)
                .contentType(MediaType.APPLICATION_JSON).content(
                        "{\"businessAccountId\":\"" + accountId + "\",\"days\":[{\"dayOfWeek\":1,\"closed\":false,"
                                + "\"intervals\":[{\"openTime\":\"18:00\",\"closeTime\":\"08:00\"}]}]}"), owner)),
                400, "INVALID_OPENING_HOURS_INTERVAL");
        expectCode(putAmenities(owner, placeId, accountId, "TELEPORTE"), 400, "INVALID_AMENITY_CODE");
        expectCode(putOffer(owner, placeId, productId, offer(accountId, "SOLD_OUT", null)), 400, "INVALID_OFFER_AVAILABILITY");
        expectCode(putProfile(owner, placeId, profile(accountId, DESCRIPTION, null, "javascript:alert(1)", null, null)),
                400, "INVALID_WEBSITE_URL");

        // Conta aprovada de outro local: 403 nos quatro domínios
        expectCode(mockMvc.perform(withToken(put("/api/v1/places/{id}/opening-hours", placeId)
                .contentType(MediaType.APPLICATION_JSON).content(openingHours(outsiderAccount)), outsider)),
                403, "BUSINESS_NOT_RESPONSIBLE");
        expectCode(putAmenities(outsider, placeId, outsiderAccount, "WIFI"), 403, "BUSINESS_NOT_RESPONSIBLE");
        expectCode(putOffer(outsider, placeId, productId, offer(outsiderAccount, "AVAILABLE", null)),
                403, "BUSINESS_NOT_RESPONSIBLE");
        expectCode(putProfile(outsider, placeId, profile(outsiderAccount, DESCRIPTION, PHONE, null, null, null)),
                403, "BUSINESS_NOT_RESPONSIBLE");

        // Nada foi gravado no local por nenhuma das tentativas recusadas
        assertEquals(0, count("place_opening_hours_schedules", placeId));
        assertEquals(0, count("place_amenity_configurations", placeId));
        assertEquals(0, count("place_product_offers", placeId));
        assertNull(jdbcTemplate.queryForObject("SELECT phone FROM places WHERE id = ?", String.class, placeId));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Fluxos reais
    // ---------------------------------------------------------------------------------------------------------

    /** Equipe com role ADMIN: a role é atribuída no banco (não há rota) e o token novo a carrega. */
    private TestUser staffAdmin() throws Exception {
        TestUser registered = registerUser("e2e_staff");
        PrivilegedTestDatabase.assignRole(registered.id(), "ADMIN");
        return login(registered);
    }

    private UUID createPlaceViaApi(TestUser user) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(withToken(post("/api/v1/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Café E2E " + suffix,
                                "category", "CAFE",
                                "description", DESCRIPTION,
                                "addressText", "Rua da Gestão Comercial, " + suffix,
                                "city", "Curitiba", "state", "PR",
                                "latitude", -25.43, "longitude", -49.27))), user))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode place = read(result);
        assertContacts(place, null, null, null, null);
        return UUID.fromString(place.get("id").asText());
    }

    private UUID createProductViaApi(TestUser user) throws Exception {
        MvcResult result = mockMvc.perform(withToken(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Café Torrado E2E " + UUID.randomUUID().toString().substring(0, 8),
                                "brand", "Marca", "category", "BEBIDA"))), user))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").asText());
    }

    private UUID createBusinessAccount(TestUser user) throws Exception {
        MvcResult result = mockMvc.perform(withToken(post("/api/v1/business-accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "corporateName", "Empresa " + user.id().toString().substring(0, 8),
                                "taxId", randomTaxId()))), user))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verificationStatus").value("PENDING"))
                .andReturn();
        return UUID.fromString(read(result).get("id").asText());
    }

    private UUID requestClaim(TestUser owner, UUID accountId, UUID placeId) throws Exception {
        MvcResult result = mockMvc.perform(withToken(post("/api/v1/business-accounts/{id}/place-claims", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "placeId", placeId.toString(), "evidenceDescription", EVIDENCE))), owner))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        return UUID.fromString(read(result).get("id").asText());
    }

    private void approveClaim(TestUser staff, UUID claimId) throws Exception {
        mockMvc.perform(withToken(post("/api/v1/admin/place-claims/{id}/decision", claimId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decision", "APPROVE", "justification", JUSTIFICATION))), staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    private ResultActions putAmenities(TestUser user, UUID placeId, UUID accountId, String... codes) throws Exception {
        return mockMvc.perform(withToken(put("/api/v1/places/{id}/amenities", placeId)
                .contentType(MediaType.APPLICATION_JSON).content(amenities(accountId, codes)), user));
    }

    private ResultActions putOffer(TestUser user, UUID placeId, UUID productId, String body) throws Exception {
        return mockMvc.perform(withToken(put("/api/v1/places/{id}/offers/{productId}", placeId, productId)
                .contentType(MediaType.APPLICATION_JSON).content(body), user));
    }

    private ResultActions putProfile(TestUser user, UUID placeId, String body) throws Exception {
        return mockMvc.perform(withToken(put("/api/v1/places/{id}/business-profile", placeId)
                .contentType(MediaType.APPLICATION_JSON).content(body), user));
    }

    private JsonNode publicPlace(TestUser reader, UUID placeId) throws Exception {
        return read(mockMvc.perform(withToken(get("/api/v1/places/{id}", placeId), reader))
                .andExpect(status().isOk()).andReturn());
    }

    /** Horários, comodidades e ofertas ocultos na leitura pública enquanto a representação está inelegível. */
    private void assertHiddenCommercialData(TestUser reader, UUID placeId) throws Exception {
        mockMvc.perform(withToken(get("/api/v1/places/{id}/opening-hours", placeId), reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false));
        mockMvc.perform(withToken(get("/api/v1/places/{id}/amenities", placeId), reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false));
        mockMvc.perform(withToken(get("/api/v1/places/{id}/offers", placeId), reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Payloads
    // ---------------------------------------------------------------------------------------------------------

    private String openingHours(UUID accountId) {
        ObjectNode body = objectMapper.createObjectNode().put("businessAccountId", accountId.toString());
        ArrayNode days = body.putArray("days");
        ObjectNode monday = days.addObject().put("dayOfWeek", 1).put("closed", false);
        ArrayNode intervals = monday.putArray("intervals");
        intervals.addObject().put("openTime", "08:00").put("closeTime", "12:00");
        intervals.addObject().put("openTime", "13:30").put("closeTime", "18:00");
        days.addObject().put("dayOfWeek", 7).put("closed", true).putArray("intervals");
        return body.toString();
    }

    private String amenities(UUID accountId, String... codes) {
        ObjectNode body = objectMapper.createObjectNode().put("businessAccountId", accountId.toString());
        ArrayNode list = body.putArray("amenities");
        for (String code : codes) {
            list.add(code);
        }
        return body.toString();
    }

    private String offer(UUID accountId, String availability, String price) {
        ObjectNode body = objectMapper.createObjectNode().put("businessAccountId", accountId.toString())
                .put("availability", availability);
        if (price != null) {
            body.put("price", new java.math.BigDecimal(price));
        }
        return body.toString();
    }

    private String profile(UUID accountId, String description, String phone, String websiteUrl, String instagramUrl,
                           String facebookUrl) {
        return objectMapper.createObjectNode().put("businessAccountId", accountId.toString())
                .put("description", description).put("phone", phone).put("websiteUrl", websiteUrl)
                .put("instagramUrl", instagramUrl).put("facebookUrl", facebookUrl).toString();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Asserções e infraestrutura do teste
    // ---------------------------------------------------------------------------------------------------------

    private static JsonNode dayOf(JsonNode hours, int dayOfWeek) {
        for (JsonNode day : hours.get("days")) {
            if (day.get("dayOfWeek").asInt() == dayOfWeek) {
                return day;
            }
        }
        throw new AssertionError("dia ausente na resposta: " + dayOfWeek);
    }

    private static void assertContacts(JsonNode place, String phone, String websiteUrl, String instagramUrl,
                                       String facebookUrl) {
        assertEquals(phone, text(place, "phone"));
        assertEquals(websiteUrl, text(place, "websiteUrl"));
        assertEquals(instagramUrl, text(place, "instagramUrl"));
        assertEquals(facebookUrl, text(place, "facebookUrl"));
    }

    private static String text(JsonNode node, String field) {
        assertTrue(node.has(field), "campo ausente na resposta: " + field);
        return node.get(field).isNull() ? null : node.get(field).asText();
    }

    private static Map<String, Object> row(String description, String phone, String websiteUrl, String instagramUrl,
                                           String facebookUrl) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("description", description);
        row.put("phone", phone);
        row.put("website_url", websiteUrl);
        row.put("instagram_url", instagramUrl);
        row.put("facebook_url", facebookUrl);
        return row;
    }

    private int count(String table, UUID placeId) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE place_id = ?",
                Integer.class, placeId);
        return count == null ? 0 : count;
    }

    private void expectCode(ResultActions actions, int status, String code) throws Exception {
        actions.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

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
                                email, password, prefix + "_" + suffix, "Nome " + suffix))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = read(result);
        return new TestUser(UUID.fromString(node.get("user").get("id").asText()), email, password,
                node.get("accessToken").asText());
    }

    private TestUser login(TestUser user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isOk())
                .andReturn();
        String token = read(result).get("accessToken").asText();
        assertFalse(token.isBlank());
        return new TestUser(user.id(), user.email(), user.password(), token);
    }

    private static String randomTaxId() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            builder.append(ThreadLocalRandom.current().nextInt(10));
        }
        return builder.toString();
    }
}
