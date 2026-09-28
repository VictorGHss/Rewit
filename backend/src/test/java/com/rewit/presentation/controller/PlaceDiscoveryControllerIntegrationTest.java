package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceDiscoveryPort;
import com.rewit.application.port.PlaceRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.PlaceCandidate;
import com.rewit.presentation.dto.auth.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração da API de Descoberta /api/v1/places/discovery (Step 8)")
class PlaceDiscoveryControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PlaceRepository placeRepository;

    @MockitoBean
    private PlaceDiscoveryPort placeDiscoveryPort;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private String registerAndGetToken() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "discovery." + suffix + "@rewit.com";
        String password = "Password@" + suffix;

        RegisterRequest req = new RegisterRequest(email, password, "@disc_" + suffix, "Discovery Tester " + suffix);
        MvcResult res = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        return node.get("accessToken").asText();
    }

    @Test
    @DisplayName("1. Busca autenticada funciona e retorna candidatos mapeados")
    void search_authenticatedShouldReturnCandidates() throws Exception {
        String token = registerAndGetToken();

        PlaceCandidate candidate1 = new PlaceCandidate(
                "GOOGLE", "ChIJ_mock_1", "Café do Ponto", "Rua XV de Novembro, 500", -25.0916, -50.1581, List.of("cafe", "food")
        );
        PlaceCandidate candidate2 = new PlaceCandidate(
                "GOOGLE", "ChIJ_mock_2", "Livraria & Café", "Rua Balduíno Taques, 800", -25.0930, -50.1600, List.of("book_store", "cafe")
        );

        when(placeDiscoveryPort.searchByText(eq("cafe"), any(), any(), any(), eq(10), eq("pt-BR")))
                .thenReturn(List.of(candidate1, candidate2));

        mockMvc.perform(get("/api/v1/places/discovery/search")
                        .header("Authorization", "Bearer " + token)
                        .param("query", "cafe")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].provider").value("GOOGLE"))
                .andExpect(jsonPath("$[0].externalId").value("ChIJ_mock_1"))
                .andExpect(jsonPath("$[0].displayName").value("Café do Ponto"))
                .andExpect(jsonPath("$[0].formattedAddress").value("Rua XV de Novembro, 500"))
                .andExpect(jsonPath("$[0].latitude").value(-25.0916))
                .andExpect(jsonPath("$[0].longitude").value(-50.1581))
                .andExpect(jsonPath("$[0].types", hasItem("cafe")))
                .andExpect(jsonPath("$[1].externalId").value("ChIJ_mock_2"));
    }

    @Test
    @DisplayName("2. Busca sem JWT retorna 401 Unauthorized")
    void search_unauthenticatedShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/places/discovery/search")
                        .param("query", "McDonalds"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("3. Query vazia ou em branco retorna 400 Bad Request")
    void search_emptyQueryShouldReturn400() throws Exception {
        String token = registerAndGetToken();

        mockMvc.perform(get("/api/v1/places/discovery/search")
                        .header("Authorization", "Bearer " + token)
                        .param("query", "   "))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("4. Limite inválido (menor que 1 ou maior que 20) retorna 400 Bad Request")
    void search_invalidLimitShouldReturn400() throws Exception {
        String token = registerAndGetToken();

        // Limite 0
        mockMvc.perform(get("/api/v1/places/discovery/search")
                        .header("Authorization", "Bearer " + token)
                        .param("query", "restaurante")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest());

        // Limite 50
        mockMvc.perform(get("/api/v1/places/discovery/search")
                        .header("Authorization", "Bearer " + token)
                        .param("query", "restaurante")
                        .param("limit", "50"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("5. Detalhes autenticados funcionam para provider GOOGLE e externalId existente")
    void getDetails_authenticatedShouldReturnCandidate() throws Exception {
        String token = registerAndGetToken();

        PlaceCandidate candidate = new PlaceCandidate(
                "GOOGLE", "ChIJ_detail_123", "Pizzaria Florença", "Av. Bonifácio Vilela, 120", -25.0880, -50.1550, List.of("restaurant", "pizza")
        );

        when(placeDiscoveryPort.getDetails("ChIJ_detail_123", "pt-BR")).thenReturn(Optional.of(candidate));

        mockMvc.perform(get("/api/v1/places/discovery/GOOGLE/ChIJ_detail_123")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("GOOGLE"))
                .andExpect(jsonPath("$.externalId").value("ChIJ_detail_123"))
                .andExpect(jsonPath("$.displayName").value("Pizzaria Florença"))
                .andExpect(jsonPath("$.formattedAddress").value("Av. Bonifácio Vilela, 120"))
                .andExpect(jsonPath("$.latitude").value(-25.0880))
                .andExpect(jsonPath("$.longitude").value(-50.1550));
    }

    @Test
    @DisplayName("6. Provider diferente de GOOGLE é rejeitado com 400 Bad Request")
    void getDetails_unsupportedProviderShouldReturn400() throws Exception {
        String token = registerAndGetToken();

        mockMvc.perform(get("/api/v1/places/discovery/FOURSQUARE/fsq_12345")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_PROVIDER"));
    }

    @Test
    @DisplayName("7. ExternalId inexistente resulta em erro controlado 404 Not Found")
    void getDetails_nonExistentExternalIdShouldReturn404() throws Exception {
        String token = registerAndGetToken();

        when(placeDiscoveryPort.getDetails("ChIJ_not_found", "pt-BR")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/places/discovery/GOOGLE/ChIJ_not_found")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXTERNAL_PLACE_NOT_FOUND"));
    }

    @Test
    @DisplayName("8. Erro do Google não vaza detalhes internos, credenciais ou stack trace")
    void search_googleErrorShouldNotLeakInternalDetails() throws Exception {
        String token = registerAndGetToken();

        when(placeDiscoveryPort.searchByText(anyString(), any(), any(), any(), anyInt(), anyString()))
                .thenThrow(new BusinessException(
                        "Falha na comunicação com o serviço externo de lugares",
                        HttpStatus.BAD_GATEWAY,
                        "EXTERNAL_SERVICE_ERROR"
                ));

        MvcResult result = mockMvc.perform(get("/api/v1/places/discovery/search")
                        .header("Authorization", "Bearer " + token)
                        .param("query", "qualquer coisa"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_SERVICE_ERROR"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("X-Goog-Api-Key"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("AIzaSy"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("places.googleapis.com"));
    }

    @Test
    @DisplayName("9. Ausência de persistência automática: nenhuma entidade Place é criada no banco")
    void discovery_shouldNeverPersistPlaceInDatabase() throws Exception {
        String token = registerAndGetToken();

        PlaceCandidate candidate = new PlaceCandidate(
                "GOOGLE", "ChIJ_temp_no_persist", "Lugar Volátil", "Rua Teste, 999", -25.09, -50.15, List.of("point_of_interest")
        );

        when(placeDiscoveryPort.searchByText(eq("volatil"), any(), any(), any(), anyInt(), anyString()))
                .thenReturn(List.of(candidate));
        when(placeDiscoveryPort.getDetails("ChIJ_temp_no_persist", "pt-BR"))
                .thenReturn(Optional.of(candidate));

        // Conta lugares existentes antes da chamada
        List<com.rewit.domain.model.Place> placesBefore = placeRepository.findNearby(-25.09, -50.15, 100000.0);
        int countBefore = placesBefore.size();

        // Executa busca
        mockMvc.perform(get("/api/v1/places/discovery/search")
                        .header("Authorization", "Bearer " + token)
                        .param("query", "volatil"))
                .andExpect(status().isOk());

        // Executa detalhes
        mockMvc.perform(get("/api/v1/places/discovery/GOOGLE/ChIJ_temp_no_persist")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Conta lugares após as operações de discovery
        List<com.rewit.domain.model.Place> placesAfter = placeRepository.findNearby(-25.09, -50.15, 100000.0);
        int countAfter = placesAfter.size();


        assertEquals(countBefore, countAfter, "Nenhum Place deve ter sido criado/persistido durante a descoberta!");
    }

    @Test
    @DisplayName("10. Descoberta transporta atribuições legais no DTO sem vazar classes ou dados internos")
    void discovery_shouldTransportAttributionsInResponseDto() throws Exception {
        String token = registerAndGetToken();

        com.rewit.domain.model.PlaceAttribution attr = new com.rewit.domain.model.PlaceAttribution(
                "OpenStreetMap", "https://www.openstreetmap.org"
        );
        PlaceCandidate candidate = new PlaceCandidate(
                "GOOGLE",
                "ChIJ_with_attr",
                "Praça Santos Andrade",
                "Praça Santos Andrade, s/n - Centro",
                -25.4284,
                -49.2686,
                List.of("tourist_attraction"),
                List.of(attr)
        );

        when(placeDiscoveryPort.getDetails("ChIJ_with_attr", "pt-BR")).thenReturn(Optional.of(candidate));

        mockMvc.perform(get("/api/v1/places/discovery/GOOGLE/ChIJ_with_attr")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("GOOGLE"))
                .andExpect(jsonPath("$.externalId").value("ChIJ_with_attr"))
                .andExpect(jsonPath("$.attributions", hasSize(1)))
                .andExpect(jsonPath("$.attributions[0].provider").value("OpenStreetMap"))
                .andExpect(jsonPath("$.attributions[0].providerUri").value("https://www.openstreetmap.org"));
    }
}
