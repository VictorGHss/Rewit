package com.rewit.infrastructure.integration.google;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.PlaceCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("Testes do Adaptador Google Places (New) - MockRestServiceServer")
class GooglePlacesAdapterTest {

    private GooglePlacesProperties properties;
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private GooglePlacesAdapter adapter;

    @BeforeEach
    void setUp() {
        properties = new GooglePlacesProperties();
        properties.setApiKey("test-fake-api-key");
        properties.setBaseUrl("https://places.googleapis.com");
        properties.setConnectTimeoutMs(3000);
        properties.setReadTimeoutMs(5000);

        restClientBuilder = RestClient.builder().baseUrl(properties.getBaseUrl());
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        RestClient restClient = restClientBuilder.build();
        adapter = new GooglePlacesAdapter(properties, restClient);
    }


    @Test
    @DisplayName("Text Search: resposta válida com mapeamento de candidatos e verificação de headers")
    void searchByText_shouldReturnCandidatesOnValidResponseAndVerifyHeaders() {
        String mockResponseBody = """
                {
                  "places": [
                    {
                      "id": "ChIJN1t_tDeuEmsRUsoyG83frY4",
                      "types": ["restaurant", "food", "point_of_interest"],
                      "formattedAddress": "Av. Paulista, 1000 - Bela Vista, São Paulo - SP",
                      "location": {
                        "latitude": -23.565,
                        "longitude": -46.651
                      },
                      "displayName": {
                        "text": "Restaurante Paulistano",
                        "languageCode": "pt-BR"
                      },
                      "attributions": [
                        {
                          "provider": "OpenStreetMap",
                          "providerUri": "https://www.openstreetmap.org"
                        }
                      ]
                    },
                    {
                      "id": "ChIJb83frY4N1t_tDeuEmsRUsoy",
                      "types": ["cafe", "bakery"],
                      "formattedAddress": "Rua Augusta, 200 - Consolação, São Paulo - SP",
                      "location": {
                        "latitude": -23.551,
                        "longitude": -46.658
                      },
                      "displayName": {
                        "text": "Café Vintage",
                        "languageCode": "pt-BR"
                      }
                    }
                  ]
                }
                """;

        mockServer.expect(requestTo("https://places.googleapis.com/v1/places:searchText"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(GooglePlacesAdapter.HEADER_API_KEY, "test-fake-api-key"))
                .andExpect(header(GooglePlacesAdapter.HEADER_FIELD_MASK, GooglePlacesAdapter.FIELD_MASK_TEXT_SEARCH))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(jsonPath("$.maxResultCount").doesNotExist())
                .andRespond(withSuccess(mockResponseBody, MediaType.APPLICATION_JSON));

        List<PlaceCandidate> candidates = adapter.searchByText(
                "Restaurante Paulista", -23.565, -46.651, 5000.0, 10, "pt-BR"
        );

        mockServer.verify();
        assertNotNull(candidates);
        assertEquals(2, candidates.size());

        PlaceCandidate c1 = candidates.get(0);
        assertEquals("GOOGLE", c1.getProvider());
        assertEquals("ChIJN1t_tDeuEmsRUsoyG83frY4", c1.getExternalId());
        assertEquals("Restaurante Paulistano", c1.getDisplayName());
        assertEquals("Av. Paulista, 1000 - Bela Vista, São Paulo - SP", c1.getFormattedAddress());
        assertEquals(-23.565, c1.getLatitude());
        assertEquals(-46.651, c1.getLongitude());
        assertTrue(c1.getTypes().contains("restaurant"));
        assertEquals(1, c1.getAttributions().size());
        assertEquals("OpenStreetMap", c1.getAttributions().get(0).provider());
        assertEquals("https://www.openstreetmap.org", c1.getAttributions().get(0).providerUri());

        PlaceCandidate c2 = candidates.get(1);
        assertEquals("ChIJb83frY4N1t_tDeuEmsRUsoy", c2.getExternalId());
        assertEquals("Café Vintage", c2.getDisplayName());
        assertNotNull(c2.getAttributions());
        assertTrue(c2.getAttributions().isEmpty());
    }

    @Test
    @DisplayName("Text Search: comprovação explícita de pageSize presente, valor válido e maxResultCount ausente")
    void searchByText_shouldSendPageSizeAndNeverSendDeprecatedMaxResultCount() {
        mockServer.expect(requestTo("https://places.googleapis.com/v1/places:searchText"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.pageSize").value(15))
                .andExpect(jsonPath("$.maxResultCount").doesNotExist())
                .andRespond(withSuccess("{\"places\": []}", MediaType.APPLICATION_JSON));

        List<PlaceCandidate> candidates = adapter.searchByText(
                "Café", null, null, null, 15, "pt-BR"
        );

        mockServer.verify();
        assertNotNull(candidates);
    }

    @Test
    @DisplayName("Text Search: auditoria geográfica - sem coordenadas não deve enviar locationBias")
    void searchByText_withoutCoordinates_shouldNotSendLocationBias() {
        mockServer.expect(requestTo("https://places.googleapis.com/v1/places:searchText"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.locationBias").doesNotExist())
                .andRespond(withSuccess("{\"places\": []}", MediaType.APPLICATION_JSON));

        adapter.searchByText("Padaria", null, null, 10000.0, 10, "pt-BR");
        mockServer.verify();
    }

    @Test
    @DisplayName("Text Search: auditoria geográfica - raio acima de 50km deve ser limitado a 50000 metros")
    void searchByText_withExcessiveRadius_shouldCapAt50000Meters() {
        mockServer.expect(requestTo("https://places.googleapis.com/v1/places:searchText"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.locationBias.circle.center.latitude").value(-23.55))
                .andExpect(jsonPath("$.locationBias.circle.center.longitude").value(-46.63))
                .andExpect(jsonPath("$.locationBias.circle.radius").value(50000.0))
                .andRespond(withSuccess("{\"places\": []}", MediaType.APPLICATION_JSON));

        adapter.searchByText("Hotel", -23.55, -46.63, 100000.0, 10, "pt-BR");
        mockServer.verify();
    }

    @Test
    @DisplayName("Text Search: auditoria geográfica - raio ausente ou inválido (<= 0) deve usar default de 5000 metros")
    void searchByText_withInvalidRadius_shouldUseDefault5000Meters() {
        mockServer.expect(requestTo("https://places.googleapis.com/v1/places:searchText"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.locationBias.circle.radius").value(5000.0))
                .andRespond(withSuccess("{\"places\": []}", MediaType.APPLICATION_JSON));

        adapter.searchByText("Farmácia", -23.55, -46.63, -100.0, 10, "pt-BR");
        mockServer.verify();
    }

    @Test
    @DisplayName("Comprovação de FieldMask: as máscaras adotadas NÃO podem conter wildcard '*', vazias ou com espaços")
    void fieldMask_shouldExplicitlyNeverContainWildcardOrSpaces() {
        assertFalse(GooglePlacesAdapter.FIELD_MASK_TEXT_SEARCH.isBlank());
        assertFalse(GooglePlacesAdapter.FIELD_MASK_PLACE_DETAILS.isBlank());

        assertFalse(GooglePlacesAdapter.FIELD_MASK_TEXT_SEARCH.contains("*"),
                "FieldMask de Text Search não pode conter wildcard '*'");
        assertFalse(GooglePlacesAdapter.FIELD_MASK_PLACE_DETAILS.contains("*"),
                "FieldMask de Place Details não pode conter wildcard '*'");

        assertFalse(GooglePlacesAdapter.FIELD_MASK_TEXT_SEARCH.contains(" "),
                "FieldMask de Text Search não pode conter espaços");
        assertFalse(GooglePlacesAdapter.FIELD_MASK_PLACE_DETAILS.contains(" "),
                "FieldMask de Place Details não pode conter espaços");

        assertEquals("places.id,places.displayName,places.formattedAddress,places.location,places.types,places.attributions",
                GooglePlacesAdapter.FIELD_MASK_TEXT_SEARCH);
        assertEquals("id,displayName,formattedAddress,location,types,attributions",
                GooglePlacesAdapter.FIELD_MASK_PLACE_DETAILS);

        // Garantir que todos os campos de Text Search começam com 'places.'
        for (String field : GooglePlacesAdapter.FIELD_MASK_TEXT_SEARCH.split(",")) {
            assertTrue(field.startsWith("places."), "Campo de Text Search deve conter prefixo 'places.': " + field);
        }

        // Garantir que Place Details não usa o prefixo 'places.'
        for (String field : GooglePlacesAdapter.FIELD_MASK_PLACE_DETAILS.split(",")) {
            assertFalse(field.startsWith("places."), "Campo de Place Details não deve conter prefixo 'places.': " + field);
        }
    }

    @Test
    @DisplayName("Text Search: resposta com lista vazia de lugares deve retornar lista vazia")
    void searchByText_shouldReturnEmptyListWhenNoPlacesFound() {
        mockServer.expect(requestTo("https://places.googleapis.com/v1/places:searchText"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"places\": []}", MediaType.APPLICATION_JSON));

        List<PlaceCandidate> candidates = adapter.searchByText("Lugar Inexistente 999", null, null, null, 10, "pt-BR");

        mockServer.verify();
        assertNotNull(candidates);
        assertTrue(candidates.isEmpty());
    }

    @Test
    @DisplayName("Deve recusar chamadas quando a chave de API não estiver configurada no servidor")
    void searchByText_shouldThrowWhenApiKeyNotConfigured() {
        properties.setApiKey("");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                adapter.searchByText("Consulta", null, null, null, 10, "pt-BR")
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("EXTERNAL_SERVICE_NOT_CONFIGURED", ex.getErrorCode());
    }

    @Test
    @DisplayName("Text Search: erro 400 upstream do Google deve ser mapeado para EXTERNAL_SERVICE_ERROR sem vazar detalhes")
    void searchByText_shouldHandleUpstream400BadRequest() {
        mockServer.expect(requestTo("https://places.googleapis.com/v1/places:searchText"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withBadRequest().body("{\"error\": {\"message\": \"API key not valid\"}}"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                adapter.searchByText("Teste", null, null, null, 10, "pt-BR")
        );

        mockServer.verify();
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        assertEquals("EXTERNAL_SERVICE_ERROR", ex.getErrorCode());
        assertFalse(ex.getMessage().contains("test-fake-api-key"));
    }

    @Test
    @DisplayName("Text Search: erro 403 Forbidden do Google deve ser mapeado para EXTERNAL_SERVICE_ERROR")
    void searchByText_shouldHandleUpstream403Forbidden() {
        mockServer.expect(requestTo("https://places.googleapis.com/v1/places:searchText"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withRawStatus(403).body("{\"error\": {\"code\": 403}}"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                adapter.searchByText("Teste", null, null, null, 10, "pt-BR")
        );

        mockServer.verify();
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        assertEquals("EXTERNAL_SERVICE_ERROR", ex.getErrorCode());
    }

    @Test
    @DisplayName("Text Search: erro 429 Too Many Requests do Google deve ser mapeado para EXTERNAL_SERVICE_UNAVAILABLE")
    void searchByText_shouldHandleUpstream429RateLimit() {
        mockServer.expect(requestTo("https://places.googleapis.com/v1/places:searchText"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withRawStatus(429).body("{\"error\": \"Quota exceeded\"}"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                adapter.searchByText("Teste", null, null, null, 10, "pt-BR")
        );

        mockServer.verify();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("EXTERNAL_SERVICE_UNAVAILABLE", ex.getErrorCode());
    }

    @Test
    @DisplayName("Text Search: erro 500 Internal Server Error do Google deve ser mapeado para EXTERNAL_SERVICE_UNAVAILABLE")
    void searchByText_shouldHandleUpstream500Error() {
        mockServer.expect(requestTo("https://places.googleapis.com/v1/places:searchText"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError().body("{\"error\": \"Internal server error\"}"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                adapter.searchByText("Teste", null, null, null, 10, "pt-BR")
        );

        mockServer.verify();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("EXTERNAL_SERVICE_UNAVAILABLE", ex.getErrorCode());
    }

    @Test
    @DisplayName("Text Search: timeout de conexão/leitura deve ser mapeado para EXTERNAL_SERVICE_TIMEOUT")
    void searchByText_shouldHandleTimeout() {
        mockServer.expect(requestTo("https://places.googleapis.com/v1/places:searchText"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                adapter.searchByText("Teste", null, null, null, 10, "pt-BR")
        );

        mockServer.verify();
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, ex.getStatus());
        assertEquals("EXTERNAL_SERVICE_TIMEOUT", ex.getErrorCode());
    }


    @Test
    @DisplayName("Text Search: resposta com JSON inválido/malformado deve ser mapeado para EXTERNAL_SERVICE_INVALID_RESPONSE")
    void searchByText_shouldHandleMalformedJson() {
        mockServer.expect(requestTo("https://places.googleapis.com/v1/places:searchText"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{places: [not valid json...", MediaType.APPLICATION_JSON));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                adapter.searchByText("Teste", null, null, null, 10, "pt-BR")
        );

        mockServer.verify();
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        assertEquals("EXTERNAL_SERVICE_INVALID_RESPONSE", ex.getErrorCode());
    }

    @Test
    @DisplayName("Text Search: falha de resposta é logada só com a classe do erro, sem trecho do corpo nem mensagem do cliente HTTP")
    void searchByText_failureLogHasNoRawMessage() {
        mockServer.expect(requestTo("https://places.googleapis.com/v1/places:searchText"))
                .andRespond(withSuccess("{places: [conteudo-upstream-sensivel", MediaType.APPLICATION_JSON));
        Logger logger = (Logger) LoggerFactory.getLogger(GooglePlacesAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThrows(BusinessException.class, () -> adapter.searchByText("Teste", null, null, null, 10, "pt-BR"));
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(1, appender.list.size());
        String message = appender.list.getFirst().getFormattedMessage();
        assertTrue(message.contains("(erro="));
        assertFalse(message.contains("conteudo-upstream-sensivel"));
        assertFalse(message.contains("places.googleapis.com"));
        assertNull(appender.list.getFirst().getThrowableProxy());
    }

    @Test
    @DisplayName("Place Details: resposta válida com mapeamento e verificação de headers")
    void getDetails_shouldReturnCandidateOnValidResponseAndVerifyHeaders() {
        String mockDetailsResponse = """
                {
                  "id": "ChIJN1t_tDeuEmsRUsoyG83frY4",
                  "types": ["restaurant", "food"],
                  "formattedAddress": "Av. Paulista, 1000 - Bela Vista, São Paulo - SP",
                  "location": {
                    "latitude": -23.565,
                    "longitude": -46.651
                  },
                  "displayName": {
                    "text": "Restaurante Paulistano",
                    "languageCode": "pt-BR"
                  },
                  "attributions": [
                    {
                      "provider": "Portal de Dados Abertos",
                      "providerUri": "https://dados.gov.br"
                    }
                  ]
                }
                """;

        mockServer.expect(requestTo("https://places.googleapis.com/v1/places/ChIJN1t_tDeuEmsRUsoyG83frY4?languageCode=pt-BR"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(GooglePlacesAdapter.HEADER_API_KEY, "test-fake-api-key"))
                .andExpect(header(GooglePlacesAdapter.HEADER_FIELD_MASK, GooglePlacesAdapter.FIELD_MASK_PLACE_DETAILS))
                .andRespond(withSuccess(mockDetailsResponse, MediaType.APPLICATION_JSON));

        Optional<PlaceCandidate> candidateOpt = adapter.getDetails("ChIJN1t_tDeuEmsRUsoyG83frY4", "pt-BR");

        mockServer.verify();
        assertTrue(candidateOpt.isPresent());
        PlaceCandidate candidate = candidateOpt.get();
        assertEquals("GOOGLE", candidate.getProvider());
        assertEquals("ChIJN1t_tDeuEmsRUsoyG83frY4", candidate.getExternalId());
        assertEquals("Restaurante Paulistano", candidate.getDisplayName());
        assertEquals("Av. Paulista, 1000 - Bela Vista, São Paulo - SP", candidate.getFormattedAddress());
        assertEquals(-23.565, candidate.getLatitude());
        assertEquals(-46.651, candidate.getLongitude());
        assertEquals(1, candidate.getAttributions().size());
        assertEquals("Portal de Dados Abertos", candidate.getAttributions().get(0).provider());
        assertEquals("https://dados.gov.br", candidate.getAttributions().get(0).providerUri());
    }

    @Test
    @DisplayName("Place Details: 404 Not Found deve retornar Optional.empty()")
    void getDetails_shouldReturnEmptyOn404NotFound() {
        mockServer.expect(requestTo("https://places.googleapis.com/v1/places/inexistent_id?languageCode=pt-BR"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withRawStatus(404));

        Optional<PlaceCandidate> candidateOpt = adapter.getDetails("inexistent_id", "pt-BR");

        mockServer.verify();
        assertTrue(candidateOpt.isEmpty());
    }

    @Test
    @DisplayName("Place Details: múltiplas atribuições devem ser convertidas corretamente para a representação agnóstica")
    void getDetails_withMultipleAttributions_shouldMapAllCorrectly() {
        String mockDetailsResponse = """
                {
                  "id": "ChIJ_multi_attr",
                  "displayName": {"text": "Local com Atribuições"},
                  "attributions": [
                    {
                      "provider": "Fonte Alpha",
                      "providerUri": "https://alpha.example.com"
                    },
                    {
                      "provider": "Fonte Beta",
                      "providerUri": "https://beta.example.com"
                    }
                  ]
                }
                """;

        mockServer.expect(requestTo("https://places.googleapis.com/v1/places/ChIJ_multi_attr?languageCode=pt-BR"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(GooglePlacesAdapter.HEADER_FIELD_MASK, GooglePlacesAdapter.FIELD_MASK_PLACE_DETAILS))
                .andRespond(withSuccess(mockDetailsResponse, MediaType.APPLICATION_JSON));

        Optional<PlaceCandidate> candidateOpt = adapter.getDetails("ChIJ_multi_attr", "pt-BR");

        mockServer.verify();
        assertTrue(candidateOpt.isPresent());
        PlaceCandidate candidate = candidateOpt.get();
        assertEquals(2, candidate.getAttributions().size());
        assertEquals("Fonte Alpha", candidate.getAttributions().get(0).provider());
        assertEquals("https://alpha.example.com", candidate.getAttributions().get(0).providerUri());
        assertEquals("Fonte Beta", candidate.getAttributions().get(1).provider());
        assertEquals("https://beta.example.com", candidate.getAttributions().get(1).providerUri());
    }

    @Test
    @DisplayName("Place Details: ausência de campo de atribuição no JSON upstream deve resultar em lista vazia imutável (nunca null)")
    void getDetails_withoutAttributions_shouldReturnEmptyListNeverNull() {
        String mockDetailsResponse = """
                {
                  "id": "ChIJ_no_attr",
                  "displayName": {"text": "Local Sem Atribuição"}
                }
                """;

        mockServer.expect(requestTo("https://places.googleapis.com/v1/places/ChIJ_no_attr?languageCode=pt-BR"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(GooglePlacesAdapter.HEADER_FIELD_MASK, GooglePlacesAdapter.FIELD_MASK_PLACE_DETAILS))
                .andRespond(withSuccess(mockDetailsResponse, MediaType.APPLICATION_JSON));

        Optional<PlaceCandidate> candidateOpt = adapter.getDetails("ChIJ_no_attr", "pt-BR");

        mockServer.verify();
        assertTrue(candidateOpt.isPresent());
        PlaceCandidate candidate = candidateOpt.get();
        assertNotNull(candidate.getAttributions());
        assertTrue(candidate.getAttributions().isEmpty());
        assertThrows(UnsupportedOperationException.class, () ->
                candidate.getAttributions().add(new com.rewit.domain.model.PlaceAttribution("teste", "teste"))
        );
    }
}
