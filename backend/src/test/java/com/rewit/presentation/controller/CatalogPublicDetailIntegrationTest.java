package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProductRepository;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Product;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.CreateReviewRequest;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.CreateReviewTargetRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
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
 * Detalhes públicos do catálogo (C5.2) pela API real com PostgreSQL: place, product, stats e reviews do alvo só
 * expõem alvos {@code ACTIVE}, como a busca. Status indisponível responde o mesmo 404 de um id inexistente, sem
 * revelar nome, slug, status, estatísticas, reviews nem autores.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Detalhes públicos do catálogo (C5.2): só alvos ACTIVE, indisponível igual a inexistente")
class CatalogPublicDetailIntegrationTest {

    private static final String REVIEW_TEXT = "Experiência pública do alvo";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private ProductRepository productRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record TestUser(UUID id, String accessToken) {}

    /** Place e product com uma review PUBLIC que avalia os dois. */
    private record Fixture(UUID placeId, String placeName, String placeSlug, UUID productId, String productName,
                           UUID reviewId, TestUser author) {}

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("ACTIVE: detalhe, stats e reviews do alvo seguem respondendo como antes")
    void activeTargetsStayPublic() throws Exception {
        TestUser viewer = register("detalhe_leitor");
        Fixture f = fixture();

        JsonNode place = read(get("/api/v1/places/{id}", f.placeId()), viewer, 200);
        assertEquals(f.placeId().toString(), place.get("id").asText());
        assertEquals(f.placeName(), place.get("name").asText());
        assertEquals(f.placeSlug(), place.get("slug").asText());
        assertEquals("ACTIVE", place.get("status").asText());

        JsonNode product = read(get("/api/v1/products/{id}", f.productId()), viewer, 200);
        assertEquals(f.productId().toString(), product.get("id").asText());
        assertEquals(f.productName(), product.get("name").asText());
        assertEquals("ACTIVE", product.get("status").asText());

        for (UUID target : List.of(f.placeId(), f.productId())) {
            JsonNode stats = read(get("/api/v1/targets/{id}/stats", target), viewer, 200);
            assertEquals(target.toString(), stats.get("targetId").asText());
            assertTrue(stats.has("averageRating") && stats.has("reviewsCount"));

            JsonNode reviews = read(get("/api/v1/targets/{id}/reviews", target), viewer, 200);
            assertEquals(1, reviews.get("totalElements").asLong());
            assertEquals(f.reviewId().toString(), reviews.get("content").get(0).get("id").asText());
        }
        // Validação de paginação inalterada para ACTIVE
        read(get("/api/v1/targets/{id}/reviews", f.placeId()).param("size", "51"), viewer, 400);
    }

    /** Todos os status não públicos do schema (V3): places INACTIVE/CLOSED, products INACTIVE/DISCONTINUED. */
    @ParameterizedTest(name = "place {0}, product {1}")
    @CsvSource({"INACTIVE, INACTIVE", "CLOSED, DISCONTINUED"})
    @DisplayName("Indisponível: detalhe, stats e reviews respondem o 404 de inexistente, sem vazar nada")
    void unavailableTargetsLookMissing(String placeStatus, String productStatus) throws Exception {
        TestUser viewer = register("detalhe_leitor");
        Fixture f = fixture();
        jdbcTemplate.update("UPDATE places SET status = ? WHERE id = ?", placeStatus, f.placeId());
        jdbcTemplate.update("UPDATE products SET status = ? WHERE id = ?", productStatus, f.productId());

        UUID missing = UUID.randomUUID();
        List<String> forbidden = List.of(f.placeName(), f.placeSlug(), f.productName(), placeStatus, productStatus,
                "ACTIVE", REVIEW_TEXT,
                f.reviewId().toString(), f.author().id().toString(), "averageRating", "reviewsCount", "content");

        assertSameNotFound("/api/v1/places/{id}", f.placeId(), missing, "PLACE_NOT_FOUND", viewer, forbidden);
        assertSameNotFound("/api/v1/products/{id}", f.productId(), missing, "PRODUCT_NOT_FOUND", viewer, forbidden);
        for (UUID target : List.of(f.placeId(), f.productId())) {
            assertSameNotFound("/api/v1/targets/{id}/stats", target, missing, "RATEABLE_TARGET_NOT_FOUND", viewer, forbidden);
            assertSameNotFound("/api/v1/targets/{id}/reviews", target, missing, "RATEABLE_TARGET_NOT_FOUND", viewer, forbidden);
            // Nem o autor da review enxerga o alvo indisponível pela listagem pública
            assertSameNotFound("/api/v1/targets/{id}/reviews", target, missing, "RATEABLE_TARGET_NOT_FOUND", f.author(), forbidden);
        }

        // Só a leitura pública muda: o dado continua gravado, com o status real
        assertEquals(placeStatus, jdbcTemplate.queryForObject("SELECT status FROM places WHERE id = ?", String.class,
                f.placeId()));
    }

    @Test
    @DisplayName("Nova review em alvo ou local de contexto indisponível responde o 404 de inexistente e não grava nada")
    void reviewCreationRequiresAvailableTargetAndContext() throws Exception {
        TestUser author = register("detalhe_nova_review");
        Fixture f = fixture();
        jdbcTemplate.update("UPDATE products SET status = 'DISCONTINUED' WHERE id = ?", f.productId());
        Integer before = jdbcTemplate.queryForObject("SELECT count(*) FROM reviews WHERE user_id = ?", Integer.class,
                author.id());

        // Produto descontinuado como alvo: mesmo 404 de um alvo inexistente
        JsonNode hiddenTarget = read(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateReviewRequest(f.placeId(), "Nova", false, "PUBLIC",
                        List.of(new CreateReviewTargetRequest(f.productId(), new BigDecimal("4.0"), null))))), author, 404);
        assertEquals("RATEABLE_TARGET_NOT_FOUND", hiddenTarget.get("code").asText());
        JsonNode missingTarget = read(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateReviewRequest(f.placeId(), "Nova", false, "PUBLIC",
                        List.of(new CreateReviewTargetRequest(UUID.randomUUID(), new BigDecimal("4.0"), null))))), author, 404);
        assertEquals(missingTarget.get("detail").asText().replaceAll("[0-9a-f-]{36}", ""),
                hiddenTarget.get("detail").asText().replaceAll("[0-9a-f-]{36}", ""));

        // Local de contexto fechado: mesmo 404 de um local inexistente, mesmo com alvo disponível
        jdbcTemplate.update("UPDATE places SET status = 'CLOSED' WHERE id = ?", f.placeId());
        UUID activeProduct = productRepository.save(new Product(null, "Produto ativo " + UUID.randomUUID(), "Marca", null,
                null, "BEBIDA", null, "ACTIVE")).getId();
        JsonNode hiddenContext = read(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateReviewRequest(f.placeId(), "Nova", false, "PUBLIC",
                        List.of(new CreateReviewTargetRequest(activeProduct, new BigDecimal("4.0"), null))))), author, 404);
        assertEquals("PLACE_NOT_FOUND", hiddenContext.get("code").asText());
        JsonNode missingContext = read(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateReviewRequest(UUID.randomUUID(), "Nova", false, "PUBLIC",
                        List.of(new CreateReviewTargetRequest(activeProduct, new BigDecimal("4.0"), null))))), author, 404);
        assertEquals(missingContext.get("detail").asText(), hiddenContext.get("detail").asText());

        assertEquals(before, jdbcTemplate.queryForObject("SELECT count(*) FROM reviews WHERE user_id = ?", Integer.class,
                author.id()), "nenhuma review gravada");

        // Alvo e contexto disponíveis seguem aceitos
        read(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateReviewRequest(null, "Nova", false, "PUBLIC",
                        List.of(new CreateReviewTargetRequest(activeProduct, new BigDecimal("4.0"), null))))), author, 201);
    }

    @Test
    @DisplayName("Inexistente: o mesmo 404 de sempre em todos os endpoints")
    void missingTargets() throws Exception {
        TestUser viewer = register("detalhe_leitor");
        UUID missing = UUID.randomUUID();
        assertEquals("PLACE_NOT_FOUND", read(get("/api/v1/places/{id}", missing), viewer, 404).get("code").asText());
        assertEquals("PRODUCT_NOT_FOUND", read(get("/api/v1/products/{id}", missing), viewer, 404).get("code").asText());
        assertEquals("RATEABLE_TARGET_NOT_FOUND",
                read(get("/api/v1/targets/{id}/stats", missing), viewer, 404).get("code").asText());
        assertEquals("RATEABLE_TARGET_NOT_FOUND",
                read(get("/api/v1/targets/{id}/reviews", missing), viewer, 404).get("code").asText());
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * O 404 do alvo indisponível é o do inexistente: mesmos campos e valores, exceto {@code instance}, que só repete o
     * caminho pedido. Nenhum dado do alvo aparece no corpo.
     */
    private void assertSameNotFound(String path, UUID hidden, UUID missing, String code, TestUser viewer,
                                    List<String> forbidden) throws Exception {
        MvcResult hiddenResult = perform(get(path, hidden), viewer, 404);
        String body = hiddenResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode hiddenNode = objectMapper.readTree(body);
        JsonNode missingNode = read(get(path, missing), viewer, 404);

        assertEquals(code, hiddenNode.get("code").asText(), body);
        List<String> fields = fieldNames(hiddenNode);
        assertEquals(fieldNames(missingNode), fields, body);
        for (String field : fields) {
            if (field.equals("instance") || field.equals("timestamp")) {
                continue;
            }
            assertEquals(missingNode.get(field), hiddenNode.get(field), path + " campo " + field);
        }
        String withoutRequestedPath = body.replace(hidden.toString(), "");
        for (String value : forbidden) {
            assertFalse(withoutRequestedPath.contains(value), path + " vazou '" + value + "': " + body);
        }
        assertFalse(withoutRequestedPath.matches("(?s).*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*"),
                "nenhum UUID além do id pedido: " + body);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names.stream().sorted().toList();
    }

    private Fixture fixture() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String placeName = "Local Detalhe " + suffix;
        String placeSlug = "local-detalhe-" + suffix;
        UUID placeId = placeRepository.save(new Place(null, placeName, placeSlug, "RESTAURANTE", "Descrição",
                "Rua Detalhe, 1", "Curitiba", "PR", "BR", -25.43, -49.27, 50, "USER", false, null, "ACTIVE")).getId();
        String productName = "Produto Detalhe " + suffix;
        UUID productId = productRepository.save(new Product(null, productName, "Marca", null, null, "BEBIDA", null,
                "ACTIVE")).getId();

        TestUser author = register("detalhe_autor");
        JsonNode review = read(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateReviewRequest(placeId, REVIEW_TEXT, false, "PUBLIC",
                        List.of(new CreateReviewTargetRequest(placeId, new BigDecimal("4.0"), "Nota do local"),
                                new CreateReviewTargetRequest(productId, new BigDecimal("3.0"), "Nota do produto"))))),
                author, 201);
        return new Fixture(placeId, placeName, placeSlug, productId, productName,
                UUID.fromString(review.get("id").asText()), author);
    }

    private JsonNode read(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          TestUser user, int expectedStatus) throws Exception {
        return objectMapper.readTree(perform(request, user, expectedStatus).getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private MvcResult perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                              TestUser user, int expectedStatus) throws Exception {
        if (user != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken());
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus(),
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return result;
    }

    private TestUser register(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode node = read(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest(prefix + "." + suffix + "@rewit.test",
                        "Senha@" + suffix, prefix + "_" + suffix, "Nome " + suffix))), null, 201);
        return new TestUser(UUID.fromString(node.get("user").get("id").asText()), node.get("accessToken").asText());
    }
}
