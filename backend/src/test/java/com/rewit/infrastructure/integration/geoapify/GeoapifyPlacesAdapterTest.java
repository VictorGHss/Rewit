package com.rewit.infrastructure.integration.geoapify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.BrazilianState;
import com.rewit.domain.model.PlaceAttribution;
import com.rewit.domain.model.PlaceCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("Adapter Geoapify (OpenStreetMap) - MockRestServiceServer")
class GeoapifyPlacesAdapterTest {

    private static final String KEY = "geoapify-chave-de-teste-nao-real";
    private static final String BASE = "https://api.geoapify.com";

    private GeoapifyProperties properties;
    private MockRestServiceServer mockServer;
    private GeoapifyPlacesAdapter adapter;

    @BeforeEach
    void setUp() {
        properties = new GeoapifyProperties();
        properties.setApiKey(KEY);
        properties.setBaseUrl(BASE);
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        adapter = new GeoapifyPlacesAdapter(properties, builder.build());
    }

    /** Parâmetros de query já decodificados (o matcher padrão compara os valores codificados). */
    private static Map<String, String> params(ClientHttpRequest request) {
        Map<String, String> decoded = new LinkedHashMap<>();
        UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams()
                .forEach((k, v) -> decoded.put(k, URLDecoder.decode(v.getFirst(), StandardCharsets.UTF_8)));
        return decoded;
    }

    @Test
    @DisplayName("Busca: só estabelecimentos com nome viram candidatos; nome, endereço, coordenadas, categorias, id e atribuições")
    void searchMapsAmenitiesAndDropsAddressesAndInvalidItems() {
        String body = """
                {
                  "results": [
                    {
                      "place_id": "51a1b2c3d4-padaria",
                      "name": "Padaria Estrela",
                      "formatted": "Padaria Estrela, Rua XV de Novembro, 100, Ponta Grossa - PR, Brasil",
                      "lat": -25.0945,
                      "lon": -50.1633,
                      "result_type": "amenity",
                      "category": "commercial.food_and_drink.bakery",
                      "city": "Ponta Grossa",
                      "state": "Paraná",
                      "state_code": "pr",
                      "country_code": "br",
                      "datasource": {
                        "sourcename": "openstreetmap",
                        "attribution": "© OpenStreetMap contributors",
                        "license": "Open Database License",
                        "url": "https://www.openstreetmap.org/copyright"
                      },
                      "rank": {"confidence": 0.9},
                      "opening_hours": "Mo-Sa 06:00-20:00"
                    },
                    {"place_id": "rua-1", "name": "Rua da Padaria", "result_type": "street", "lat": -25.1, "lon": -50.1},
                    {"place_id": "sem-nome", "result_type": "amenity", "formatted": "Rua X, 10", "lat": -25.1, "lon": -50.1},
                    {"place_id": "coordenada-invalida", "name": "Erro", "result_type": "amenity", "lat": 200.0, "lon": -50.1},
                    {"name": "Sem id", "result_type": "amenity", "lat": -25.1, "lon": -50.1}
                  ]
                }
                """;
        mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.SEARCH_PATH)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(request -> {
                    Map<String, String> p = params(request);
                    assertEquals("padaria em Ponta Grossa, Paraná, Brasil", p.get("text"));
                    assertEquals("amenity", p.get("type"));
                    assertEquals("json", p.get("format"));
                    assertEquals("pt", p.get("lang"));
                    assertEquals("5", p.get("limit"));
                    assertEquals("proximity:-50.1633,-25.0945", p.get("bias"));
                    assertEquals("circle:-50.1633,-25.0945,3000.0", p.get("filter"));
                    assertEquals(KEY, p.get("apiKey"));
                })
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<PlaceCandidate> candidates = adapter.searchByText(
                "padaria em Ponta Grossa, Paraná, Brasil", -25.0945, -50.1633, 3000.0, 5, "pt-BR");

        mockServer.verify();
        assertEquals(1, candidates.size());
        PlaceCandidate c = candidates.getFirst();
        assertEquals("GEOAPIFY", c.getProvider());
        assertEquals("51a1b2c3d4-padaria", c.getExternalId());
        assertEquals("Padaria Estrela", c.getDisplayName());
        assertEquals("Padaria Estrela, Rua XV de Novembro, 100, Ponta Grossa - PR, Brasil", c.getFormattedAddress());
        assertEquals(-25.0945, c.getLatitude());
        assertEquals(-50.1633, c.getLongitude());
        assertEquals(List.of("commercial.food_and_drink.bakery"), c.getTypes());
        assertEquals("Ponta Grossa", c.getCity());
        assertEquals("PR", c.getState(), "state_code tem prioridade e vira UF maiúscula");
        assertEquals("BR", c.getCountryCode());
        assertEquals(List.of(GeoapifyPlacesAdapter.GEOAPIFY_ATTRIBUTION,
                new PlaceAttribution("© OpenStreetMap contributors", "https://www.openstreetmap.org/copyright")),
                c.getAttributions());
    }

    @Test
    @DisplayName("Busca sem localização não envia bias nem filtro; raio acima de 50 km é limitado")
    void searchWithoutLocationAndRadiusCap() {
        mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.SEARCH_PATH)))
                .andExpect(request -> {
                    Map<String, String> p = params(request);
                    assertFalse(p.containsKey("bias"));
                    assertFalse(p.containsKey("filter"));
                    assertEquals("10", p.get("limit"), "limite fora de 1..20 vira o padrão");
                })
                .andRespond(withSuccess("{\"results\": []}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.SEARCH_PATH)))
                .andExpect(request -> {
                    Map<String, String> p = params(request);
                    assertEquals("proximity:-50.0,-25.0", p.get("bias"));
                    assertEquals("circle:-50.0,-25.0,50000.0", p.get("filter"));
                })
                .andRespond(withSuccess("{\"results\": []}", MediaType.APPLICATION_JSON));

        assertTrue(adapter.searchByText("Café do Ponto", null, null, null, 99, "pt-BR").isEmpty());
        assertTrue(adapter.searchByText("Café do Ponto", -25.0, -50.0, 900_000.0, 5, null).isEmpty());
        mockServer.verify();
    }

    @Test
    @DisplayName("Busca: lista vazia, corpo sem results e 404 respondem lista vazia")
    void emptyResponses() {
        mockServer.expect(requestTo(startsWith(BASE))).andRespond(withSuccess("{\"results\": []}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(startsWith(BASE))).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertTrue(adapter.searchByText("nada", null, null, null, 5, "pt-BR").isEmpty());
        assertTrue(adapter.searchByText("nada", null, null, null, 5, "pt-BR").isEmpty());
        assertTrue(adapter.searchByText("nada", null, null, null, 5, "pt-BR").isEmpty());
        mockServer.verify();
    }

    @Test
    @DisplayName("Sem chave: 503 EXTERNAL_SERVICE_NOT_CONFIGURED antes de qualquer requisição")
    void missingKeyIsControlledAndSendsNothing() {
        properties.setApiKey("  ");
        BusinessException search = assertThrows(BusinessException.class,
                () -> adapter.searchByText("padaria", null, null, null, 5, "pt-BR"));
        properties.setApiKey("change-me");
        BusinessException details = assertThrows(BusinessException.class, () -> adapter.getDetails("id", "pt-BR"));

        mockServer.verify();
        for (BusinessException ex : List.of(search, details)) {
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
            assertEquals("EXTERNAL_SERVICE_NOT_CONFIGURED", ex.getErrorCode());
        }
    }

    @Test
    @DisplayName("Erros HTTP: credencial recusada 502, cota/limite e 5xx 503, timeout 504, rede 502, payload malformado 502")
    void httpFailuresAreMappedToControlledErrors() {
        mockServer.expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        mockServer.expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.FORBIDDEN));
        mockServer.expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        mockServer.expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        mockServer.expect(requestTo(startsWith(BASE))).andRespond(withException(new SocketTimeoutException("Read timed out")));
        mockServer.expect(requestTo(startsWith(BASE))).andRespond(withException(new java.net.ConnectException("refused")));
        mockServer.expect(requestTo(startsWith(BASE))).andRespond(withSuccess("{\"results\": [ {", MediaType.APPLICATION_JSON));

        assertError(HttpStatus.BAD_GATEWAY, "EXTERNAL_SERVICE_ERROR", this::search);
        assertError(HttpStatus.BAD_GATEWAY, "EXTERNAL_SERVICE_ERROR", this::search);
        assertError(HttpStatus.SERVICE_UNAVAILABLE, "EXTERNAL_SERVICE_UNAVAILABLE", this::search);
        assertError(HttpStatus.SERVICE_UNAVAILABLE, "EXTERNAL_SERVICE_UNAVAILABLE", this::search);
        assertError(HttpStatus.GATEWAY_TIMEOUT, "EXTERNAL_SERVICE_TIMEOUT", this::search);
        assertError(HttpStatus.BAD_GATEWAY, "EXTERNAL_SERVICE_UNAVAILABLE", this::search);
        assertError(HttpStatus.BAD_GATEWAY, "EXTERNAL_SERVICE_INVALID_RESPONSE", this::search);
        mockServer.verify();
    }

    @Test
    @DisplayName("Nenhum log nem mensagem de erro contém a chave, a URL ou a query enviada ao Geoapify")
    void keyNeverLeaksIntoLogsOrErrors() {
        mockServer.expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        mockServer.expect(requestTo(startsWith(BASE))).andRespond(withException(new SocketTimeoutException("Read timed out")));
        mockServer.expect(requestTo(startsWith(BASE))).andRespond(withException(new java.net.ConnectException("refused")));
        mockServer.expect(requestTo(startsWith(BASE))).andRespond(withSuccess("{malformado", MediaType.APPLICATION_JSON));

        Logger logger = (Logger) LoggerFactory.getLogger(GeoapifyPlacesAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        List<BusinessException> errors = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                errors.add(assertThrows(BusinessException.class, this::search));
            }
        } finally {
            logger.detachAppender(appender);
        }
        mockServer.verify();

        assertEquals(4, appender.list.size());
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            assertFalse(message.contains(KEY), message);
            assertFalse(message.contains("apiKey"), message);
            assertFalse(message.contains("api.geoapify.com"), message);
            assertFalse(message.contains("padaria"), message);
            assertEquals(null, event.getThrowableProxy(), "sem stack trace com a URL");
        }
        for (BusinessException ex : errors) {
            assertFalse(ex.getMessage().contains(KEY));
            assertFalse(ex.getMessage().contains("apiKey"));
        }
    }

    @Test
    @DisplayName("Detalhes: feature details vira candidato; 404, feature sem nome e lista vazia respondem vazio")
    void detailsMapping() {
        String details = """
                {
                  "type": "FeatureCollection",
                  "features": [
                    {
                      "type": "Feature",
                      "properties": {
                        "feature_type": "details",
                        "place_id": "51a1b2c3d4-padaria",
                        "name": "Padaria Estrela",
                        "formatted": "Rua XV de Novembro, 100, Ponta Grossa - PR",
                        "lat": -25.0945,
                        "lon": -50.1633,
                        "categories": ["commercial", "commercial.food_and_drink.bakery"],
                        "city": "Ponta Grossa",
                        "state": "Paraná"
                      },
                      "geometry": {"type": "Point", "coordinates": [-50.1633, -25.0945]}
                    }
                  ]
                }
                """;
        mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.DETAILS_PATH)))
                .andExpect(request -> {
                    Map<String, String> p = params(request);
                    assertEquals("51a1b2c3d4-padaria", p.get("id"));
                    assertEquals("details", p.get("features"));
                    assertEquals("pt", p.get("lang"));
                    assertEquals(KEY, p.get("apiKey"));
                })
                .andRespond(withSuccess(details, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.NOT_FOUND));
        mockServer.expect(requestTo(startsWith(BASE))).andRespond(withSuccess(
                "{\"features\": [{\"properties\": {\"feature_type\": \"details\", \"place_id\": \"x\"}}]}",
                MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(startsWith(BASE))).andRespond(withSuccess("{\"features\": []}", MediaType.APPLICATION_JSON));

        Optional<PlaceCandidate> found = adapter.getDetails(" 51a1b2c3d4-padaria ", "pt-BR");
        assertTrue(found.isPresent());
        assertEquals("Padaria Estrela", found.get().getDisplayName());
        assertEquals(List.of("commercial", "commercial.food_and_drink.bakery"), found.get().getTypes());
        assertEquals("Ponta Grossa", found.get().getCity());
        assertNull(found.get().getState(), "sem país não há como validar a UF: o estado fica nulo");
        assertNull(found.get().getCountryCode(), "país ausente fica nulo, sem inventar valor");
        assertEquals(GeoapifyPlacesAdapter.OSM_ATTRIBUTION, found.get().getAttributions().get(1),
                "sem datasource na resposta, a atribuição do OSM é mantida");

        assertTrue(adapter.getDetails("inexistente", "pt-BR").isEmpty());
        assertTrue(adapter.getDetails("sem-nome", "pt-BR").isEmpty());
        assertTrue(adapter.getDetails("vazio", "pt-BR").isEmpty());
        mockServer.verify();
    }

    @Test
    @DisplayName("Idioma: pt-BR, pt_BR e vazio viram pt; códigos de 2 letras são mantidos")
    void languageNormalization() {
        assertEquals("pt", GeoapifyPlacesAdapter.toIsoLanguage("pt-BR"));
        assertEquals("pt", GeoapifyPlacesAdapter.toIsoLanguage("pt_BR"));
        assertEquals("pt", GeoapifyPlacesAdapter.toIsoLanguage(null));
        assertEquals("en", GeoapifyPlacesAdapter.toIsoLanguage("EN"));
        assertEquals("pt", GeoapifyPlacesAdapter.toIsoLanguage("portuguese"));
    }

    @ParameterizedTest(name = "pais={0}, state_code={1}, state={2} -> {3}")
    @CsvSource(nullValues = "NULL", value = {
            "br, PR, Paraná, PR",
            "BR, pr, NULL, PR",
            "br, NULL, Paraná, PR",
            "br, NULL, parana, PR",
            "br, NULL, Sul, NULL",
            "br, NULL, Sudeste, NULL",
            "br, NULL, NULL, NULL",
            "br, XX, Sul, NULL",
            "br, XX, Paraná, PR",
            "us, CA, California, CA",
            "ar, NULL, Córdoba, Córdoba",
            "NULL, PR, Paraná, NULL"
    })
    @DisplayName("UF: só as 27 siglas oficiais no Brasil; regiões e códigos desconhecidos ficam nulos; outros países sem regra de UF")
    void stateMapping(String country, String stateCode, String stateName, String expected) {
        assertEquals(expected, GeoapifyPlacesAdapter.stateFor(country, stateCode, stateName));
    }

    @Test
    @DisplayName("Região \"Sul\" sem state_code: estado nulo, demais campos e atribuições preservados")
    void regionNameIsNeverMappedToState() {
        mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.SEARCH_PATH)))
                .andRespond(withSuccess("""
                        {"results": [{
                          "place_id": "51c39b-super-pao", "name": "Padaria Super Pão", "result_type": "amenity",
                          "formatted": "Padaria Super Pão, Avenida Brasil, Cambará - Sul, 86390-000, Brasil",
                          "lat": -23.043, "lon": -50.073, "city": "Cambará", "state": "Sul", "country_code": "br"
                        }]}
                        """, MediaType.APPLICATION_JSON));

        PlaceCandidate c = adapter.searchByText("padaria", null, null, null, 5, "pt-BR").getFirst();

        mockServer.verify();
        assertNull(c.getState(), "\"Sul\" é região, não UF");
        assertEquals("Padaria Super Pão", c.getDisplayName());
        assertEquals("Cambará", c.getCity());
        assertEquals("BR", c.getCountryCode());
        assertEquals(-23.043, c.getLatitude());
        assertEquals(List.of(GeoapifyPlacesAdapter.GEOAPIFY_ATTRIBUTION, GeoapifyPlacesAdapter.OSM_ATTRIBUTION),
                c.getAttributions());
    }

    @Test
    @DisplayName("Coordenadas sem raio aplicam filtro circular padrão de 10 km, não só viés")
    void coordinatesWithoutRadiusUseDefaultCircleFilter() {
        mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.SEARCH_PATH)))
                .andExpect(request -> {
                    Map<String, String> p = params(request);
                    assertEquals("circle:-50.16,-25.09,10000.0", p.get("filter"));
                    assertEquals("proximity:-50.16,-25.09", p.get("bias"));
                })
                .andRespond(withSuccess("{\"results\": []}", MediaType.APPLICATION_JSON));

        adapter.searchByText("Padaria do Tião", -25.09, -50.16, null, 5, "pt-BR");
        mockServer.verify();
    }

    private static final String PONTA_GROSSA_CITY = """
            {"results": [
              {"place_id": "51-ponta-grossa-sc", "name": "Ponta Grossa", "city": "Ponta Grossa", "result_type": "city",
               "lat": -27.0, "lon": -49.0, "state": "Santa Catarina", "state_code": "SC", "country_code": "br"},
              {"place_id": "51-ponta-grossa-pr", "name": "Ponta Grossa", "city": "Ponta Grossa", "result_type": "city",
               "lat": -25.0945, "lon": -50.1633, "state": "Paraná", "state_code": "PR", "country_code": "br"}
            ]}
            """;

    @Test
    @DisplayName("Cidade com UF: resolve a cidade uma vez e filtra a busca pela fronteira dela (filter=place)")
    void citySearchFiltersByCityBoundaryAndCachesResolution() {
        mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.SEARCH_PATH)))
                .andExpect(request -> {
                    Map<String, String> p = params(request);
                    assertEquals("Ponta Grossa", p.get("city"));
                    assertEquals("Paraná", p.get("state"));
                    assertEquals("Brasil", p.get("country"));
                    assertEquals("city", p.get("type"));
                    assertEquals("countrycode:br", p.get("filter"));
                    assertFalse(p.containsKey("text"), "a resolução da cidade é estruturada, sem texto livre");
                })
                .andRespond(withSuccess(PONTA_GROSSA_CITY, MediaType.APPLICATION_JSON));
        for (int i = 0; i < 2; i++) {
            mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.SEARCH_PATH)))
                    .andExpect(request -> {
                        Map<String, String> p = params(request);
                        assertEquals("Padaria Junior", p.get("text"));
                        assertEquals("amenity", p.get("type"));
                        assertEquals("place:51-ponta-grossa-pr", p.get("filter"), "fronteira da cidade da UF pedida");
                        assertEquals("proximity:-50.1633,-25.0945", p.get("bias"));
                        assertEquals(KEY, p.get("apiKey"));
                    })
                    .andRespond(withSuccess("""
                            {"results": [{"place_id": "51-raikoski", "name": "Padaria Junior Raikoski",
                              "result_type": "amenity", "formatted": "Rua Conrado Schiffer, Ponta Grossa - PR",
                              "lat": -25.108, "lon": -50.169, "city": "Ponta Grossa", "state": "Paraná",
                              "state_code": "PR", "country_code": "br"}]}
                            """, MediaType.APPLICATION_JSON));
        }

        List<PlaceCandidate> first = adapter.searchByTextInCity("Padaria Junior", "Ponta Grossa", BrazilianState.PR, 5, "pt-BR");
        // Mesma cidade com outra grafia: reaproveita a resolução, só a busca vai ao provedor
        List<PlaceCandidate> second = adapter.searchByTextInCity("Padaria Junior", "PONTA GROSSA", BrazilianState.PR, 5, "pt-BR");

        mockServer.verify();
        assertEquals("Padaria Junior Raikoski", first.getFirst().getDisplayName());
        assertEquals("PR", first.getFirst().getState());
        assertEquals(1, second.size());
    }

    @Test
    @DisplayName("Cidade não encontrada na UF: 422 REGION_NOT_FOUND sem buscar em outra cidade")
    void cityNotFoundInStateIsRejectedWithoutSearching() {
        mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.SEARCH_PATH)))
                .andRespond(withSuccess(PONTA_GROSSA_CITY, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.SEARCH_PATH)))
                .andRespond(withSuccess("""
                        {"results": [{"place_id": "51-ponta-pora", "name": "Ponta Porã", "city": "Ponta Porã",
                          "result_type": "city", "lat": -22.5, "lon": -55.7, "state_code": "MS", "country_code": "br"},
                          {"place_id": "51-bairro", "name": "Ponta Grossa", "result_type": "suburb",
                          "lat": -22.5, "lon": -55.7, "state_code": "MS", "country_code": "br"}]}
                        """, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.SEARCH_PATH)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertError(HttpStatus.UNPROCESSABLE_CONTENT, "REGION_NOT_FOUND",
                () -> adapter.searchByTextInCity("padaria", "Ponta Grossa", BrazilianState.RS, 5, "pt-BR"));
        assertError(HttpStatus.UNPROCESSABLE_CONTENT, "REGION_NOT_FOUND",
                () -> adapter.searchByTextInCity("padaria", "Ponta Grossa", BrazilianState.MS, 5, "pt-BR"));
        assertError(HttpStatus.UNPROCESSABLE_CONTENT, "REGION_NOT_FOUND",
                () -> adapter.searchByTextInCity("padaria", "Cidade Inexistente", BrazilianState.PR, 5, "pt-BR"));
        // Nenhuma busca de estabelecimento foi feita: só as três resoluções esperadas
        mockServer.verify();
    }

    @Test
    @DisplayName("Falha do provedor na resolução da cidade é mapeada como na busca, sem vazar a chave")
    void cityResolutionFailuresAreControlled() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(GeoapifyPlacesAdapter.class);
        logger.addAppender(appender);
        try {
            mockServer.expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
            mockServer.expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

            assertError(HttpStatus.SERVICE_UNAVAILABLE, "EXTERNAL_SERVICE_UNAVAILABLE",
                    () -> adapter.searchByTextInCity("padaria", "Ponta Grossa", BrazilianState.PR, 5, "pt-BR"));
            BusinessException denied = assertThrows(BusinessException.class,
                    () -> adapter.searchByTextInCity("padaria", "Ponta Grossa", BrazilianState.PR, 5, "pt-BR"));
            assertEquals("EXTERNAL_SERVICE_ERROR", denied.getErrorCode());
            assertFalse(denied.getMessage().contains(KEY));
            mockServer.verify();
            assertTrue(appender.list.stream().map(ILoggingEvent::getFormattedMessage).noneMatch(m -> m.contains(KEY)));
        } finally {
            logger.detachAppender(appender);
        }
    }

    /** Resposta da Places API: FeatureCollection, país e estado por nome (sem códigos), um item repetido e um sem nome. */
    private static final String BAKERIES_PLACES = """
            {"type": "FeatureCollection", "features": [
              {"type": "Feature", "properties": {"place_id": "51-raikoski", "name": "Padaria Junior Raikoski",
                "formatted": "Rua Conrado Schiffer, Ponta Grossa - PR", "lat": -25.108, "lon": -50.169,
                "city": "Ponta Grossa", "state": "Paraná", "country": "Brasil",
                "categories": ["commercial", "commercial.food_and_drink", "commercial.food_and_drink.bakery"]}},
              {"type": "Feature", "properties": {"place_id": "51-pao-quente", "name": "Pão Quente",
                "formatted": "Rua Balduíno Taques, Ponta Grossa - PR", "lat": -25.095, "lon": -50.161,
                "city": "Ponta Grossa", "state": "Paraná", "country": "Brazil",
                "categories": ["commercial.food_and_drink.bakery"]}},
              {"type": "Feature", "properties": {"place_id": "51-raikoski", "name": "Padaria Junior Raikoski",
                "lat": -25.108, "lon": -50.169}},
              {"type": "Feature", "properties": {"place_id": "51-sem-nome", "lat": -25.1, "lon": -50.1}}
            ]}
            """;

    @Test
    @DisplayName("Categoria perto de mim: Places API com categoria oficial, filtro circular, viés e só lugares com nome")
    void categoryNearbyUsesPlacesApiWithCircleFilter() {
        mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.PLACES_PATH)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(request -> {
                    Map<String, String> p = params(request);
                    assertEquals("commercial.food_and_drink.bakery", p.get("categories"));
                    assertEquals("named", p.get("conditions"));
                    assertEquals("circle:-50.16,-25.09,10000.0", p.get("filter"));
                    assertEquals("proximity:-50.16,-25.09", p.get("bias"));
                    assertEquals("5", p.get("limit"));
                    assertEquals("pt", p.get("lang"));
                    assertFalse(p.containsKey("name"), "categoria não é reduzida aos nomes com a palavra pesquisada");
                    assertFalse(p.containsKey("text"));
                    assertEquals(KEY, p.get("apiKey"));
                })
                .andRespond(withSuccess(BAKERIES_PLACES, MediaType.APPLICATION_JSON));

        List<PlaceCandidate> results = adapter.searchByText("Padarias", -25.09, -50.16, 10_000.0, 5, "pt-BR");

        mockServer.verify();
        assertEquals(List.of("51-raikoski", "51-pao-quente"), results.stream().map(PlaceCandidate::getExternalId).toList(),
                "deduplicado pelo id externo, na ordem do provedor, sem o lugar sem nome");
        PlaceCandidate semPalavraNoNome = results.get(1);
        assertEquals("Pão Quente", semPalavraNoNome.getDisplayName(), "padaria sem 'padaria' no nome também aparece");
        assertEquals("PR", semPalavraNoNome.getState(), "país e estado por nome: Brazil + Paraná viram BR/PR");
        assertEquals("BR", semPalavraNoNome.getCountryCode());
        assertEquals(List.of(GeoapifyPlacesAdapter.GEOAPIFY_ATTRIBUTION, GeoapifyPlacesAdapter.OSM_ATTRIBUTION),
                semPalavraNoNome.getAttributions());
    }

    @Test
    @DisplayName("Categoria na cidade: Places API limitada à fronteira da cidade (filter=place)")
    void categoryInCityUsesPlacesApiWithCityBoundary() {
        mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.SEARCH_PATH)))
                .andRespond(withSuccess(PONTA_GROSSA_CITY, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.PLACES_PATH)))
                .andExpect(request -> {
                    Map<String, String> p = params(request);
                    assertEquals("commercial.food_and_drink.bakery", p.get("categories"));
                    assertEquals("place:51-ponta-grossa-pr", p.get("filter"));
                    assertEquals("proximity:-50.1633,-25.0945", p.get("bias"));
                })
                .andRespond(withSuccess(BAKERIES_PLACES, MediaType.APPLICATION_JSON));

        List<PlaceCandidate> results = adapter.searchByTextInCity("padaria", "Ponta Grossa", BrazilianState.PR, 10, "pt-BR");

        mockServer.verify();
        assertEquals(2, results.size());
    }

    @Test
    @DisplayName("Nome próprio na cidade: busca textual pelo nome, sem reduzir a uma categoria genérica")
    void specificNameUsesTextSearch() {
        mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.SEARCH_PATH)))
                .andRespond(withSuccess(PONTA_GROSSA_CITY, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.SEARCH_PATH)))
                .andExpect(request -> {
                    Map<String, String> p = params(request);
                    assertEquals("Padaria do Tião", p.get("text"));
                    assertEquals("amenity", p.get("type"));
                    assertEquals("place:51-ponta-grossa-pr", p.get("filter"));
                    assertFalse(p.containsKey("categories"));
                })
                .andRespond(withSuccess("{\"results\": []}", MediaType.APPLICATION_JSON));

        assertTrue(adapter.searchByTextInCity("Padaria do Tião", "Ponta Grossa", BrazilianState.PR, 10, "pt-BR").isEmpty());
        mockServer.verify();
    }

    @Test
    @DisplayName("Categoria sem região: Places exige área, então a busca é textual e sem restrição geográfica")
    void categoryWithoutRegionFallsBackToTextSearch() {
        mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.SEARCH_PATH)))
                .andExpect(request -> {
                    Map<String, String> p = params(request);
                    assertEquals("padaria", p.get("text"));
                    assertFalse(p.containsKey("filter"));
                    assertFalse(p.containsKey("bias"));
                })
                .andRespond(withSuccess("""
                        {"results": [
                          {"place_id": "51-a", "name": "Padaria A", "result_type": "amenity", "lat": -25.1, "lon": -50.1},
                          {"place_id": "51-a", "name": "Padaria A", "result_type": "amenity", "lat": -25.1, "lon": -50.1}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<PlaceCandidate> results = adapter.searchByText("padaria", null, null, null, 10, "pt-BR");

        mockServer.verify();
        assertEquals(1, results.size(), "duplicados pelo id externo também são removidos na busca textual");
    }

    @Test
    @DisplayName("Places API: vazio, 404, cota, chave recusada e timeout são controlados, sem vazar a chave")
    void placesFailuresAreControlled() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(GeoapifyPlacesAdapter.class);
        logger.addAppender(appender);
        try {
            mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.PLACES_PATH)))
                    .andRespond(withSuccess("{\"type\": \"FeatureCollection\", \"features\": []}", MediaType.APPLICATION_JSON));
            mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.PLACES_PATH)))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND));
            mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.PLACES_PATH)))
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
            mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.PLACES_PATH)))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
            mockServer.expect(requestTo(startsWith(BASE + GeoapifyPlacesAdapter.PLACES_PATH)))
                    .andRespond(withException(new SocketTimeoutException("Read timed out")));

            assertTrue(adapter.searchByText("farmácia", -25.09, -50.16, 3000.0, 5, "pt-BR").isEmpty());
            assertTrue(adapter.searchByText("farmácia", -25.09, -50.16, 3000.0, 5, "pt-BR").isEmpty());
            assertError(HttpStatus.SERVICE_UNAVAILABLE, "EXTERNAL_SERVICE_UNAVAILABLE",
                    () -> adapter.searchByText("farmácia", -25.09, -50.16, 3000.0, 5, "pt-BR"));
            BusinessException denied = assertThrows(BusinessException.class,
                    () -> adapter.searchByText("farmácia", -25.09, -50.16, 3000.0, 5, "pt-BR"));
            assertEquals("EXTERNAL_SERVICE_ERROR", denied.getErrorCode());
            assertFalse(denied.getMessage().contains(KEY));
            assertError(HttpStatus.GATEWAY_TIMEOUT, "EXTERNAL_SERVICE_TIMEOUT",
                    () -> adapter.searchByText("farmácia", -25.09, -50.16, 3000.0, 5, "pt-BR"));
            mockServer.verify();
            assertTrue(appender.list.stream().map(ILoggingEvent::getFormattedMessage).noneMatch(m -> m.contains(KEY)));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = ';', nullValues = "NULL", value = {
            "padaria; commercial.food_and_drink.bakery",
            "  PADARIAS ; commercial.food_and_drink.bakery",
            "Panificadora; commercial.food_and_drink.bakery",
            "farmácia; healthcare.pharmacy,commercial.health_and_beauty.pharmacy",
            "Drogaria; healthcare.pharmacy,commercial.health_and_beauty.pharmacy",
            "restaurantes; catering.restaurant",
            "Café; catering.cafe",
            "supermercado; commercial.supermarket",
            "Padaria do Tião; NULL",
            "padaria em Ponta Grossa; NULL",
            "pizzaria napolitana; NULL"
    })
    @DisplayName("Termos e sinônimos do tipo de estabelecimento viram categorias oficiais; nomes próprios não")
    void termToCategoryMapping(String term, String expected) {
        assertEquals(expected == null ? null : List.of(expected.split(",")),
                GeoapifyCategories.forQuery(term).orElse(null));
    }

    private void search() {
        adapter.searchByText("padaria", null, null, null, 5, "pt-BR");
    }

    private static void assertError(HttpStatus status, String code, Executable action) {
        BusinessException ex = assertThrows(BusinessException.class, action);
        assertEquals(status, ex.getStatus());
        assertEquals(code, ex.getErrorCode());
    }
}
