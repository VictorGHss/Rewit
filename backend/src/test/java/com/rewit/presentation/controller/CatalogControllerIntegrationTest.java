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
import com.rewit.application.port.PlaceExternalReferenceRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceExternalReference;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.AdoptPlaceRequest.ExternalReferenceRequest;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração da API REST de Catálogo /api/v1/places e /api/v1/products (Step 7 e Step 9.4)")
class CatalogControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private PlaceExternalReferenceRepository referenceRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

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

    @Test
    @DisplayName("16. Caso A: POST /api/v1/places/adopt com JWT cria Place, retorna 201 Created e Location")
    void shouldAdoptNewPlaceAndReturn201WithLocation() throws Exception {
        TestUser user = registerUser("adopt_a");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String extId = "ChIJ_ADOPT_A_" + suffix;

        AdoptPlaceRequest req = new AdoptPlaceRequest(
                "Restaurante Caso A " + suffix,
                "restaurante-caso-a-" + suffix,
                "RESTAURANTE",
                "Comida caseira",
                "Rua das Palmeiras, 200",
                "200",
                "Batel",
                "Curitiba",
                "PR",
                "BR",
                -25.4350,
                -49.2750,
                50,
                new ExternalReferenceRequest("GOOGLE", extId)
        );

        MvcResult result = mockMvc.perform(post("/api/v1/places/adopt")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertNotNull(location, "Location header deve existir na criação 201");
        assertTrue(location.startsWith("/api/v1/places/"));

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID createdId = UUID.fromString(node.get("id").asText());
        assertEquals("restaurante-caso-a-" + suffix, node.get("slug").asText());
        assertEquals("USER", node.get("origin").asText());
        assertEquals("ACTIVE", node.get("status").asText());

        // Valida persistência real no PostgreSQL
        assertTrue(placeRepository.findById(createdId).isPresent());
        assertTrue(referenceRepository.findByProviderAndExternalId("GOOGLE", extId).isPresent());
        assertTrue(rateableTargetRepository.findById(createdId).isPresent());
    }

    @Test
    @DisplayName("17. Caso B: POST /api/v1/places/adopt idempotente com mesma referência retorna 200 OK sem duplicar")
    void shouldReturn200OkOnIdempotentAdoptionWithoutDuplication() throws Exception {
        TestUser user = registerUser("adopt_b");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String extId = "ChIJ_ADOPT_B_" + suffix;

        AdoptPlaceRequest firstReq = new AdoptPlaceRequest(
                "Padaria Original " + suffix,
                "padaria-original-" + suffix,
                "PADARIA",
                "Desc original",
                "Rua 1",
                "10",
                "Centro",
                "Curitiba",
                "PR",
                "BR",
                -25.43,
                -49.27,
                50,
                new ExternalReferenceRequest("GOOGLE", extId)
        );

        MvcResult firstRes = mockMvc.perform(post("/api/v1/places/adopt")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstReq)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode firstNode = objectMapper.readTree(firstRes.getResponse().getContentAsString());
        String originalId = firstNode.get("id").asText();

        // Segunda requisição com dados diferentes mas mesmo externalId
        AdoptPlaceRequest secondReq = new AdoptPlaceRequest(
                "Outro Nome Alterado",
                "outro-slug",
                "OUTRO",
                "Outra desc",
                "Outro end",
                "999",
                "Outro bairro",
                "São Paulo",
                "SP",
                "BR",
                -23.55,
                -46.63,
                100,
                new ExternalReferenceRequest("GOOGLE", extId)
        );

        MvcResult secondRes = mockMvc.perform(post("/api/v1/places/adopt")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondReq)))
                .andExpect(status().isOk())
                .andReturn();

        assertNull(secondRes.getResponse().getHeader("Location"), "Adoção idempotente 200 não deve conter Location de novo recurso");

        JsonNode secondNode = objectMapper.readTree(secondRes.getResponse().getContentAsString());
        assertEquals(originalId, secondNode.get("id").asText(), "Deve retornar exatamente o mesmo Place.id");
        assertEquals("Padaria Original " + suffix, secondNode.get("name").asText(), "Nome original não deve ser alterado");
        assertEquals("padaria-original-" + suffix, secondNode.get("slug").asText(), "Slug original não deve ser alterado");
        assertEquals("Curitiba", secondNode.get("city").asText(), "Cidade original não deve ser alterada");
    }

    @Test
    @DisplayName("18. Caso C: POST /api/v1/places/adopt com dados Google extras não persiste conteúdo externo")
    void shouldNotPersistExtraGoogleData() throws Exception {
        TestUser user = registerUser("adopt_c");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String extId = "ChIJ_ADOPT_C_" + suffix;

        Map<String, Object> payloadWithGoogleData = Map.ofEntries(
                Map.entry("name", "Lugar Zero-Store " + suffix),
                Map.entry("addressText", "Rua das Flores"),
                Map.entry("city", "Curitiba"),
                Map.entry("state", "PR"),
                Map.entry("latitude", -25.43),
                Map.entry("longitude", -49.27),
                Map.entry("externalReference", Map.of(
                        "provider", "GOOGLE",
                        "externalId", extId
                )),
                Map.entry("displayName", "Google Display Name"),
                Map.entry("formattedAddress", "Google Formatted Address"),
                Map.entry("rating", 4.9),
                Map.entry("reviews", "raw reviews"),
                Map.entry("metadataJson", "{\"google\": true}")
        );

        MvcResult result = mockMvc.perform(post("/api/v1/places/adopt")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payloadWithGoogleData)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID placeId = UUID.fromString(node.get("id").asText());

        // Valida no banco que metadata_json da referência externa permanece estritamente null
        Optional<PlaceExternalReference> ref = referenceRepository.findByProviderAndExternalId("GOOGLE", extId);
        assertTrue(ref.isPresent());
        assertNull(ref.get().getMetadataJson(), "Zero-store: metadata_json deve ser null");
        assertEquals(placeId, ref.get().getPlaceId());
    }

    @Test
    @DisplayName("19. Caso D: POST /api/v1/places/adopt sem JWT retorna 401 Unauthorized")
    void shouldReturn401WhenAdoptingWithoutJwt() throws Exception {
        AdoptPlaceRequest req = new AdoptPlaceRequest(
                "Sem Auth",
                "sem-auth",
                "GERAL",
                "Desc",
                "End",
                "1",
                "Bairro",
                "Curitiba",
                "PR",
                "BR",
                -25.0,
                -49.0,
                50,
                null
        );

        mockMvc.perform(post("/api/v1/places/adopt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("20. Caso E: GET /api/v1/places/external/{provider}/{externalId} retorna 200 OK com dados do local")
    void shouldGetPlaceByExternalReferenceSuccessfully() throws Exception {
        TestUser user = registerUser("adopt_e");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String extId = "ChIJ_ADOPT_E_" + suffix;

        AdoptPlaceRequest req = new AdoptPlaceRequest(
                "Lugar Caso E " + suffix,
                "lugar-caso-e-" + suffix,
                "BAR",
                "Desc E",
                "Rua XV",
                "10",
                "Centro",
                "Curitiba",
                "PR",
                "BR",
                -25.43,
                -49.27,
                50,
                new ExternalReferenceRequest("GOOGLE", extId)
        );

        MvcResult createRes = mockMvc.perform(post("/api/v1/places/adopt")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createdNode = objectMapper.readTree(createRes.getResponse().getContentAsString());
        String expectedId = createdNode.get("id").asText();

        // Consulta pelo endpoint de referência externa
        MvcResult getRes = mockMvc.perform(get("/api/v1/places/external/{provider}/{externalId}", "GOOGLE", extId)
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode getNode = objectMapper.readTree(getRes.getResponse().getContentAsString());
        assertEquals(expectedId, getNode.get("id").asText());
        assertEquals("Lugar Caso E " + suffix, getNode.get("name").asText());
        assertEquals("lugar-caso-e-" + suffix, getNode.get("slug").asText());
    }

    @Test
    @DisplayName("21. Caso F: GET /api/v1/places/external/{provider}/{externalId} com referência inexistente retorna 404")
    void shouldReturn404WhenExternalReferenceNotFound() throws Exception {
        TestUser user = registerUser("adopt_f");

        MvcResult res = mockMvc.perform(get("/api/v1/places/external/{provider}/{externalId}", "GOOGLE", "ChIJ_NONEXISTENT_404")
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isNotFound())
                .andReturn();

        JsonNode errorNode = objectMapper.readTree(res.getResponse().getContentAsString());
        assertEquals("PLACE_EXTERNAL_REFERENCE_NOT_FOUND", errorNode.get("code").asText());
    }

    @Test
    @DisplayName("22. Caso G: Duas requisições HTTP concorrentes com mesma referência externa convergem para mesmo Place.id sem 500 nem órfãos")
    void shouldHandleConcurrentHttpAdoptionsSafely() throws Exception {
        TestUser user1 = registerUser("adopt_g1");
        TestUser user2 = registerUser("adopt_g2");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sharedExtId = "ChIJ_CONCURRENT_HTTP_" + suffix;

        int numberOfThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(numberOfThreads);

        AtomicReference<String> placeIdThread1 = new AtomicReference<>();
        AtomicReference<String> placeIdThread2 = new AtomicReference<>();
        AtomicReference<Integer> statusThread1 = new AtomicReference<>();
        AtomicReference<Integer> statusThread2 = new AtomicReference<>();
        AtomicReference<Exception> errorThread1 = new AtomicReference<>();
        AtomicReference<Exception> errorThread2 = new AtomicReference<>();

        Callable<Void> task1 = () -> {
            startSignal.await();
            try {
                AdoptPlaceRequest req = new AdoptPlaceRequest(
                        "Lugar HTTP Concorrente 1 " + suffix,
                        null,
                        "BAR",
                        "Desc 1",
                        "End 1",
                        "1",
                        "Bairro",
                        "Curitiba",
                        "PR",
                        "BR",
                        -25.43,
                        -49.27,
                        50,
                        new ExternalReferenceRequest("GOOGLE", sharedExtId)
                );
                MvcResult res = mockMvc.perform(post("/api/v1/places/adopt")
                                .header("Authorization", "Bearer " + user1.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                        .andReturn();

                statusThread1.set(res.getResponse().getStatus());
                JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
                if (node.has("id")) {
                    placeIdThread1.set(node.get("id").asText());
                }
            } catch (Exception e) {
                errorThread1.set(e);
            } finally {
                doneSignal.countDown();
            }
            return null;
        };

        Callable<Void> task2 = () -> {
            startSignal.await();
            try {
                AdoptPlaceRequest req = new AdoptPlaceRequest(
                        "Lugar HTTP Concorrente 2 " + suffix,
                        null,
                        "BAR",
                        "Desc 2",
                        "End 2",
                        "2",
                        "Bairro",
                        "Curitiba",
                        "PR",
                        "BR",
                        -25.43,
                        -49.27,
                        50,
                        new ExternalReferenceRequest("GOOGLE", sharedExtId)
                );
                MvcResult res = mockMvc.perform(post("/api/v1/places/adopt")
                                .header("Authorization", "Bearer " + user2.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                        .andReturn();

                statusThread2.set(res.getResponse().getStatus());
                JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
                if (node.has("id")) {
                    placeIdThread2.set(node.get("id").asText());
                }
            } catch (Exception e) {
                errorThread2.set(e);
            } finally {
                doneSignal.countDown();
            }
            return null;
        };

        executor.submit(task1);
        executor.submit(task2);

        // Dispara simultaneamente
        startSignal.countDown();

        boolean finished = doneSignal.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finished, "As requisições HTTP concorrentes devem concluir em até 10 segundos");
        assertNull(errorThread1.get());
        assertNull(errorThread2.get());

        // Ambas as threads devem receber sucesso (201 ou 200), NENHUM 500
        assertTrue(statusThread1.get() == 201 || statusThread1.get() == 200, "Thread 1 deve ter status 201 ou 200, teve: " + statusThread1.get());
        assertTrue(statusThread2.get() == 201 || statusThread2.get() == 200, "Thread 2 deve ter status 201 ou 200, teve: " + statusThread2.get());

        assertNotNull(placeIdThread1.get());
        assertNotNull(placeIdThread2.get());

        // Ambas devem convergir para o mesmo Place.id!
        assertEquals(placeIdThread1.get(), placeIdThread2.get(), "Ambas as requisições HTTP concorrentes devem convergir para o mesmo Place.id");

        UUID finalPlaceId = UUID.fromString(placeIdThread1.get());

        // Valida no banco: exatamente 1 referência externa, 1 Place e 1 RateableTarget
        Optional<PlaceExternalReference> ref = referenceRepository.findByProviderAndExternalId("GOOGLE", sharedExtId);
        assertTrue(ref.isPresent());
        assertEquals(finalPlaceId, ref.get().getPlaceId());

        assertTrue(placeRepository.findById(finalPlaceId).isPresent());
        assertTrue(rateableTargetRepository.findById(finalPlaceId).isPresent());
    }

    // =========================================================================
    // STEP 9.5: TESTES DE INTEGRAÇÃO HTTP REAL: GET /api/v1/places/nearby
    // =========================================================================

    @Test
    @DisplayName("23. GET /api/v1/places/nearby: Sem autenticação JWT retorna 401 Unauthorized")
    void shouldReturn401WhenNearbyWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/places/nearby")
                        .param("latitude", "-25.4297")
                        .param("longitude", "-49.2719")
                        .param("radiusMeters", "1000.0"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("24. GET /api/v1/places/nearby: Parâmetros inválidos retornam 400 Bad Request")
    void shouldReturn400WhenNearbyParametersAreInvalid() throws Exception {
        TestUser user = registerUser("nearby_invalid");

        // Latitude inválida (> 90)
        mockMvc.perform(get("/api/v1/places/nearby")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .param("latitude", "95.0")
                        .param("longitude", "-49.2719")
                        .param("radiusMeters", "1000.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NEARBY_COORDINATES"));

        // Longitude inválida (< -180)
        mockMvc.perform(get("/api/v1/places/nearby")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .param("latitude", "-25.4297")
                        .param("longitude", "-185.0")
                        .param("radiusMeters", "1000.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NEARBY_COORDINATES"));

        // Raio inválido (zero)
        mockMvc.perform(get("/api/v1/places/nearby")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .param("latitude", "-25.4297")
                        .param("longitude", "-49.2719")
                        .param("radiusMeters", "0.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NEARBY_RADIUS"));

        // Raio inválido (> 50.000m)
        mockMvc.perform(get("/api/v1/places/nearby")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .param("latitude", "-25.4297")
                        .param("longitude", "-49.2719")
                        .param("radiusMeters", "55000.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NEARBY_RADIUS"));

        // Limit inválido (zero)
        mockMvc.perform(get("/api/v1/places/nearby")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .param("latitude", "-25.4297")
                        .param("longitude", "-49.2719")
                        .param("radiusMeters", "1000.0")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NEARBY_LIMIT"));

        // Limit inválido (> 100)
        mockMvc.perform(get("/api/v1/places/nearby")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .param("latitude", "-25.4297")
                        .param("longitude", "-49.2719")
                        .param("radiusMeters", "1000.0")
                        .param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NEARBY_LIMIT"));
    }

    @Test
    @DisplayName("25. GET /api/v1/places/nearby: Lista vazia retorna 200 OK com items=[]")
    void shouldReturn200OkWithEmptyListWhenNoPlacesNearby() throws Exception {
        TestUser user = registerUser("nearby_empty");

        // Coordenadas no meio do oceano onde não há nenhum local cadastrado
        mockMvc.perform(get("/api/v1/places/nearby")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .param("latitude", "-10.0")
                        .param("longitude", "-20.0")
                        .param("radiusMeters", "500.0")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.limit").value(10));
    }

    @Test
    @DisplayName("26. GET /api/v1/places/nearby: Requisição válida retorna 200 OK com itens ordenados, distância e limite respeitado")
    void shouldReturn200OkWithOrderedNearbyPlacesAndDistance() throws Exception {
        TestUser user = registerUser("nearby_valid");
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // Centro: Porto Alegre (-30.0346, -51.2177)
        double centerLat = -30.0346;
        double centerLon = -51.2177;

        // Local A: ~200m
        Place placeA = placeRepository.save(new Place(
                null, "Café POA A " + suffix, "cafe-poa-a-" + suffix, "CAFE", "Perto",
                "Rua dos Andradas, 100", "Porto Alegre", "RS", "BR", -30.0335, -51.2185, 50, "USER", false, null, "ACTIVE"
        ));

        // Local B: ~1500m
        Place placeB = placeRepository.save(new Place(
                null, "Restaurante POA B " + suffix, "restaurante-poa-b-" + suffix, "RESTAURANTE", "Médio",
                "Av Ipiranga, 500", "Porto Alegre", "RS", "BR", -30.0450, -51.2050, 50, "USER", false, null, "ACTIVE"
        ));

        // Local C: ~3000m
        Place placeC = placeRepository.save(new Place(
                null, "Bar POA C " + suffix, "bar-poa-c-" + suffix, "BAR", "Mais distante",
                "Av Assis Brasil, 1000", "Porto Alegre", "RS", "BR", -30.0100, -51.1900, 50, "USER", false, null, "ACTIVE"
        ));

        // Consulta raio 2000m com limit 100 (máximo da API) para garantir que Place A e Place B
        // apareçam mesmo quando outras runs acumularam Places no banco compartilhado sem limpeza
        MvcResult result = mockMvc.perform(get("/api/v1/places/nearby")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .param("latitude", String.valueOf(centerLat))
                        .param("longitude", String.valueOf(centerLon))
                        .param("radiusMeters", "2000.0")
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(100))
                .andExpect(jsonPath("$.items").isArray())
                .andReturn();


        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode items = root.get("items");

        // Verifica que Place A e Place B aparecem, e Place C não aparece
        boolean foundA = false;
        boolean foundB = false;
        boolean foundC = false;
        double distA = 0;
        double distB = 0;

        for (JsonNode item : items) {
            String itemId = item.get("id").asText();
            if (itemId.equals(placeA.getId().toString())) {
                foundA = true;
                distA = item.get("distanceMeters").asDouble();
            }
            if (itemId.equals(placeB.getId().toString())) {
                foundB = true;
                distB = item.get("distanceMeters").asDouble();
            }
            if (itemId.equals(placeC.getId().toString())) {
                foundC = true;
            }
        }

        assertTrue(foundA, "Place A deve estar nos resultados de 2000m");
        assertTrue(foundB, "Place B deve estar nos resultados de 2000m");
        assertFalse(foundC, "Place C está fora do raio de 2000m e não deve estar nos resultados");

        // Validação da ordenação por proximidade crescente: A mais próximo que B
        assertTrue(distA < distB, "Place A (mais próximo) deve ter distância menor que Place B: " + distA + " vs " + distB);

        // Consulta com limit 1 -> Não deve ultrapassar o limite solicitado
        MvcResult resultLimit1 = mockMvc.perform(get("/api/v1/places/nearby")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .param("latitude", String.valueOf(centerLat))
                        .param("longitude", String.valueOf(centerLon))
                        .param("radiusMeters", "5000.0")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn();

        JsonNode limit1Node = objectMapper.readTree(resultLimit1.getResponse().getContentAsString());
        assertEquals(1, limit1Node.get("limit").asInt(), "O campo limit retornado deve ser 1");
        assertEquals(1, limit1Node.get("items").size(), "A lista de itens com limit=1 deve conter exatamente 1 elemento");
        assertTrue(limit1Node.get("items").get(0).hasNonNull("distanceMeters"), "O item retornado deve conter distanceMeters");
        assertTrue(limit1Node.get("items").get(0).get("distanceMeters").asDouble() <= 5000.0, "A distância deve respeitar o raio de 5000m");
    }
}
