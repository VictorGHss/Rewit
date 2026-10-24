package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceDiscoveryPort;
import com.rewit.infrastructure.integration.geoapify.GeoapifyPlacesAdapter;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ponta a ponta da descoberta com o provedor GEOAPIFY real (adapter, serviço, controller, segurança e serialização)
 * contra um servidor HTTP local que imita o Geoapify: nenhuma chamada sai da máquina e nenhuma chave real é usada.
 */
@SpringBootTest(properties = {
        "rewit.place-discovery.provider=GEOAPIFY",
        "rewit.geoapify.api-key=" + GeoapifyDiscoveryEndToEndIntegrationTest.FAKE_KEY
})
@ActiveProfiles("local")
@DisplayName("Descoberta GEOAPIFY ponta a ponta: JWT, região, serialização e ausência da chave")
class GeoapifyDiscoveryEndToEndIntegrationTest {

    static final String FAKE_KEY = "geoapify-chave-e2e-nao-real-7f3a";

    private static final String CITY_JSON = """
            {"results": [{"place_id": "51-pg-pr", "name": "Ponta Grossa", "city": "Ponta Grossa", "result_type": "city",
              "lat": -25.0945, "lon": -50.1633, "state": "Paraná", "state_code": "PR", "country_code": "br"}]}
            """;
    private static final String AMENITIES_JSON = """
            {"results": [
              {"place_id": "51-e2e-raikoski", "name": "Padaria Junior Raikoski", "result_type": "amenity",
               "formatted": "Padaria Junior Raikoski, Rua Conrado Schiffer, Ponta Grossa - PR, Brasil",
               "lat": -25.108, "lon": -50.169, "city": "Ponta Grossa", "state": "Paraná", "state_code": "PR",
               "country_code": "br", "category": "commercial.food_and_drink.bakery",
               "datasource": {"sourcename": "openstreetmap", "attribution": "© OpenStreetMap contributors",
                              "url": "https://www.openstreetmap.org/copyright"}},
              {"place_id": "51-e2e-sul", "name": "Padaria Região", "result_type": "amenity",
               "formatted": "Padaria Região, Ponta Grossa - Sul, Brasil", "lat": -25.1, "lon": -50.1,
               "city": "Ponta Grossa", "state": "Sul", "country_code": "br"}
            ]}
            """;

    private static HttpServer geoapifyStub;
    private static final List<Map<String, String>> stubRequests = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void geoapifyStubProperties(DynamicPropertyRegistry registry) throws IOException {
        if (geoapifyStub == null) {
            geoapifyStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            geoapifyStub.createContext("/v1/geocode/search", exchange -> {
                Map<String, String> params = UriComponentsBuilder.fromUri(exchange.getRequestURI()).build()
                        .getQueryParams().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey,
                                e -> URLDecoder.decode(e.getValue().getFirst(), StandardCharsets.UTF_8)));
                stubRequests.add(params);
                int status = 200;
                String body;
                if ("city".equals(params.get("type"))) {
                    body = CITY_JSON;
                } else if ("chave recusada".equals(params.get("text"))) {
                    status = 401;
                    body = "{\"statusCode\":401,\"message\":\"Invalid apiKey " + params.get("apiKey") + "\"}";
                } else {
                    body = AMENITIES_JSON;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            geoapifyStub.start();
        }
        registry.add("rewit.geoapify.base-url", () -> "http://127.0.0.1:" + geoapifyStub.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        if (geoapifyStub != null) {
            geoapifyStub.stop(0);
        }
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PlaceDiscoveryPort placeDiscoveryPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        stubRequests.clear();
    }

    private String registerAndGetToken() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest req = new RegisterRequest("geo.e2e." + suffix + "@rewit.com", "Password@" + suffix,
                "@geo_" + suffix, "Geo E2E " + suffix);
        MvcResult res = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    @DisplayName("Sem JWT: 401 e nenhuma chamada ao provedor")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/places/discovery/search").param("query", "padaria")
                        .param("city", "Ponta Grossa").param("state", "PR"))
                .andExpect(status().isUnauthorized());
        assertTrue(stubRequests.isEmpty());
    }

    @Test
    @DisplayName("Busca por cidade: filtra pela fronteira, UF validada, atribuições e nada da chave na resposta nem no catálogo")
    void citySearchEndToEnd() throws Exception {
        assertInstanceOf(GeoapifyPlacesAdapter.class, placeDiscoveryPort, "o adapter ativo é o Geoapify real");
        String token = registerAndGetToken();

        MvcResult result = mockMvc.perform(get("/api/v1/places/discovery/search")
                        .header("Authorization", "Bearer " + token)
                        .param("query", "padaria")
                        .param("city", "Ponta Grossa")
                        .param("state", "PR")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].provider").value("GEOAPIFY"))
                .andExpect(jsonPath("$[0].externalId").value("51-e2e-raikoski"))
                .andExpect(jsonPath("$[0].displayName").value("Padaria Junior Raikoski"))
                .andExpect(jsonPath("$[0].city").value("Ponta Grossa"))
                .andExpect(jsonPath("$[0].state").value("PR"))
                .andExpect(jsonPath("$[0].country").value("BR"))
                .andExpect(jsonPath("$[0].attributions[0].provider").value("Powered by Geoapify"))
                .andExpect(jsonPath("$[0].attributions[1].provider").value("© OpenStreetMap contributors"))
                // Região ("Sul") nunca vira UF: o candidato vem sem estado e o app não o adota
                .andExpect(jsonPath("$[1].displayName").value("Padaria Região"))
                .andExpect(jsonPath("$[1].state").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains(FAKE_KEY), "a chave do provedor nunca chega ao cliente");
        assertFalse(body.toLowerCase().contains("apikey"));

        // Resolução da cidade + busca filtrada pela fronteira dela
        assertEquals(2, stubRequests.size());
        assertEquals("city", stubRequests.get(0).get("type"));
        assertEquals("countrycode:br", stubRequests.get(0).get("filter"));
        assertEquals("place:51-pg-pr", stubRequests.get(1).get("filter"));
        assertEquals("padaria", stubRequests.get(1).get("text"));
        assertEquals(FAKE_KEY, stubRequests.get(1).get("apiKey"), "a chave só vai do backend ao provedor");

        // Pesquisa não persiste: nenhuma referência externa nem local foi criado
        Integer refs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_external_references WHERE provider = 'GEOAPIFY' AND external_id IN (?, ?)",
                Integer.class, "51-e2e-raikoski", "51-e2e-sul");
        assertEquals(0, refs);
    }

    @Test
    @DisplayName("Coordenadas com raio usam filtro circular; região inválida e cidade com coordenadas respondem 400")
    void coordinatesAndInvalidRegions() throws Exception {
        String token = registerAndGetToken();

        mockMvc.perform(get("/api/v1/places/discovery/search").header("Authorization", "Bearer " + token)
                        .param("query", "padaria").param("latitude", "-25.09").param("longitude", "-50.16")
                        .param("radius", "3000"))
                .andExpect(status().isOk());
        assertEquals("circle:-50.16,-25.09,3000.0", stubRequests.getLast().get("filter"));

        stubRequests.clear();
        for (String[] region : new String[][]{{"Ponta Grossa", "Sul"}, {"Ponta Grossa", "XX"}, {"", "PR"}}) {
            mockMvc.perform(get("/api/v1/places/discovery/search").header("Authorization", "Bearer " + token)
                            .param("query", "padaria").param("city", region[0]).param("state", region[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REGION"));
        }
        mockMvc.perform(get("/api/v1/places/discovery/search").header("Authorization", "Bearer " + token)
                        .param("query", "padaria").param("city", "Ponta Grossa").param("state", "PR")
                        .param("latitude", "-25.09").param("longitude", "-50.16"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REGION"));
        assertTrue(stubRequests.isEmpty(), "região inválida não gasta cota do provedor");
    }

    @Test
    @DisplayName("Chave recusada pelo provedor: 502 EXTERNAL_SERVICE_ERROR sem a chave no ProblemDetail")
    void providerRejectionIsControlled() throws Exception {
        String token = registerAndGetToken();

        MvcResult result = mockMvc.perform(get("/api/v1/places/discovery/search")
                        .header("Authorization", "Bearer " + token)
                        .param("query", "chave recusada"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_SERVICE_ERROR"))
                .andReturn();

        JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertFalse(problem.toString().contains(FAKE_KEY));
    }
}
