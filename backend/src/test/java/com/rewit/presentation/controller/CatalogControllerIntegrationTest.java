package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração da API REST de Catálogo /api/v1/places e /api/v1/products (Step 7)")
class CatalogControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private record TestUser(String accessToken, UUID userId) {}

    private TestUser registerUser(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "." + suffix + "@rewit.com";
        String password = "Password@" + suffix;

        RegisterRequest req = new RegisterRequest(email, password, "@" + prefix + "_" + suffix, "Nome " + suffix);
        MvcResult res = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        String token = node.get("accessToken").asText();
        UUID id = UUID.fromString(node.get("user").get("id").asText());
        return new TestUser(token, id);
    }

    @Test
    @DisplayName("1. POST /api/v1/places autenticado cria lugar e GET /api/v1/places/{id} retorna os dados")
    void shouldCreateAndRetrievePlaceSuccessfully() throws Exception {
        TestUser user = registerUser("cat_place");
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        CreatePlaceRequest createReq = new CreatePlaceRequest(
                "Restaurante Bella " + suffix,
                "restaurante-bella-" + suffix,
                "RESTAURANTE",
                "Comida italiana tradicional",
                "Rua Marechal Deodoro, 500",
                "500",
                "Centro",
                "Curitiba",
                "PR",
                "BR",
                -25.4312,
                -49.2685,
                60
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/places")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createdNode = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String placeId = createdNode.get("id").asText();
        assertEquals("restaurante-bella-" + suffix, createdNode.get("slug").asText());
        assertEquals("500", createdNode.get("streetNumber").asText());
        assertEquals("Centro", createdNode.get("neighborhood").asText());
        assertEquals(-25.4312, createdNode.get("latitude").asDouble(), 0.0001);

        // GET por ID
        MvcResult getResult = mockMvc.perform(get("/api/v1/places/" + placeId)
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode getNode = objectMapper.readTree(getResult.getResponse().getContentAsString());
        assertEquals(placeId, getNode.get("id").asText());
        assertEquals("Restaurante Bella " + suffix, getNode.get("name").asText());
    }

    @Test
    @DisplayName("2. POST /api/v1/places sem autenticação deve retornar 401 Unauthorized")
    void shouldReturn401WhenCreatingPlaceUnauthenticated() throws Exception {
        CreatePlaceRequest createReq = new CreatePlaceRequest(
                "Lugar Sem Auth", "lugar-sem-auth", "BAR", null,
                "Rua 1", null, null, "Curitiba", "PR", "BR",
                -25.4, -49.2, 50
        );

        mockMvc.perform(post("/api/v1/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("3. POST /api/v1/places com slug conflitante deve retornar 409 Conflict")
    void shouldReturn409WhenSlugAlreadyExists() throws Exception {
        TestUser user = registerUser("slug_conf");
        String slug = "slug-duplicado-" + UUID.randomUUID().toString().substring(0, 8);

        CreatePlaceRequest reqA = new CreatePlaceRequest(
                "Lugar A", slug, "BAR", null, "Rua A", null, null, "Curitiba", "PR", "BR", -25.4, -49.2, 50
        );
        mockMvc.perform(post("/api/v1/places")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqA)))
                .andExpect(status().isCreated());

        CreatePlaceRequest reqB = new CreatePlaceRequest(
                "Lugar B", slug, "CAFE", null, "Rua B", null, null, "Curitiba", "PR", "BR", -25.5, -49.3, 50
        );
        MvcResult conflictResult = mockMvc.perform(post("/api/v1/places")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqB)))
                .andExpect(status().isConflict())
                .andReturn();

        JsonNode errorNode = objectMapper.readTree(conflictResult.getResponse().getContentAsString());
        assertEquals("PLACE_SLUG_ALREADY_EXISTS", errorNode.get("code").asText());
    }

    @Test
    @DisplayName("4. Ciclo completo de Produto: Criar, Consultar, Adicionar Identificador e Associar Presença")
    void shouldExecuteFullProductLifecycle() throws Exception {
        TestUser user = registerUser("cat_prod");
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // 1. Cria lugar primeiro para ter um placeId
        CreatePlaceRequest placeReq = new CreatePlaceRequest(
                "Mercado " + suffix, "mercado-prod-" + suffix, "MERCADO", null,
                "Rua M", null, null, "Curitiba", "PR", "BR", -25.4, -49.2, 50
        );
        MvcResult placeRes = mockMvc.perform(post("/api/v1/places")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(placeReq)))
                .andExpect(status().isCreated())
                .andReturn();
        String placeId = objectMapper.readTree(placeRes.getResponse().getContentAsString()).get("id").asText();

        // 2. Cria Produto
        CreateProductRequest prodReq = new CreateProductRequest(
                "Café em Grãos " + suffix, "Moinho Curitiba", "Torra Média 250g", "Grãos selecionados", "BEBIDAS", "http://img.com/cafe.png"
        );
        MvcResult prodRes = mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(prodReq)))
                .andExpect(status().isCreated())
                .andReturn();

        String prodId = objectMapper.readTree(prodRes.getResponse().getContentAsString()).get("id").asText();

        // 3. Adiciona identificador EAN
        String barcode = "789" + suffix;
        AddProductIdentifierRequest idReq = new AddProductIdentifierRequest("EAN", barcode);
        MvcResult idRes = mockMvc.perform(post("/api/v1/products/" + prodId + "/identifiers")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(idReq)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode idNode = objectMapper.readTree(idRes.getResponse().getContentAsString());
        assertEquals("EAN", idNode.get("identifierType").asText());
        assertEquals(barcode, idNode.get("identifierValue").asText());

        // 4. Associa Presença de Produto ao Lugar
        AssociateProductPresenceRequest presenceReq = new AssociateProductPresenceRequest(UUID.fromString(placeId));
        MvcResult presenceRes = mockMvc.perform(post("/api/v1/products/" + prodId + "/presence")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presenceReq)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode presenceNode = objectMapper.readTree(presenceRes.getResponse().getContentAsString());
        assertEquals(prodId, presenceNode.get("productId").asText());
        assertEquals(placeId, presenceNode.get("placeId").asText());
        assertEquals(user.userId().toString(), presenceNode.get("reportedByUserId").asText(),
                "reportedByUserId deve ser preenchido a partir do JWT autenticado");
        assertEquals("AVAILABLE", presenceNode.get("status").asText());
    }

    @Test
    @DisplayName("5. POST /api/v1/places com payload inválido deve retornar 400 Bad Request")
    void shouldReturn400WhenCreatingPlaceWithInvalidPayload() throws Exception {
        TestUser user = registerUser("val_place");

        // Nome em branco e coordenadas fora do intervalo WGS84
        String invalidJson = """
            {
                "name": "",
                "slug": "slug-invalido",
                "category": "BAR",
                "addressText": "Rua 1",
                "city": "Curitiba",
                "state": "PR",
                "latitude": 95.0,
                "longitude": -49.0,
                "validationRadiusMeters": 0
            }
        """;

        mockMvc.perform(post("/api/v1/places")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("6. GET /api/v1/places/{id} com ID inexistente deve retornar 404 Not Found com Problem Details")
    void shouldReturn404WhenPlaceNotFound() throws Exception {
        TestUser user = registerUser("not_found_place");
        UUID nonexistentId = UUID.randomUUID();

        MvcResult res = mockMvc.perform(get("/api/v1/places/" + nonexistentId)
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isNotFound())
                .andReturn();

        JsonNode errorNode = objectMapper.readTree(res.getResponse().getContentAsString());
        assertEquals("PLACE_NOT_FOUND", errorNode.get("code").asText());
    }

    @Test
    @DisplayName("7. POST /api/v1/products sem autenticação deve retornar 401 Unauthorized")
    void shouldReturn401WhenCreatingProductUnauthenticated() throws Exception {
        CreateProductRequest req = new CreateProductRequest("Produto", "Marca", "Mod", "Desc", "CAT", null);
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("8. GET /api/v1/products/{id} retorna 200 para produto persistido e 404 para ID inexistente")
    void shouldRetrieveProductByIdAndReturn404ForNonexistent() throws Exception {
        TestUser user = registerUser("get_prod");
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        CreateProductRequest prodReq = new CreateProductRequest(
                "Produto " + suffix, "Marca", "Modelo", "Desc", "CAT", "http://img.com"
        );
        MvcResult createdRes = mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(prodReq)))
                .andExpect(status().isCreated())
                .andReturn();

        String prodId = objectMapper.readTree(createdRes.getResponse().getContentAsString()).get("id").asText();

        // 1. GET existente
        MvcResult getRes = mockMvc.perform(get("/api/v1/products/" + prodId)
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode getNode = objectMapper.readTree(getRes.getResponse().getContentAsString());
        assertEquals(prodId, getNode.get("id").asText());
        assertEquals("Produto " + suffix, getNode.get("name").asText());

        // 2. GET inexistente -> 404
        UUID nonexistent = UUID.randomUUID();
        MvcResult notFoundRes = mockMvc.perform(get("/api/v1/products/" + nonexistent)
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isNotFound())
                .andReturn();

        JsonNode errorNode = objectMapper.readTree(notFoundRes.getResponse().getContentAsString());
        assertEquals("PRODUCT_NOT_FOUND", errorNode.get("code").asText());
    }

    @Test
    @DisplayName("9. POST /api/v1/products/{id}/identifiers rejeita duplicidade com 409 IDENTIFIER_ALREADY_EXISTS")
    void shouldReturn409WhenAddingDuplicateProductIdentifier() throws Exception {
        TestUser user = registerUser("dup_id");
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // Cria produto
        CreateProductRequest prodReq = new CreateProductRequest("Produto Barcode " + suffix, null, null, null, null, null);
        MvcResult prodRes = mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(prodReq)))
                .andExpect(status().isCreated())
                .andReturn();
        String prodId = objectMapper.readTree(prodRes.getResponse().getContentAsString()).get("id").asText();

        String barcode = "789" + suffix;
        AddProductIdentifierRequest idReq = new AddProductIdentifierRequest("EAN", barcode);

        // Primeira inserção -> 201
        mockMvc.perform(post("/api/v1/products/" + prodId + "/identifiers")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(idReq)))
                .andExpect(status().isCreated());

        // Segunda inserção com o mesmo EAN -> 409
        MvcResult conflictRes = mockMvc.perform(post("/api/v1/products/" + prodId + "/identifiers")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(idReq)))
                .andExpect(status().isConflict())
                .andReturn();

        JsonNode conflictNode = objectMapper.readTree(conflictRes.getResponse().getContentAsString());
        assertEquals("IDENTIFIER_ALREADY_EXISTS", conflictNode.get("code").asText());
    }

    @Test
    @DisplayName("10. POST /api/v1/products/{id}/presence exige autenticação (401 se ausente)")
    void shouldReturn401WhenAssociatingProductPresenceUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/products/" + UUID.randomUUID() + "/presence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssociateProductPresenceRequest(UUID.randomUUID()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("11. ProductPresence: Campo userId/reportedByUserId enviado pelo cliente é ignorado, impedindo impersonação")
    void shouldIgnoreClientSuppliedUserIdAndUseAuthenticatedUserInProductPresence() throws Exception {
        TestUser user = registerUser("anti_impersonate");
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // 1. Cria lugar
        CreatePlaceRequest placeReq = new CreatePlaceRequest(
                "Lugar " + suffix, "lugar-imp-" + suffix, "MERCADO", null,
                "Rua M", null, null, "Curitiba", "PR", "BR", -25.4, -49.2, 50
        );
        MvcResult placeRes = mockMvc.perform(post("/api/v1/places")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(placeReq)))
                .andExpect(status().isCreated())
                .andReturn();
        String placeId = objectMapper.readTree(placeRes.getResponse().getContentAsString()).get("id").asText();

        // 2. Cria produto
        CreateProductRequest prodReq = new CreateProductRequest("Produto " + suffix, null, null, null, null, null);
        MvcResult prodRes = mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(prodReq)))
                .andExpect(status().isCreated())
                .andReturn();
        String prodId = objectMapper.readTree(prodRes.getResponse().getContentAsString()).get("id").asText();

        // 3. Payload malicioso tentando injetar outro userId e reportedByUserId
        UUID spoofedUserId = UUID.randomUUID();
        String maliciousJson = """
            {
                "placeId": "%s",
                "userId": "%s",
                "reportedByUserId": "%s"
            }
        """.formatted(placeId, spoofedUserId, spoofedUserId);

        MvcResult presenceRes = mockMvc.perform(post("/api/v1/products/" + prodId + "/presence")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousJson))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode presenceNode = objectMapper.readTree(presenceRes.getResponse().getContentAsString());
        assertEquals(user.userId().toString(), presenceNode.get("reportedByUserId").asText(),
                "O reportedByUserId gravado deve ser ESTRITAMENTE o usuário autenticado no JWT, ignorando injeção do payload");
        assertNotEquals(spoofedUserId.toString(), presenceNode.get("reportedByUserId").asText(),
                "O spoofedUserId nunca pode ser gravado");
    }

    @Test
    @DisplayName("12. POST /api/v1/products/{id}/presence com local ou produto inexistente retorna 404 Not Found")
    void shouldReturn404WhenAssociatingProductPresenceWithNonexistentEntities() throws Exception {
        TestUser user = registerUser("presence_404");

        // 1. Produto inexistente
        UUID fakeProdId = UUID.randomUUID();
        UUID fakePlaceId = UUID.randomUUID();
        MvcResult resProd = mockMvc.perform(post("/api/v1/products/" + fakeProdId + "/presence")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssociateProductPresenceRequest(fakePlaceId))))
                .andExpect(status().isNotFound())
                .andReturn();
        assertEquals("PRODUCT_NOT_FOUND", objectMapper.readTree(resProd.getResponse().getContentAsString()).get("code").asText());

        // 2. Produto existe, mas local inexistente
        CreateProductRequest prodReq = new CreateProductRequest("Prod 404", null, null, null, null, null);
        MvcResult prodRes = mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(prodReq)))
                .andExpect(status().isCreated())
                .andReturn();
        String prodId = objectMapper.readTree(prodRes.getResponse().getContentAsString()).get("id").asText();

        MvcResult resPlace = mockMvc.perform(post("/api/v1/products/" + prodId + "/presence")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssociateProductPresenceRequest(fakePlaceId))))
                .andExpect(status().isNotFound())
                .andReturn();
        assertEquals("PLACE_NOT_FOUND", objectMapper.readTree(resPlace.getResponse().getContentAsString()).get("code").asText());
    }

    @Test
    @DisplayName("13. GET /api/v1/places/{id}, GET /api/v1/products/{id} e POST identifiers sem autenticação retornam 401")
    void shouldReturn401WhenAccessingProtectedEndpointsWithoutAuth() throws Exception {
        UUID randomId = UUID.randomUUID();

        // 1. GET /api/v1/places/{id} sem JWT -> 401
        mockMvc.perform(get("/api/v1/places/" + randomId))
                .andExpect(status().isUnauthorized());

        // 2. GET /api/v1/products/{id} sem JWT -> 401
        mockMvc.perform(get("/api/v1/products/" + randomId))
                .andExpect(status().isUnauthorized());

        // 3. POST /api/v1/products/{id}/identifiers sem JWT -> 401
        mockMvc.perform(post("/api/v1/products/" + randomId + "/identifiers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddProductIdentifierRequest("EAN", "12345678"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("14. Validação Bean Validation (400 Bad Request) para Product, Identifier e Presence")
    void shouldReturn400WhenPayloadsViolateValidationConstraints() throws Exception {
        TestUser user = registerUser("bean_val");
        UUID randomId = UUID.randomUUID();

        // 1. POST /api/v1/products com nome vazio -> 400
        CreateProductRequest invalidProd = new CreateProductRequest("", "Marca", "Mod", "Desc", "CAT", null);
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidProd)))
                .andExpect(status().isBadRequest());

        // 2. POST /api/v1/products/{id}/identifiers com tipo em branco -> 400
        AddProductIdentifierRequest invalidIdType = new AddProductIdentifierRequest("   ", "789123");
        mockMvc.perform(post("/api/v1/products/" + randomId + "/identifiers")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidIdType)))
                .andExpect(status().isBadRequest());

        // 3. POST /api/v1/products/{id}/identifiers com valor em branco -> 400
        AddProductIdentifierRequest invalidIdValue = new AddProductIdentifierRequest("EAN", "   ");
        mockMvc.perform(post("/api/v1/products/" + randomId + "/identifiers")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidIdValue)))
                .andExpect(status().isBadRequest());

        // 4. POST /api/v1/products/{id}/presence com placeId nulo -> 400
        mockMvc.perform(post("/api/v1/products/" + randomId + "/presence")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\": null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("15. POST /api/v1/products/{id}/identifiers com produto inexistente retorna 404 PRODUCT_NOT_FOUND")
    void shouldReturn404WhenAddingIdentifierToNonexistentProduct() throws Exception {
        TestUser user = registerUser("id_not_found");
        UUID nonexistentProdId = UUID.randomUUID();

        AddProductIdentifierRequest idReq = new AddProductIdentifierRequest("EAN", "7890001112223");
        MvcResult res = mockMvc.perform(post("/api/v1/products/" + nonexistentProdId + "/identifiers")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(idReq)))
                .andExpect(status().isNotFound())
                .andReturn();

        JsonNode errorNode = objectMapper.readTree(res.getResponse().getContentAsString());
        assertEquals("PRODUCT_NOT_FOUND", errorNode.get("code").asText());
    }
}
