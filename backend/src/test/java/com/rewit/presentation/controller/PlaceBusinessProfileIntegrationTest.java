package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rewit.application.port.PlaceRepository;
import com.rewit.domain.model.Place;
import com.rewit.presentation.dto.auth.RegisterRequest;
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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * Perfil comercial (descrição e contatos) contra o PostgreSQL real: contrato HTTP, leitura pelo PlaceResponse,
 * substituição integral, autorização da Política B, validação, CHECKs da V38 e trava do local.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Integração: perfil comercial e contatos públicos de estabelecimentos")
class PlaceBusinessProfileIntegrationTest {

    private static final String PHONE = "+55 (41) 3333-4444";
    private static final String SITE = "https://www.cafe-exemplo.com.br";
    private static final String INSTAGRAM = "https://www.instagram.com/cafe.exemplo";
    private static final String FACEBOOK = "https://www.facebook.com/cafe.exemplo";
    private static final String DESCRIPTION = "Café colonial com fornada própria.\nAberto aos domingos.";

    /** Tudo o que o perfil comercial não pode alterar. */
    private static final String IMMUTABLE_COLUMNS = "SELECT name, slug, category, address_text, street_number, "
            + "neighborhood, city, state, country, ST_AsText(coordinates) AS coordinates, validation_radius_meters, "
            + "origin, is_verified, claimed_by_business_id, created_by_user_id, status, created_at FROM places WHERE id = ?";
    private static final String PROFILE_COLUMNS = "SELECT description, phone, website_url, instagram_url, facebook_url "
            + "FROM places WHERE id = ?";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record TestUser(UUID id, String token) {}

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Escrita, leitura e substituição integral
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Representante aprovado grava descrição e contatos; resposta e GET do local trazem os novos valores")
    void approvedRepresentativeUpdatesAndReadsBack() throws Exception {
        TestUser owner = registerUser("profile_ok");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = approvedOwner(owner, placeId);
        Map<String, Object> immutableBefore = jdbcTemplate.queryForMap(IMMUTABLE_COLUMNS, placeId);
        Object updatedAtBefore = updatedAt(placeId);

        JsonNode response = read(putProfile(owner, placeId, body(accountId, DESCRIPTION, PHONE, SITE, INSTAGRAM, FACEBOOK))
                .andExpect(status().isOk())
                .andReturn());
        assertProfile(response, DESCRIPTION, PHONE, SITE, INSTAGRAM, FACEBOOK);
        assertEquals(placeId.toString(), response.get("id").asText());

        JsonNode read = read(mockMvc.perform(withToken(get("/api/v1/places/{id}", placeId), owner))
                .andExpect(status().isOk())
                .andReturn());
        assertProfile(read, DESCRIPTION, PHONE, SITE, INSTAGRAM, FACEBOOK);
        assertEquals(response, read, "A resposta do PUT é o mesmo PlaceResponse da leitura do local");

        assertEquals(row(DESCRIPTION, PHONE, SITE, INSTAGRAM, FACEBOOK), jdbcTemplate.queryForMap(PROFILE_COLUMNS, placeId));
        assertEquals(immutableBefore, jdbcTemplate.queryForMap(IMMUTABLE_COLUMNS, placeId),
                "nome, slug, categoria, endereço, coordenadas, titular, status e verificação intactos");
        assertNotEquals(updatedAtBefore, updatedAt(placeId));
    }

    @Test
    @DisplayName("Campo ausente, null ou só com espaços limpa o valor anterior; os demais campos são substituídos")
    void absentNullOrBlankFieldsClearPreviousValues() throws Exception {
        TestUser owner = registerUser("profile_clear");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = approvedOwner(owner, placeId);
        putProfile(owner, placeId, body(accountId, DESCRIPTION, PHONE, SITE, INSTAGRAM, FACEBOOK))
                .andExpect(status().isOk());

        // Só o telefone e o Instagram mudam; descrição em branco, site null e Facebook ausente ficam sem valor
        ObjectNode partial = objectMapper.createObjectNode().put("businessAccountId", accountId.toString())
                .put("description", "   ").put("phone", "(41) 99999-0000").putNull("websiteUrl")
                .put("instagramUrl", "https://instagram.com/novo");
        JsonNode updated = read(putProfile(owner, placeId, partial.toString()).andExpect(status().isOk()).andReturn());
        assertProfile(updated, null, "(41) 99999-0000", null, "https://instagram.com/novo", null);
        assertEquals(row(null, "(41) 99999-0000", null, "https://instagram.com/novo", null),
                jdbcTemplate.queryForMap(PROFILE_COLUMNS, placeId));

        // Só a conta: o perfil inteiro fica vazio
        JsonNode cleared = read(putProfile(owner, placeId, "{\"businessAccountId\":\"" + accountId + "\"}")
                .andExpect(status().isOk()).andReturn());
        assertProfile(cleared, null, null, null, null, null);
        assertEquals(row(null, null, null, null, null), jdbcTemplate.queryForMap(PROFILE_COLUMNS, placeId));
    }

    @Test
    @DisplayName("Valores no limite (descrição 2000, link 500) são aceitos e lidos de volta sem alteração")
    void valuesAtLimitsRoundTrip() throws Exception {
        TestUser owner = registerUser("profile_limit");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = approvedOwner(owner, placeId);
        String description = "d".repeat(2000);
        String prefix = "https://www.exemplo.com/";
        String longUrl = prefix + "a".repeat(500 - prefix.length());

        putProfile(owner, placeId, body(accountId, description, "+123456789012345", longUrl, longUrl, longUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(description))
                .andExpect(jsonPath("$.websiteUrl").value(longUrl));
        assertEquals(row(description, "+123456789012345", longUrl, longUrl, longUrl),
                jdbcTemplate.queryForMap(PROFILE_COLUMNS, placeId));
    }

    @Test
    @DisplayName("Salvar o local por outro fluxo (save do repositório) nunca apaga os contatos gravados")
    void genericPlaceSaveNeverOverwritesContacts() throws Exception {
        TestUser owner = registerUser("profile_save");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = approvedOwner(owner, placeId);
        putProfile(owner, placeId, body(accountId, DESCRIPTION, PHONE, SITE, INSTAGRAM, FACEBOOK))
                .andExpect(status().isOk());

        // Local reconstruído sem contatos (como faz qualquer fluxo que não conhece o perfil)
        Place stored = placeRepository.findById(placeId).orElseThrow();
        placeRepository.save(new Place(stored.getId(), stored.getName(), stored.getSlug(), stored.getCategory(),
                stored.getDescription(), stored.getAddressText(), stored.getStreetNumber(), stored.getNeighborhood(),
                stored.getCity(), stored.getState(), stored.getCountry(), stored.getLatitude(), stored.getLongitude(),
                stored.getValidationRadiusMeters(), stored.getOrigin(), stored.isVerified(),
                stored.getClaimedByBusinessId(), stored.getCreatedByUserId(), stored.getStatus()));

        assertEquals(row(DESCRIPTION, PHONE, SITE, INSTAGRAM, FACEBOOK), jdbcTemplate.queryForMap(PROFILE_COLUMNS, placeId));
        assertEquals(PHONE, placeRepository.findById(placeId).orElseThrow().getContactInfo().phone());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Leituras que usam PlaceResponse
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("PlaceResponse expõe phone, websiteUrl, instagramUrl e facebookUrl (null sem perfil) em GET e POST")
    void placeResponseExposesContactFields() throws Exception {
        TestUser user = registerUser("profile_read");
        UUID placeId = createPlace("ACTIVE");

        JsonNode read = read(mockMvc.perform(withToken(get("/api/v1/places/{id}", placeId), user))
                .andExpect(status().isOk()).andReturn());
        assertProfile(read, "Descrição comunitária", null, null, null, null);
        for (String field : new String[]{"phone", "websiteUrl", "instagramUrl", "facebookUrl"}) {
            assertTrue(read.has(field) && read.get(field).isNull(), field + " presente e null");
        }

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = read(mockMvc.perform(withToken(post("/api/v1/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Local Perfil Novo " + suffix,
                                "category", "CAFE",
                                "addressText", "Rua do Perfil Novo, " + suffix,
                                "city", "Curitiba", "state", "PR",
                                "latitude", -25.43, "longitude", -49.27))), user))
                .andExpect(status().isCreated()).andReturn());
        for (String field : new String[]{"phone", "websiteUrl", "instagramUrl", "facebookUrl"}) {
            assertTrue(created.has(field) && created.get(field).isNull(), field + " presente e null na criação");
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Autorização (Política B)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Sem token responde 401; conta inexistente ou de outro usuário 404; pendente ou rejeitada 409; ator inoperante 401")
    void accountAndActorChecks() throws Exception {
        TestUser owner = registerUser("profile_owner");
        TestUser intruder = registerUser("profile_intruder");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = approvedOwner(owner, placeId);
        putProfile(owner, placeId, body(accountId, DESCRIPTION, PHONE, null, null, null)).andExpect(status().isOk());
        Map<String, Object> before = jdbcTemplate.queryForMap(PROFILE_COLUMNS, placeId);
        String change = body(accountId, "Outra descrição", null, SITE, null, null);

        mockMvc.perform(put("/api/v1/places/{placeId}/business-profile", placeId)
                        .contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        expectCode(putProfile(owner, placeId, body(UUID.randomUUID(), "Outra", null, null, null, null)),
                404, "BUSINESS_ACCOUNT_NOT_FOUND");
        expectCode(putProfile(intruder, placeId, change), 404, "BUSINESS_ACCOUNT_NOT_FOUND");

        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'PENDING' WHERE id = ?", accountId);
        expectCode(putProfile(owner, placeId, change), 409, "BUSINESS_ACCOUNT_NOT_APPROVED");
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'REJECTED' WHERE id = ?", accountId);
        expectCode(putProfile(owner, placeId, change), 409, "BUSINESS_ACCOUNT_NOT_APPROVED");
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'APPROVED' WHERE id = ?", accountId);

        jdbcTemplate.update("UPDATE users SET account_status = 'SUSPENDED', is_active = false WHERE id = ?", owner.id());
        expectCode(putProfile(owner, placeId, change), 401, "ACCOUNT_DISABLED");

        assertEquals(before, jdbcTemplate.queryForMap(PROFILE_COLUMNS, placeId));
    }

    @Test
    @DisplayName("Conta que não representa o local (de outra empresa ou sem titular) responde 403 sem gravar")
    void accountThatDoesNotRepresentPlaceIsForbidden() throws Exception {
        TestUser adminA = registerUser("profile_iso_a");
        TestUser adminB = registerUser("profile_iso_b");
        UUID placeA = createPlace("ACTIVE");
        UUID placeB = createPlace("ACTIVE");
        UUID accountA = approvedOwner(adminA, placeA);
        UUID accountB = approvedOwner(adminB, placeB);
        putProfile(adminB, placeB, body(accountB, DESCRIPTION, PHONE, SITE, null, null)).andExpect(status().isOk());
        Map<String, Object> beforeB = jdbcTemplate.queryForMap(PROFILE_COLUMNS, placeB);
        UUID unclaimed = createPlace("ACTIVE");
        Map<String, Object> beforeUnclaimed = jdbcTemplate.queryForMap(PROFILE_COLUMNS, unclaimed);

        expectCode(putProfile(adminA, placeB, body(accountA, "Invasão", PHONE, null, null, null)),
                403, "BUSINESS_NOT_RESPONSIBLE");
        expectCode(putProfile(adminA, unclaimed, body(accountA, "Sem titular", null, null, null, null)),
                403, "BUSINESS_NOT_RESPONSIBLE");

        assertEquals(beforeB, jdbcTemplate.queryForMap(PROFILE_COLUMNS, placeB));
        assertEquals(beforeUnclaimed, jdbcTemplate.queryForMap(PROFILE_COLUMNS, unclaimed));
    }

    @Test
    @DisplayName("Local inexistente ou inativo responde 404 PLACE_NOT_FOUND, mesmo representado pela conta")
    void missingOrInactivePlaceIsNotFound() throws Exception {
        TestUser owner = registerUser("profile_missing");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = approvedOwner(owner, placeId);

        expectCode(putProfile(owner, UUID.randomUUID(), body(accountId, DESCRIPTION, null, null, null, null)),
                404, "PLACE_NOT_FOUND");

        jdbcTemplate.update("UPDATE places SET status = 'INACTIVE' WHERE id = ?", placeId);
        Map<String, Object> before = jdbcTemplate.queryForMap(PROFILE_COLUMNS, placeId);
        expectCode(putProfile(owner, placeId, body(accountId, DESCRIPTION, PHONE, null, null, null)),
                404, "PLACE_NOT_FOUND");
        assertEquals(before, jdbcTemplate.queryForMap(PROFILE_COLUMNS, placeId));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Validação
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Payload inválido responde 400 RFC 7807 antes de gravar e preserva o perfil existente")
    void invalidPayloadIsRejectedWithoutTouchingStoredProfile() throws Exception {
        TestUser owner = registerUser("profile_invalid");
        UUID placeId = createPlace("ACTIVE");
        UUID accountId = approvedOwner(owner, placeId);
        putProfile(owner, placeId, body(accountId, DESCRIPTION, PHONE, SITE, INSTAGRAM, FACEBOOK))
                .andExpect(status().isOk());
        Map<String, Object> before = jdbcTemplate.queryForMap(PROFILE_COLUMNS, placeId);
        Object updatedAtBefore = updatedAt(placeId);

        Map<String, String> invalid = new LinkedHashMap<>();
        invalid.put(body(accountId, "a".repeat(2001), null, null, null, null), "VALIDATION_ERROR");
        invalid.put(body(accountId, null, "9".repeat(33), null, null, null), "VALIDATION_ERROR");
        invalid.put(body(accountId, null, null, "https://e.com/" + "a".repeat(487), null, null), "VALIDATION_ERROR");
        invalid.put(body(accountId, null, "1234567", null, null, null), "INVALID_PHONE");
        invalid.put(body(accountId, null, "+55 41 3333-4444 ramal 2", null, null, null), "INVALID_PHONE");
        invalid.put(body(accountId, null, null, "javascript:alert(1)", null, null), "INVALID_WEBSITE_URL");
        invalid.put(body(accountId, null, null, "www.exemplo.com", null, null), "INVALID_WEBSITE_URL");
        invalid.put(body(accountId, null, null, null, "data:text/html;base64,PHNjcmlwdD4=", null), "INVALID_INSTAGRAM_URL");
        invalid.put(body(accountId, null, null, null, null, "ftp://facebook.com/exemplo"), "INVALID_FACEBOOK_URL");
        invalid.put(body(accountId, null, null, null, null, "https://facebook.com@exemplo.com"), "INVALID_FACEBOOK_URL");
        invalid.put("{\"description\":\"Sem conta\"}", "VALIDATION_ERROR");
        invalid.put("{\"businessAccountId\":\"" + accountId + "\",\"phone\":", "MALFORMED_REQUEST");

        for (Map.Entry<String, String> entry : invalid.entrySet()) {
            expectCode(putProfile(owner, placeId, entry.getKey()), 400, entry.getValue());
        }
        assertEquals(before, jdbcTemplate.queryForMap(PROFILE_COLUMNS, placeId));
        assertEquals(updatedAtBefore, updatedAt(placeId));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Banco: CHECKs da V38 e trava do local
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CHECKs da V38 recusam escrita direta com valor vazio, telefone fora do formato ou esquema proibido")
    void databaseConstraintsBackTheDomain() {
        UUID placeId = createPlace("ACTIVE");
        String update = "UPDATE places SET %s = ? WHERE id = ?";

        assertViolates("chk_places_phone_format", () -> jdbcTemplate.update(update.formatted("phone"), "", placeId));
        assertViolates("chk_places_phone_format", () -> jdbcTemplate.update(update.formatted("phone"), "telefone", placeId));
        assertViolates("chk_places_website_url_scheme",
                () -> jdbcTemplate.update(update.formatted("website_url"), "javascript:alert(1)", placeId));
        assertViolates("chk_places_instagram_url_scheme",
                () -> jdbcTemplate.update(update.formatted("instagram_url"), "", placeId));
        assertViolates("chk_places_facebook_url_scheme",
                () -> jdbcTemplate.update(update.formatted("facebook_url"), "https://exemplo .com", placeId));
        assertEquals(row("Descrição comunitária", null, null, null, null),
                jdbcTemplate.queryForMap(PROFILE_COLUMNS, placeId));
    }

    @Test
    @DisplayName("Troca de titular concorrente: o PUT espera a trava do local e, após o commit da troca, recebe 403 sem gravar")
    void updateWaitsForConcurrentOwnerChangeAndIsRefused() throws Exception {
        TestUser adminA = registerUser("profile_conc_a");
        UUID placeId = createPlace("ACTIVE");
        UUID accountA = approvedOwner(adminA, placeId);
        UUID accountB = createAccount(registerUser("profile_conc_b"));
        Map<String, Object> before = jdbcTemplate.queryForMap(PROFILE_COLUMNS, placeId);

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
                    putStatus(adminA, placeId, body(accountA, "Descrição de A", PHONE, SITE, null, null)));
            waitForPlaceLockWaiter(holderPid.get());

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertEquals(403, write.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertEquals(before, jdbcTemplate.queryForMap(PROFILE_COLUMNS, placeId), "A conta que saiu não grava nada");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    /** Espera a gravação parada na leitura travada de places, bloqueada pela sessão {@code blocker}. */
    private void waitForPlaceLockWaiter(int blocker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String sql = """
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                  AND wait_event_type = 'Lock'
                  AND query ILIKE '%places%'
                  AND (query ILIKE '%for update%' OR query ILIKE '%for no key update%')
                  AND ? = ANY (pg_blocking_pids(pid))
                """;
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(sql, Integer.class, blocker);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
        fail("a gravação deveria estar esperando a trava de places, bloqueada por " + blocker);
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

    /** Cada escrita inválida deve ser recusada pela constraint específica, não por outra violação qualquer. */
    private static void assertViolates(String constraint, Executable write) {
        DataIntegrityViolationException violation = assertThrows(DataIntegrityViolationException.class, write);
        String cause = String.valueOf(violation.getMostSpecificCause().getMessage());
        assertTrue(cause.contains(constraint), "esperada violação de " + constraint + ", recebido: " + cause);
    }

    private static void assertProfile(JsonNode node, String description, String phone, String websiteUrl,
                                      String instagramUrl, String facebookUrl) {
        assertEquals(description, text(node, "description"));
        assertEquals(phone, text(node, "phone"));
        assertEquals(websiteUrl, text(node, "websiteUrl"));
        assertEquals(instagramUrl, text(node, "instagramUrl"));
        assertEquals(facebookUrl, text(node, "facebookUrl"));
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

    private Object updatedAt(UUID placeId) {
        return jdbcTemplate.queryForObject("SELECT updated_at FROM places WHERE id = ?", Object.class, placeId);
    }

    private void expectCode(ResultActions actions, int status, String code) throws Exception {
        actions.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    private String body(UUID accountId, String description, String phone, String websiteUrl, String instagramUrl,
                        String facebookUrl) {
        ObjectNode node = objectMapper.createObjectNode().put("businessAccountId", accountId.toString());
        putIfPresent(node, "description", description);
        putIfPresent(node, "phone", phone);
        putIfPresent(node, "websiteUrl", websiteUrl);
        putIfPresent(node, "instagramUrl", instagramUrl);
        putIfPresent(node, "facebookUrl", facebookUrl);
        return node.toString();
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private ResultActions putProfile(TestUser user, UUID placeId, String body) throws Exception {
        return mockMvc.perform(withToken(put("/api/v1/places/{placeId}/business-profile", placeId)
                .contentType(MediaType.APPLICATION_JSON).content(body), user));
    }

    private int putStatus(TestUser user, UUID placeId, String body) {
        try {
            return putProfile(user, placeId, body).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            return 500;
        }
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
                null, "Local Perfil " + suffix, "perfil-" + UUID.randomUUID(),
                "CAFE", "Descrição comunitária", "Rua do Perfil, " + suffix, "100", "Centro", "Curitiba", "PR", "BR",
                -25.43, -49.27, 50, "USER", false, null, null, status));
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
        return UUID.fromString(read(result).get("id").asText());
    }

    private UUID approvedOwner(TestUser user, UUID placeId) throws Exception {
        UUID accountId = createAccount(user);
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'APPROVED' WHERE id = ?", accountId);
        assertEquals(1, jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?",
                accountId, placeId));
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
