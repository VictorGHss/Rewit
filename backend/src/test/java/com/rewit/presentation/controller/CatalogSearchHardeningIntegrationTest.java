package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProductRepository;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Product;
import com.rewit.presentation.dto.auth.LoginRequest;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.AssociateProductPresenceRequest;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Busca global endurecida (C5.1) pela API real com PostgreSQL: acentos, caixa, curingas literais, ordem total,
 * paginação, índices utilizáveis e a privacidade da presença de produto.
 *
 * <p>Isolamento: cada execução usa um token aleatório só de letras nos nomes; as buscas por texto incluem o token e
 * só alcançam os fixtures do teste, removidos ao final. As buscas por curinga isolado comparam com um oráculo SQL
 * independente ({@code strpos}, substring literal) calculado sobre o banco compartilhado.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Busca global endurecida (C5.1): acentos, curingas, ordem, índices e privacidade de presença")
class CatalogSearchHardeningIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<UUID> createdTargets = new ArrayList<>();
    private String token;

    private record TestUser(UUID id, String email, String password, String accessToken) {}

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        Random random = new Random();
        StringBuilder letters = new StringBuilder("zq");
        for (int i = 0; i < 10; i++) {
            letters.append((char) ('a' + random.nextInt(26)));
        }
        token = letters.toString();
    }

    @AfterEach
    void cleanUp() {
        // Place e product são especializações de rateable_targets (ON DELETE CASCADE)
        for (UUID id : createdTargets) {
            jdbcTemplate.update("DELETE FROM rateable_targets WHERE id = ?", id);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Acentos e caixa
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Sem acento encontra acentuado (cafe -> Café, sao -> São, acai -> Açaí), em nome, categoria e cidade")
    void accentInsensitive() throws Exception {
        TestUser user = register("busca_acento");
        UUID cafe = place("Café " + token, "CAFE", "Curitiba");
        UUID sao = place("Padaria " + token, "PADARIA", "São José " + token);
        UUID acai = product("Açaí " + token, "SOBREMESA");
        UUID categoria = place("Loja " + token + " xpto", "Confeitaria Açucarada", "Curitiba");

        assertEquals(List.of(cafe.toString()), ids(search(user, "cafe " + token)));
        assertEquals(List.of(sao.toString()), ids(search(user, "sao jose " + token)));
        JsonNode acaiResult = search(user, "acai " + token);
        assertEquals(List.of(acai.toString()), ids(acaiResult));
        assertEquals("PRODUCT", acaiResult.get("content").get(0).get("targetType").asText());
        assertEquals("Açaí " + token, acaiResult.get("content").get(0).get("name").asText(), "o nome volta original");

        // Acentuado também encontra; caixa não importa; categoria acentuada por texto sem acento
        assertEquals(List.of(cafe.toString()), ids(search(user, "CAFÉ " + token.toUpperCase())));
        assertTrue(ids(search(user, "acucarada")).contains(categoria.toString()));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Curingas
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("%, _ e \\ são literais: isolados não devolvem o catálogo, e casam só o texto que os contém")
    void wildcardsAreLiteral() throws Exception {
        TestUser user = register("busca_curinga");
        UUID percent = place("Promo 100% " + token, "BAR", "Curitiba");
        UUID underscore = place("Bar a_b " + token, "BAR", "Curitiba");
        UUID backslash = place("Bar c\\d " + token, "BAR", "Curitiba");
        UUID combined = place("Combo x%_\\y " + token, "BAR", "Curitiba");
        place("Promo 100x " + token, "BAR", "Curitiba");
        place("Bar axb " + token, "BAR", "Curitiba");

        for (String wildcard : List.of("%", "_", "\\", "%_\\")) {
            JsonNode result = search(user, wildcard);
            assertEquals(literalMatches(wildcard), result.get("totalElements").asLong(),
                    "'" + wildcard + "' casa só o texto que contém o caractere");
        }
        assertTrue(literalMatches("%") < totalActiveCatalog(), "q=% não pode devolver o catálogo inteiro");

        assertEquals(List.of(percent.toString()), ids(search(user, "100% " + token)));
        assertEquals(List.of(underscore.toString()), ids(search(user, "a_b " + token)), "_ não casa 'axb'");
        assertEquals(List.of(backslash.toString()), ids(search(user, "c\\d " + token)));
        assertEquals(List.of(combined.toString()), ids(search(user, "x%_\\y " + token)));
        assertEquals(List.of(), ids(search(user, "100%x " + token)));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Ordem, paginação e limites
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Empate de ranking e nome: desempate por id; páginas sem perda nem duplicação; mesma ordem sempre")
    void deterministicOrderAndPagination() throws Exception {
        TestUser user = register("busca_ordem");
        List<String> tied = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tied.add(place("Empate " + token, "BAR", "Curitiba").toString());
        }
        List<String> expected = tied.stream().sorted().toList();

        List<String> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode result = search(user, "empate " + token, page, 2);
            assertEquals(5, result.get("totalElements").asLong());
            assertEquals(3, result.get("totalPages").asInt());
            paged.addAll(ids(result));
        }
        assertEquals(expected, paged, "ordem total por id no empate, sem perder nem repetir itens entre páginas");
        assertEquals(expected, ids(search(user, "empate " + token, 0, 50)), "mesma consulta, mesma ordem");

        // Relevância continua mandando antes do nome: o nome exato vem primeiro
        UUID exact = place("Relevancia " + token, "BAR", "Curitiba");
        place("Aaa Relevancia " + token + " complemento longo", "BAR", "Curitiba");
        assertEquals(exact.toString(), ids(search(user, "relevancia " + token)).get(0));
    }

    @Test
    @DisplayName("Contrato: busca vazia 400, size 50 aceito e 51 recusado, página negativa 400")
    void contractLimits() throws Exception {
        TestUser user = register("busca_limites");
        perform(get("/api/v1/search").param("q", "   "), user).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEARCH_QUERY"));
        perform(get("/api/v1/search").param("q", "bar").param("size", "50"), user).andExpect(status().isOk())
                .andExpect(jsonPath("$.pageSize").value(50))
                .andExpect(jsonPath("$.pageNumber").value(0)).andExpect(jsonPath("$.isLast").isBoolean());
        perform(get("/api/v1/search").param("q", "bar").param("size", "51"), user).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"));
        perform(get("/api/v1/search").param("q", "bar").param("page", "-1"), user).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Índices
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A expressão da busca é atendida pelos índices trigram normalizados (sem depender de varredura)")
    void searchExpressionUsesIndexes() {
        // Com varredura sequencial desabilitada, um índice compatível com a expressão é obrigatoriamente escolhido; se
        // a expressão não casasse com o índice (como lower(name) contra o índice da coluna crua), o plano seguiria
        // sendo Seq Scan
        String placesPlan = explainWithoutSeqScan("""
                SELECT p.id FROM places p WHERE p.status = 'ACTIVE' AND (
                    rewit_search_normalize(p.name) LIKE rewit_search_normalize('%cafe%') ESCAPE '\\'
                    OR rewit_search_normalize(p.category) LIKE rewit_search_normalize('%cafe%') ESCAPE '\\'
                    OR rewit_search_normalize(p.city) LIKE rewit_search_normalize('%cafe%') ESCAPE '\\')""");
        assertTrue(placesPlan.contains("idx_places_search_name"), placesPlan);
        assertTrue(placesPlan.contains("idx_places_search_category"), placesPlan);
        assertTrue(placesPlan.contains("idx_places_search_city"), placesPlan);
        assertFalse(placesPlan.contains("Seq Scan on places"), placesPlan);

        String productsPlan = explainWithoutSeqScan("""
                SELECT pr.id FROM products pr WHERE pr.status = 'ACTIVE' AND (
                    rewit_search_normalize(pr.name) LIKE rewit_search_normalize('%cafe%') ESCAPE '\\'
                    OR rewit_search_normalize(pr.category) LIKE rewit_search_normalize('%cafe%') ESCAPE '\\')""");
        assertTrue(productsPlan.contains("idx_products_search_name"), productsPlan);
        assertTrue(productsPlan.contains("idx_products_search_category"), productsPlan);
        assertFalse(productsPlan.contains("Seq Scan on products"), productsPlan);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Privacidade da presença de produto
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Presença existente: relator ACTIVE mantido; relator DELETED sai sem UUID na resposta")
    void productPresenceHidesDeletedReporter() throws Exception {
        TestUser activeReporter = register("presenca_ativa");
        TestUser deletedReporter = register("presenca_excluida");
        TestUser actor = register("presenca_ator");
        TestUser admin = admin();

        UUID placeId = place("Mercado " + token, "MERCADO", "Curitiba");
        UUID activeProduct = product("Produto ativo " + token, "BEBIDA");
        UUID deletedProduct = product("Produto excluido " + token, "BEBIDA");

        presence(activeReporter, activeProduct, placeId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportedByUserId").value(activeReporter.id().toString()));
        presence(deletedReporter, deletedProduct, placeId).andExpect(status().isCreated());
        perform(delete("/api/v1/admin/users/{id}", deletedReporter.id()), admin).andExpect(status().isNoContent());

        // Semântica preservada para conta ativa: a presença existente traz quem a relatou primeiro
        presence(actor, activeProduct, placeId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportedByUserId").value(activeReporter.id().toString()));

        MvcResult result = presence(actor, deletedProduct, placeId).andExpect(status().isCreated()).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(objectMapper.readTree(body).get("reportedByUserId").isNull(), body);
        assertFalse(body.contains(deletedReporter.id().toString()), body);
        // O dado gravado não muda (o purge é que o minimiza)
        assertEquals(deletedReporter.id(), jdbcTemplate.queryForObject(
                "SELECT reported_by_user_id FROM product_presences WHERE product_id = ? AND place_id = ?",
                UUID.class, deletedProduct, placeId));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private long literalMatches(String text) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM (
                    SELECT id FROM places WHERE status = 'ACTIVE' AND (
                        strpos(rewit_search_normalize(name), rewit_search_normalize(?)) > 0
                        OR strpos(rewit_search_normalize(category), rewit_search_normalize(?)) > 0
                        OR strpos(rewit_search_normalize(city), rewit_search_normalize(?)) > 0)
                    UNION ALL
                    SELECT id FROM products WHERE status = 'ACTIVE' AND (
                        strpos(rewit_search_normalize(name), rewit_search_normalize(?)) > 0
                        OR strpos(rewit_search_normalize(category), rewit_search_normalize(?)) > 0)
                ) matches
                """, Long.class, text, text, text, text, text);
        return count == null ? 0 : count;
    }

    private long totalActiveCatalog() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT (SELECT count(*) FROM places WHERE status = 'ACTIVE') + (SELECT count(*) FROM products WHERE status = 'ACTIVE')",
                Long.class);
        return count == null ? 0 : count;
    }

    private String explainWithoutSeqScan(String sql) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            return String.join("\n", jdbcTemplate.queryForList("EXPLAIN " + sql, String.class));
        });
    }

    private UUID place(String name, String category, String city) {
        String slug = "busca-" + UUID.randomUUID();
        UUID id = placeRepository.save(new Place(null, name, slug, category, "Descrição", "Rua Busca, 1", city, "PR", "BR",
                -25.43, -49.27, 50, "USER", false, null, "ACTIVE")).getId();
        createdTargets.add(id);
        return id;
    }

    private UUID product(String name, String category) {
        UUID id = productRepository.save(new Product(null, name, "Marca", null, null, category, null, "ACTIVE")).getId();
        createdTargets.add(id);
        return id;
    }

    private org.springframework.test.web.servlet.ResultActions presence(TestUser actor, UUID productId, UUID placeId)
            throws Exception {
        return perform(post("/api/v1/products/{id}/presence", productId).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new AssociateProductPresenceRequest(placeId))), actor);
    }

    private JsonNode search(TestUser user, String query) throws Exception {
        return search(user, query, 0, 50);
    }

    private JsonNode search(TestUser user, String query, int page, int size) throws Exception {
        MvcResult result = perform(get("/api/v1/search").param("q", query).param("page", String.valueOf(page))
                .param("size", String.valueOf(size)), user).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("content").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private org.springframework.test.web.servlet.ResultActions perform(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, TestUser user) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()));
    }

    private TestUser register(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "." + suffix + "@rewit.test";
        String password = "Senha@" + suffix;
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, password, (prefix + "_" + suffix).toLowerCase(), "Nome " + suffix))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return new TestUser(UUID.fromString(node.get("user").get("id").asText()), email, password,
                node.get("accessToken").asText());
    }

    private TestUser admin() throws Exception {
        TestUser user = register("busca_admin");
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.id());
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isOk())
                .andReturn();
        return new TestUser(user.id(), user.email(), user.password(),
                objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("accessToken").asText());
    }
}
