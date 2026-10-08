package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProductRepository;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Product;
import com.rewit.presentation.dto.auth.RegisterRequest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Descoberta de produtos (C5.3) pela API real com PostgreSQL: detalhe, identificadores públicos, lookup por código e
 * produtos de um local. Só place/product ACTIVE; indisponível responde o 404 de inexistente; nada da presença (nem
 * quem a relatou) é exposto.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Descoberta de produtos (C5.3): detalhe, identificadores, lookup por código e produtos do local")
class ProductDiscoveryIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<UUID> createdTargets = new ArrayList<>();
    private String suffix;
    private TestUser viewer;

    private record TestUser(UUID id, String accessToken) {}

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        viewer = register("produto_leitor");
    }

    @AfterEach
    void cleanUp() {
        // Place e product são especializações de rateable_targets (ON DELETE CASCADE: identificadores e presenças)
        for (UUID id : createdTargets) {
            jdbcTemplate.update("DELETE FROM rateable_targets WHERE id = ?", id);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Product Detail
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Detalhe: ACTIVE 200 com o contrato atual; INACTIVE, DISCONTINUED e inexistente o mesmo 404")
    void productDetail() throws Exception {
        UUID active = product("Detalhe ativo", "ACTIVE");
        JsonNode body = read(get("/api/v1/products/{id}", active), 200);
        assertEquals(List.of("brand", "category", "description", "id", "imageUrl", "model", "name", "status"),
                fieldNames(body));
        assertEquals("ACTIVE", body.get("status").asText());

        for (String status : List.of("INACTIVE", "DISCONTINUED")) {
            UUID hidden = product("Detalhe " + status, status);
            assertSameNotFound("/api/v1/products/{id}", hidden, "PRODUCT_NOT_FOUND",
                    List.of("Detalhe " + status, status));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Identificadores
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Identificadores: só tipos públicos, ordenados, só tipo e valor; vazio; produto indisponível 404")
    void productIdentifiers() throws Exception {
        UUID product = product("Com codigos", "ACTIVE");
        String ean = "789" + digits(10);
        String upc = "0" + digits(11);
        String isbn = "978-" + digits(9) + "X";
        identifier(product, "UPC", upc);
        identifier(product, "EAN", ean);
        identifier(product, "ISBN", isbn);
        identifier(product, "SKU_INTERNO", "interno-" + suffix);

        JsonNode body = read(get("/api/v1/products/{id}/identifiers", product), 200);
        assertEquals(List.of("identifiers"), fieldNames(body));
        JsonNode identifiers = body.get("identifiers");
        assertEquals(3, identifiers.size(), body.toString());
        assertEquals(List.of("EAN:" + ean, "ISBN:" + isbn, "UPC:" + upc), List.of(
                pair(identifiers.get(0)), pair(identifiers.get(1)), pair(identifiers.get(2))));
        assertEquals(List.of("identifierType", "identifierValue"), fieldNames(identifiers.get(0)));
        String raw = body.toString();
        assertFalse(raw.contains("interno-" + suffix), "tipo fora da lista pública não aparece");
        assertFalse(raw.contains(product.toString()), "sem id de produto nem de linha");

        UUID empty = product("Sem codigos", "ACTIVE");
        assertEquals(0, read(get("/api/v1/products/{id}/identifiers", empty), 200).get("identifiers").size());

        UUID discontinued = product("Codigos descontinuado", "DISCONTINUED");
        String hiddenEan = "790" + digits(10);
        identifier(discontinued, "EAN", hiddenEan);
        assertSameNotFound("/api/v1/products/{id}/identifiers", discontinued, "PRODUCT_NOT_FOUND", List.of(hiddenEan));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Lookup por código
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Lookup: encontra por tipo e código, normaliza tipo e espaços, distingue tipos; indisponível igual a inexistente")
    void lookupByIdentifier() throws Exception {
        UUID product = product("Lookup ativo", "ACTIVE");
        UUID other = product("Lookup outro tipo", "ACTIVE");
        String code = "789" + digits(10);
        identifier(product, "EAN", code);
        identifier(other, "GTIN", code); // mesmo valor, outro tipo: (tipo, valor) é a chave única

        JsonNode found = read(get("/api/v1/products/identifiers/{type}/{value}", "EAN", code), 200);
        assertEquals(product.toString(), found.get("id").asText());
        assertEquals(List.of("brand", "category", "description", "id", "imageUrl", "model", "name", "status"),
                fieldNames(found));
        assertEquals(other.toString(),
                read(get("/api/v1/products/identifiers/{type}/{value}", "GTIN", code), 200).get("id").asText());
        assertEquals(product.toString(),
                read(get("/api/v1/products/identifiers/{type}/{value}", "ean", " " + code + " "), 200).get("id").asText(),
                "tipo em minúsculas e espaços nas pontas são normalizados como na gravação");

        UUID inactive = product("Lookup inativo", "INACTIVE");
        UUID discontinued = product("Lookup descontinuado", "DISCONTINUED");
        String inactiveCode = "791" + digits(10);
        String discontinuedCode = "792" + digits(10);
        identifier(inactive, "EAN", inactiveCode);
        identifier(discontinued, "EAN", discontinuedCode);

        JsonNode missing = read(get("/api/v1/products/identifiers/{type}/{value}", "EAN", "793" + digits(10)), 404);
        assertEquals("PRODUCT_NOT_FOUND", missing.get("code").asText());
        for (String hiddenCode : List.of(inactiveCode, discontinuedCode)) {
            MvcResult result = perform(get("/api/v1/products/identifiers/{type}/{value}", "EAN", hiddenCode), 404);
            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(body);
            assertSameProblem(missing, node, body);
            for (String value : List.of(inactive.toString(), discontinued.toString(), "Lookup", "INACTIVE", "DISCONTINUED")) {
                assertFalse(body.contains(value), body);
            }
        }
    }

    @Test
    @DisplayName("Lookup: tipo fora da lista pública e código malformado ou longo demais são 400")
    void lookupValidation() throws Exception {
        UUID product = product("Lookup sku", "ACTIVE");
        identifier(product, "SKU_INTERNO", "sku" + suffix);

        assertEquals("INVALID_IDENTIFIER_TYPE",
                read(get("/api/v1/products/identifiers/{type}/{value}", "SKU_INTERNO", "sku" + suffix), 400)
                        .get("code").asText(), "tipo interno não é consultável");
        assertEquals("INVALID_IDENTIFIER_VALUE",
                read(get("/api/v1/products/identifiers/{type}/{value}", "EAN", "789_abc"), 400).get("code").asText());
        assertEquals("INVALID_IDENTIFIER_VALUE",
                read(get("/api/v1/products/identifiers/{type}/{value}", "EAN", "   "), 400).get("code").asText());
        assertEquals("INVALID_IDENTIFIER_VALUE",
                read(get("/api/v1/products/identifiers/{type}/{value}", "EAN", "7".repeat(129)), 400).get("code").asText());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Produtos do local
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Produtos do local: só ACTIVE, nome e id, páginas sem perda nem repetição, sem dados da presença")
    void productsInPlace() throws Exception {
        TestUser reporter = register("produto_relator");
        UUID place = place("ACTIVE");
        UUID banana = product("Banana " + suffix, "ACTIVE");
        UUID abacaxi = product("Abacaxi " + suffix, "ACTIVE");
        UUID tie1 = product("Caju " + suffix, "ACTIVE");
        UUID tie2 = product("Caju " + suffix, "ACTIVE");
        UUID inactive = product("Damasco " + suffix, "INACTIVE");
        UUID discontinued = product("Ervilha " + suffix, "DISCONTINUED");
        for (UUID product : List.of(banana, abacaxi, tie1, tie2, inactive, discontinued)) {
            presence(product, place, reporter.id());
        }
        product("Fora do local " + suffix, "ACTIVE");

        List<String> ties = List.of(tie1.toString(), tie2.toString()).stream().sorted().toList();
        List<String> expected = List.of(abacaxi.toString(), banana.toString(), ties.get(0), ties.get(1));

        List<String> paged = new ArrayList<>();
        for (int page = 0; page < 2; page++) {
            JsonNode body = read(get("/api/v1/places/{id}/products", place).param("page", String.valueOf(page))
                    .param("size", "2"), 200);
            assertEquals(4, body.get("totalElements").asLong());
            assertEquals(2, body.get("totalPages").asInt());
            assertEquals(page == 1, body.get("isLast").asBoolean());
            body.get("content").forEach(item -> paged.add(item.get("id").asText()));

            String raw = body.toString();
            assertFalse(raw.contains("reportedByUserId"), raw);
            assertFalse(raw.contains(reporter.id().toString()), "quem relatou a presença nunca aparece");
            assertFalse(raw.contains("verificationStatus") || raw.contains("AVAILABLE"), "nada da presença");
        }
        assertEquals(expected, paged);

        JsonNode all = read(get("/api/v1/places/{id}/products", place), 200);
        assertEquals(20, all.get("pageSize").asInt(), "tamanho padrão");
        assertEquals(List.of("brand", "category", "description", "id", "imageUrl", "model", "name", "status"),
                fieldNames(all.get("content").get(0)), "itens no contrato do detalhe de produto");

        assertEquals("INVALID_PAGE_SIZE", read(get("/api/v1/places/{id}/products", place).param("size", "51"), 400)
                .get("code").asText());
        assertEquals("INVALID_PAGE", read(get("/api/v1/places/{id}/products", place).param("page", "-1"), 400)
                .get("code").asText());

        UUID emptyPlace = place("ACTIVE");
        assertEquals(0, read(get("/api/v1/places/{id}/products", emptyPlace), 200).get("totalElements").asLong());
    }

    @Test
    @DisplayName("Produtos do local: local INACTIVE ou CLOSED responde o 404 de inexistente, sem listar nada")
    void productsInUnavailablePlace() throws Exception {
        for (String status : List.of("INACTIVE", "CLOSED")) {
            UUID place = place(status);
            UUID product = product("Em local " + status + " " + suffix, "ACTIVE");
            presence(product, place, null);
            assertSameNotFound("/api/v1/places/{id}/products", place, "PLACE_NOT_FOUND",
                    List.of(product.toString(), "Em local", status));
        }
    }

    @Test
    @DisplayName("Produtos do local: número de consultas não cresce com a página (sem N+1)")
    void productsInPlaceWithoutNPlusOne() throws Exception {
        UUID small = place("ACTIVE");
        presence(product("Unico " + suffix, "ACTIVE"), small, null);
        UUID large = place("ACTIVE");
        for (int i = 0; i < 12; i++) {
            presence(product("Item " + i + " " + suffix, "ACTIVE"), large, null);
        }

        // Os agendadores ficam desligados nos testes: as estatísticas contam só esta requisição
        long smallStatements = statementsFor(get("/api/v1/places/{id}/products", small).param("size", "50"));
        long largeStatements = statementsFor(get("/api/v1/places/{id}/products", large).param("size", "50"));
        assertEquals(smallStatements, largeStatements, "1 item ou 12 itens: as mesmas consultas");
        assertTrue(largeStatements <= 4, "local, página e contagem (e o que a autenticação já fazia): " + largeStatements);
    }

    @Test
    @DisplayName("Autenticação: as rotas novas exigem token, como o restante do catálogo")
    void newRoutesRequireAuthentication() throws Exception {
        UUID product = product("Sem token", "ACTIVE");
        UUID place = place("ACTIVE");
        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/v1/products/{id}/identifiers", product),
                get("/api/v1/products/identifiers/{type}/{value}", "EAN", "7890000000000"),
                get("/api/v1/places/{id}/products", place))) {
            assertEquals(401, mockMvc.perform(request).andReturn().getResponse().getStatus());
        }
    }

    @Test
    @DisplayName("Índices: lookup por índice de product_identifiers; produtos do local por índice de presença e PK")
    void queriesUseExistingIndexes() {
        // Varredura sequencial desligada: o plano só evita Seq Scan se houver índice compatível com o filtro
        String lookupPlan = explainWithoutSeqScan("""
                SELECT * FROM product_identifiers WHERE identifier_type = 'EAN' AND identifier_value = '7890000000000'""");
        // Qualquer um dos índices existentes atende (o planner escolhe entre a chave única e o índice do valor)
        assertTrue(lookupPlan.contains("uq_product_identifier") || lookupPlan.contains("idx_product_identifiers_value"),
                lookupPlan);
        assertFalse(lookupPlan.contains("Seq Scan"), lookupPlan);

        String inPlacePlan = explainWithoutSeqScan("""
                SELECT pr.* FROM product_presences pp JOIN products pr ON pr.id = pp.product_id
                WHERE pp.place_id = '00000000-0000-0000-0000-000000000000' AND pr.status = 'ACTIVE'
                ORDER BY pr.name ASC, pr.id ASC LIMIT 20 OFFSET 0""");
        assertTrue(inPlacePlan.contains("idx_product_presence_place") || inPlacePlan.contains("uq_product_place"),
                inPlacePlan);
        assertTrue(inPlacePlan.contains("products_pkey"), inPlacePlan);
        assertFalse(inPlacePlan.contains("Seq Scan"), inPlacePlan);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers

    private String explainWithoutSeqScan(String sql) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            return String.join("\n", jdbcTemplate.queryForList("EXPLAIN " + sql, String.class));
        });
    }
    // ---------------------------------------------------------------------------------------------------------

    private long statementsFor(MockHttpServletRequestBuilder request) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            perform(request, 200);
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    /** O 404 do recurso indisponível é o do inexistente: mesmos campos e valores, exceto o caminho pedido. */
    private void assertSameNotFound(String path, UUID hidden, String code, List<String> forbidden) throws Exception {
        MvcResult result = perform(get(path, hidden), 404);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode hiddenNode = objectMapper.readTree(body);
        JsonNode missingNode = read(get(path, UUID.randomUUID()), 404);
        assertEquals(code, hiddenNode.get("code").asText(), body);
        assertSameProblem(missingNode, hiddenNode, body);
        String withoutRequestedPath = body.replace(hidden.toString(), "");
        for (String value : forbidden) {
            assertFalse(withoutRequestedPath.contains(value), path + " vazou '" + value + "': " + body);
        }
    }

    private void assertSameProblem(JsonNode expected, JsonNode actual, String body) {
        assertEquals(fieldNames(expected), fieldNames(actual), body);
        for (String field : fieldNames(actual)) {
            if (!field.equals("instance") && !field.equals("timestamp")) {
                assertEquals(expected.get(field), actual.get(field), "campo " + field + ": " + body);
            }
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names.stream().sorted().toList();
    }

    private static String pair(JsonNode identifier) {
        return identifier.get("identifierType").asText() + ":" + identifier.get("identifierValue").asText();
    }

    private static String digits(int count) {
        StringBuilder builder = new StringBuilder();
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            builder.append(random.nextInt(10));
        }
        return builder.toString();
    }

    private UUID product(String name, String status) {
        UUID id = productRepository.save(new Product(null, name, "Marca", null, null, "BEBIDA", null, status)).getId();
        createdTargets.add(id);
        return id;
    }

    private UUID place(String status) {
        UUID id = placeRepository.save(new Place(null, "Mercado " + status + " " + suffix, "mercado-" + UUID.randomUUID(),
                "MERCADO", "Descrição", "Rua Produto, 1", "Curitiba", "PR", "BR", -25.43, -49.27, 50, "USER", false,
                null, status)).getId();
        createdTargets.add(id);
        return id;
    }

    private void identifier(UUID productId, String type, String value) {
        jdbcTemplate.update("INSERT INTO product_identifiers (product_id, identifier_type, identifier_value) VALUES (?, ?, ?)",
                productId, type, value);
    }

    private void presence(UUID productId, UUID placeId, UUID reporter) {
        jdbcTemplate.update("INSERT INTO product_presences (product_id, place_id, reported_by_user_id) VALUES (?, ?, ?)",
                productId, placeId, reporter);
    }

    private JsonNode read(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        return objectMapper.readTree(perform(request, expectedStatus).getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        if (viewer != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer.accessToken());
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus(),
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return result;
    }

    private TestUser register(String prefix) throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(prefix + "." + id + "@rewit.test",
                                "Senha@" + id, prefix + "_" + id, "Nome " + id))))
                .andReturn();
        assertEquals(201, result.getResponse().getStatus());
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return new TestUser(UUID.fromString(node.get("user").get("id").asText()), node.get("accessToken").asText());
    }
}
