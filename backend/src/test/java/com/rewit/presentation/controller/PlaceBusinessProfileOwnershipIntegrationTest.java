package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rewit.application.dto.business.BusinessDtos.PlaceClaimView;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.usecase.DecidePlaceClaimUseCase;
import com.rewit.application.usecase.RequestPlaceClaimUseCase;
import com.rewit.config.PrivilegedTestDatabase;
import com.rewit.domain.enums.PlaceClaimDecision;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceBusinessProfile;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.PlaceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contatos do perfil comercial e titularidade contra o PostgreSQL real: a troca efetiva de titular apaga os contatos
 * (V39) sem tocar na descrição, a inelegibilidade temporária só os oculta na leitura pública, e a representação que
 * saiu não grava mais.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Integração: contatos do perfil comercial na mudança de titularidade e na inelegibilidade (V39)")
class PlaceBusinessProfileOwnershipIntegrationTest {

    private static final String PHONE = "+55 (41) 3333-4444";
    private static final String SITE = "https://www.cafe-exemplo.com.br";
    private static final String INSTAGRAM = "https://www.instagram.com/cafe.exemplo";
    private static final String FACEBOOK = "https://www.facebook.com/cafe.exemplo";
    private static final String DESCRIPTION = "Descrição pública do café, mantida na troca de titular.";
    private static final String TRIGGER = "trg_places_owner_change_clears_business_contacts";
    private static final String EVIDENCE = "Sou o proprietário; contrato social e alvará disponíveis para conferência.";
    private static final String REASON = "Documentação conferida e compatível com o local.";
    private static final String PROFILE = "SELECT description, phone, website_url, instagram_url, facebook_url "
            + "FROM places WHERE id = ?";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RequestPlaceClaimUseCase requestPlaceClaim;
    @Autowired private DecidePlaceClaimUseCase decidePlaceClaim;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record TestUser(UUID id, String token) {}

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Troca efetiva de titular e encerramento definitivo da representação
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A -> B apaga os quatro contatos e preserva a descrição; leitura pública não expõe contatos de A")
    void ownerChangeClearsContactsAndKeepsDescription() throws Exception {
        TestUser adminA = registerUser("own_ab_a");
        TestUser visitor = registerUser("own_ab_v");
        UUID placeId = createPlace();
        UUID accountA = approvedOwner(adminA, placeId);
        UUID accountB = approvedAccount(registerUser("own_ab_b"));
        putFullProfile(adminA, placeId, accountA);
        assertPublicContacts(visitor, placeId, PHONE, SITE, INSTAGRAM, FACEBOOK);

        transferOwnership(placeId, accountB);

        assertEquals(row(DESCRIPTION, null, null, null, null), jdbcTemplate.queryForMap(PROFILE, placeId));
        JsonNode read = assertPublicContacts(visitor, placeId, null, null, null, null);
        assertEquals(DESCRIPTION, read.get("description").asText());
    }

    @Test
    @DisplayName("A -> sem titular e exclusão da conta (FK SET NULL) apagam os contatos e preservam a descrição")
    void definitiveEndOfRepresentationClearsContacts() throws Exception {
        TestUser adminA = registerUser("own_end_a");
        UUID unlinked = createPlace();
        UUID accountA = approvedOwner(adminA, unlinked);
        putFullProfile(adminA, unlinked, accountA);
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = NULL WHERE id = ?", unlinked);
        assertEquals(row(DESCRIPTION, null, null, null, null), jdbcTemplate.queryForMap(PROFILE, unlinked));

        TestUser adminC = registerUser("own_end_c");
        UUID deleted = createPlace();
        UUID accountC = approvedOwner(adminC, deleted);
        putFullProfile(adminC, deleted, accountC);
        // Exclusão física da conta: só administrativa (o runtime não tem DELETE em business_accounts)
        assertEquals(1, PrivilegedTestDatabase.jdbc().update("DELETE FROM business_accounts WHERE id = ?", accountC));
        assertEquals(row(DESCRIPTION, null, null, null, null), jdbcTemplate.queryForMap(PROFILE, deleted));
        assertNull(jdbcTemplate.queryForObject("SELECT claimed_by_business_id FROM places WHERE id = ?",
                UUID.class, deleted));
    }

    @Test
    @DisplayName("Reivindicação aprovada pelo fluxo oficial (sem titular -> A) não herda contatos gravados antes")
    void officialClaimDoesNotInheritPreviousContacts() throws Exception {
        TestUser admin = registerUser("own_claim");
        UUID placeId = createPlace();
        // Contatos órfãos gravados diretamente num local sem titular (nenhum fluxo da API grava isso)
        jdbcTemplate.update("UPDATE places SET phone = ?, website_url = ? WHERE id = ?", PHONE, SITE, placeId);
        UUID accountId = createAccount(admin);

        claimViaOfficialFlow(admin, accountId, placeId);

        assertEquals(accountId, jdbcTemplate.queryForObject(
                "SELECT claimed_by_business_id FROM places WHERE id = ?", UUID.class, placeId));
        assertEquals(row(DESCRIPTION, null, null, null, null), jdbcTemplate.queryForMap(PROFILE, placeId));
        // A nova titular grava os próprios contatos normalmente
        putFullProfile(admin, placeId, accountId);
        assertEquals(row(DESCRIPTION, PHONE, SITE, INSTAGRAM, FACEBOOK), jdbcTemplate.queryForMap(PROFILE, placeId));
    }

    @Test
    @DisplayName("Mesmo titular, mudança de status ou de nome não apagam contatos (só troca efetiva de titular)")
    void updatesWithoutOwnerChangeKeepContacts() throws Exception {
        TestUser admin = registerUser("own_same");
        UUID placeId = createPlace();
        UUID accountId = approvedOwner(admin, placeId);
        putFullProfile(admin, placeId, accountId);
        Map<String, Object> stored = row(DESCRIPTION, PHONE, SITE, INSTAGRAM, FACEBOOK);

        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", accountId, placeId);
        jdbcTemplate.update("UPDATE places SET status = 'INACTIVE' WHERE id = ?", placeId);
        jdbcTemplate.update("UPDATE places SET status = 'ACTIVE', name = name || ' (renomeado)' WHERE id = ?", placeId);
        Place reloaded = placeRepository.findById(placeId).orElseThrow();
        placeRepository.save(reloaded);

        assertEquals(stored, jdbcTemplate.queryForMap(PROFILE, placeId));
    }

    @Test
    @DisplayName("Teste negativo: com o trigger da V39 desligado, a troca de titular deixaria os contatos de A no local")
    void withoutV39TriggerContactsWouldLeak() throws Exception {
        TestUser adminA = registerUser("own_neg_a");
        UUID placeId = createPlace();
        UUID accountA = approvedOwner(adminA, placeId);
        UUID accountB = approvedAccount(registerUser("own_neg_b"));
        putFullProfile(adminA, placeId, accountA);

        // Transação da role de migração (dona da tabela), sempre desfeita: o trigger volta habilitado
        String leakedPhone = PrivilegedTestDatabase.jdbc().execute((ConnectionCallback<String>) conn -> {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement();
                 PreparedStatement update = conn.prepareStatement(
                         "UPDATE places SET claimed_by_business_id = ? WHERE id = ?");
                 PreparedStatement select = conn.prepareStatement("SELECT phone FROM places WHERE id = ?")) {
                st.execute("ALTER TABLE places DISABLE TRIGGER " + TRIGGER);
                update.setObject(1, accountB);
                update.setObject(2, placeId);
                update.executeUpdate();
                select.setObject(1, placeId);
                try (ResultSet rs = select.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            } finally {
                conn.rollback();
                conn.setAutoCommit(autoCommit);
            }
        });
        assertEquals(PHONE, leakedPhone, "sem a V39 o telefone de A sobreviveria à troca para B");

        assertEquals("O", jdbcTemplate.queryForObject(
                "SELECT tgenabled::text FROM pg_trigger WHERE tgname = ?", String.class, TRIGGER));
        assertEquals(accountA, jdbcTemplate.queryForObject(
                "SELECT claimed_by_business_id FROM places WHERE id = ?", UUID.class, placeId));
        transferOwnership(placeId, accountB);
        assertEquals(row(DESCRIPTION, null, null, null, null), jdbcTemplate.queryForMap(PROFILE, placeId));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Atomicidade
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Rollback: troca de titular desfeita devolve titular e contatos juntos; dentro da transação nunca coexistem")
    void rollbackNeverLeavesNewOwnerWithOldContacts() throws Exception {
        TestUser adminA = registerUser("own_rb_a");
        UUID placeId = createPlace();
        UUID accountA = approvedOwner(adminA, placeId);
        UUID accountB = approvedAccount(registerUser("own_rb_b"));
        putFullProfile(adminA, placeId, accountA);
        Map<String, Object> stored = row(DESCRIPTION, PHONE, SITE, INSTAGRAM, FACEBOOK);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Map<String, Object> insideTransaction = new LinkedHashMap<>();
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", accountB, placeId);
            insideTransaction.putAll(jdbcTemplate.queryForMap(
                    "SELECT claimed_by_business_id, phone, website_url, instagram_url, facebook_url, description "
                            + "FROM places WHERE id = ?", placeId));
            throw new IllegalStateException("falha depois da troca de titular");
        }));
        assertEquals(accountB, insideTransaction.get("claimed_by_business_id"));
        assertNull(insideTransaction.get("phone"), "no mesmo comando da troca os contatos já saíram");
        assertEquals(DESCRIPTION, insideTransaction.get("description"));

        assertEquals(accountA, jdbcTemplate.queryForObject(
                "SELECT claimed_by_business_id FROM places WHERE id = ?", UUID.class, placeId));
        assertEquals(stored, jdbcTemplate.queryForMap(PROFILE, placeId));

        // Comando que troca o titular e viola outra regra falha inteiro: nada muda
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "UPDATE places SET claimed_by_business_id = ?, status = 'DESCONHECIDO' WHERE id = ?", accountB, placeId));
        assertEquals(accountA, jdbcTemplate.queryForObject(
                "SELECT claimed_by_business_id FROM places WHERE id = ?", UUID.class, placeId));
        assertEquals(stored, jdbcTemplate.queryForMap(PROFILE, placeId));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Representação anterior
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A representação anterior não grava o perfil depois de perder a titularidade (403); a nova sim")
    void previousRepresentativeCannotUpdateAfterLosingOwnership() throws Exception {
        TestUser adminA = registerUser("own_prev_a");
        TestUser adminB = registerUser("own_prev_b");
        UUID placeId = createPlace();
        UUID accountA = approvedOwner(adminA, placeId);
        UUID accountB = approvedAccount(adminB);
        putFullProfile(adminA, placeId, accountA);

        transferOwnership(placeId, accountB);
        expectCode(putProfile(adminA, placeId, body(accountA, "Descrição de A", PHONE, SITE, null, null)),
                403, "BUSINESS_NOT_RESPONSIBLE");
        assertEquals(row(DESCRIPTION, null, null, null, null), jdbcTemplate.queryForMap(PROFILE, placeId));

        // Controle negativo: a gravação em si não tem guarda de titular; sem a verificação de representação do caso
        // de uso, os dados de A seriam gravados no local de B (transação desfeita, nada persiste)
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        String wouldBeWritten = tx.execute(status -> {
            status.setRollbackOnly();
            return placeRepository.updateBusinessProfile(placeId,
                    PlaceBusinessProfile.of("Descrição de A", PHONE, SITE, null, null))
                    .orElseThrow().getContactInfo().phone();
        });
        assertEquals(PHONE, wouldBeWritten);
        assertEquals(row(DESCRIPTION, null, null, null, null), jdbcTemplate.queryForMap(PROFILE, placeId));

        putProfile(adminB, placeId, body(accountB, DESCRIPTION, "(41) 98888-7777", null, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("(41) 98888-7777"));

        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = NULL WHERE id = ?", placeId);
        expectCode(putProfile(adminB, placeId, body(accountB, DESCRIPTION, PHONE, null, null, null)),
                403, "BUSINESS_NOT_RESPONSIBLE");
        assertEquals(row(DESCRIPTION, null, null, null, null), jdbcTemplate.queryForMap(PROFILE, placeId));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Inelegibilidade temporária: oculta na leitura pública, preserva no banco
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Conta pendente ou rejeitada e administrador suspenso ocultam os contatos na leitura pública sem apagá-los; "
            + "a elegibilidade recuperada volta a expô-los")
    void temporaryIneligibilityHidesContactsWithoutErasingThem() throws Exception {
        TestUser admin = registerUser("own_tmp");
        TestUser visitor = registerUser("own_tmp_v");
        UUID placeId = createPlace();
        UUID accountId = approvedOwner(admin, placeId);
        putFullProfile(admin, placeId, accountId);
        Map<String, Object> stored = row(DESCRIPTION, PHONE, SITE, INSTAGRAM, FACEBOOK);

        for (String status : new String[]{"PENDING", "REJECTED"}) {
            jdbcTemplate.update("UPDATE business_accounts SET verification_status = ? WHERE id = ?", status, accountId);
            JsonNode hidden = assertPublicContacts(visitor, placeId, null, null, null, null);
            assertEquals(DESCRIPTION, hidden.get("description").asText(), "a descrição nunca é ocultada");
            assertEquals(stored, jdbcTemplate.queryForMap(PROFILE, placeId), status + ": nada é apagado");
            // Controle negativo: sem a projeção pública, o mesmo local carregaria os contatos gravados
            assertEquals(PHONE, PlaceResponse.representativeView(placeRepository.findById(placeId).orElseThrow(), null)
                    .phone(), "o que oculta é a projeção pública, não a ausência do dado");
        }

        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'APPROVED' WHERE id = ?", accountId);
        assertPublicContacts(visitor, placeId, PHONE, SITE, INSTAGRAM, FACEBOOK);

        jdbcTemplate.update("UPDATE users SET account_status = 'SUSPENDED', is_active = false WHERE id = ?", admin.id());
        assertPublicContacts(visitor, placeId, null, null, null, null);
        assertEquals(stored, jdbcTemplate.queryForMap(PROFILE, placeId));

        jdbcTemplate.update("UPDATE users SET account_status = 'ACTIVE', is_active = true WHERE id = ?", admin.id());
        assertPublicContacts(visitor, placeId, PHONE, SITE, INSTAGRAM, FACEBOOK);
    }

    @Test
    @DisplayName("Leituras públicas por referência externa e adoção seguem a mesma projeção dos contatos")
    void otherPublicReadsUseTheSameProjection() throws Exception {
        TestUser admin = registerUser("own_ext");
        TestUser visitor = registerUser("own_ext_v");
        UUID placeId = createPlace();
        UUID accountId = approvedOwner(admin, placeId);
        putFullProfile(admin, placeId, accountId);
        String externalId = "ext-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO place_external_references (id, place_id, provider, external_id) "
                + "VALUES (?, ?, 'GOOGLE', ?)", UUID.randomUUID(), placeId, externalId);
        JsonNode visible = readByExternalReference(visitor, externalId);
        assertEquals(PHONE, text(visible, "phone"));
        assertEquals(SITE, text(visible, "websiteUrl"));

        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'PENDING' WHERE id = ?", accountId);
        JsonNode hidden = readByExternalReference(visitor, externalId);
        assertNull(text(hidden, "phone"));
        assertNull(text(hidden, "websiteUrl"));
        assertNull(text(hidden, "instagramUrl"));
        assertNull(text(hidden, "facebookUrl"));
        assertEquals(DESCRIPTION, text(hidden, "description"));
    }

    private JsonNode readByExternalReference(TestUser reader, String externalId) throws Exception {
        return read(mockMvc.perform(withToken(get("/api/v1/places/external/{provider}/{externalId}",
                        "GOOGLE", externalId), reader))
                .andExpect(status().isOk()).andReturn());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private JsonNode assertPublicContacts(TestUser reader, UUID placeId, String phone, String websiteUrl,
                                          String instagramUrl, String facebookUrl) throws Exception {
        JsonNode node = read(mockMvc.perform(withToken(get("/api/v1/places/{id}", placeId), reader))
                .andExpect(status().isOk()).andReturn());
        assertEquals(phone, text(node, "phone"));
        assertEquals(websiteUrl, text(node, "websiteUrl"));
        assertEquals(instagramUrl, text(node, "instagramUrl"));
        assertEquals(facebookUrl, text(node, "facebookUrl"));
        return node;
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

    private void putFullProfile(TestUser admin, UUID placeId, UUID accountId) throws Exception {
        putProfile(admin, placeId, body(accountId, DESCRIPTION, PHONE, SITE, INSTAGRAM, FACEBOOK))
                .andExpect(status().isOk());
        assertEquals(row(DESCRIPTION, PHONE, SITE, INSTAGRAM, FACEBOOK), jdbcTemplate.queryForMap(PROFILE, placeId));
    }

    private void transferOwnership(UUID placeId, UUID newAccountId) {
        assertEquals(1, jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?",
                newAccountId, placeId));
    }

    private void claimViaOfficialFlow(TestUser owner, UUID accountId, UUID placeId) throws Exception {
        UUID moderator = registerUser("own_moderator").id();
        PrivilegedTestDatabase.jdbc().update("UPDATE users SET role = 'MODERATOR' WHERE id = ?", moderator);
        PlaceClaimView claim = requestPlaceClaim.execute(owner.id(), accountId, placeId, EVIDENCE);
        decidePlaceClaim.execute(moderator, claim.id(), PlaceClaimDecision.APPROVE, REASON, Instant.now());
    }

    private void expectCode(ResultActions actions, int status, String code) throws Exception {
        actions.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    private String body(UUID accountId, String description, String phone, String websiteUrl, String instagramUrl,
                        String facebookUrl) {
        ObjectNode node = objectMapper.createObjectNode().put("businessAccountId", accountId.toString());
        node.put("description", description);
        node.put("phone", phone);
        node.put("websiteUrl", websiteUrl);
        node.put("instagramUrl", instagramUrl);
        node.put("facebookUrl", facebookUrl);
        return node.toString();
    }

    private ResultActions putProfile(TestUser user, UUID placeId, String body) throws Exception {
        return mockMvc.perform(withToken(put("/api/v1/places/{placeId}/business-profile", placeId)
                .contentType(MediaType.APPLICATION_JSON).content(body), user));
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
                null, "Local Titular " + suffix, "titular-" + UUID.randomUUID(),
                "CAFE", DESCRIPTION, "Rua da Titularidade, " + suffix, "100", "Centro", "Curitiba", "PR", "BR",
                -25.43, -49.27, 50, "USER", false, null, null, "ACTIVE"));
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

    private UUID approvedAccount(TestUser user) throws Exception {
        UUID accountId = createAccount(user);
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'APPROVED' WHERE id = ?", accountId);
        return accountId;
    }

    private UUID approvedOwner(TestUser user, UUID placeId) throws Exception {
        UUID accountId = approvedAccount(user);
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
