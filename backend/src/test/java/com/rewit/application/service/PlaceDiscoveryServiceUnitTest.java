package com.rewit.application.service;

import com.rewit.application.port.PlaceDiscoveryPort;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.PlaceCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes de Unidade de PlaceDiscoveryService (Step 8)")
class PlaceDiscoveryServiceUnitTest {

    @Mock
    private PlaceDiscoveryPort placeDiscoveryPort;

    private PlaceDiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        discoveryService = new PlaceDiscoveryService(placeDiscoveryPort);
    }

    @Test
    @DisplayName("Deve normalizar query com trim e aplicar limite padrão quando limit não informado")
    void shouldNormalizeQueryAndApplyDefaultLimit() {
        PlaceCandidate candidate = new PlaceCandidate(
                "GOOGLE", "place_123", "Café Central", "Rua XV, 100", -25.09, -50.16, List.of("cafe")
        );
        when(placeDiscoveryPort.searchByText(eq("Café Central"), isNull(), isNull(), isNull(), eq(10), eq("pt-BR")))
                .thenReturn(List.of(candidate));

        List<PlaceCandidate> results = discoveryService.searchCandidates("  Café Central  ", null, null, null, null);

        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("Café Central", results.get(0).getDisplayName());
        verify(placeDiscoveryPort).searchByText("Café Central", null, null, null, 10, "pt-BR");
    }

    @Test
    @DisplayName("Deve rejeitar consulta com query nula ou em branco")
    void shouldRejectBlankOrNullQuery() {
        BusinessException ex1 = assertThrows(BusinessException.class, () ->
                discoveryService.searchCandidates(null, null, null, null, 10)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex1.getStatus());
        assertEquals("INVALID_QUERY", ex1.getErrorCode());

        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                discoveryService.searchCandidates("   ", null, null, null, 10)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex2.getStatus());
        assertEquals("INVALID_QUERY", ex2.getErrorCode());

        verifyNoInteractions(placeDiscoveryPort);
    }

    @Test
    @DisplayName("Deve rejeitar limites fora do intervalo [1, 20]")
    void shouldRejectInvalidLimits() {
        BusinessException exZero = assertThrows(BusinessException.class, () ->
                discoveryService.searchCandidates("Restaurante", null, null, null, 0)
        );
        assertEquals(HttpStatus.BAD_REQUEST, exZero.getStatus());
        assertEquals("INVALID_LIMIT", exZero.getErrorCode());

        BusinessException exMax = assertThrows(BusinessException.class, () ->
                discoveryService.searchCandidates("Restaurante", null, null, null, 21)
        );
        assertEquals(HttpStatus.BAD_REQUEST, exMax.getStatus());
        assertEquals("INVALID_LIMIT", exMax.getErrorCode());

        verifyNoInteractions(placeDiscoveryPort);
    }

    @Test
    @DisplayName("Deve aceitar limite customizado válido")
    void shouldAcceptValidCustomLimit() {
        when(placeDiscoveryPort.searchByText(anyString(), any(), any(), any(), eq(15), anyString()))
                .thenReturn(List.of());

        List<PlaceCandidate> results = discoveryService.searchCandidates("Pizzaria", null, null, null, 15);

        assertNotNull(results);
        verify(placeDiscoveryPort).searchByText("Pizzaria", null, null, null, 15, "pt-BR");
    }

    @Test
    @DisplayName("Deve validar coordenadas e rejeitar configurações geográficas inconsistentes")
    void shouldValidateCoordinates() {
        // Apenas latitude
        BusinessException exOnlyLat = assertThrows(BusinessException.class, () ->
                discoveryService.searchCandidates("Bar", -25.0, null, null, 10)
        );
        assertEquals("INVALID_COORDINATES", exOnlyLat.getErrorCode());

        // Apenas longitude
        BusinessException exOnlyLng = assertThrows(BusinessException.class, () ->
                discoveryService.searchCandidates("Bar", null, -50.0, null, 10)
        );
        assertEquals("INVALID_COORDINATES", exOnlyLng.getErrorCode());

        // Latitude fora de -90 a 90
        BusinessException exLatOutOfRange = assertThrows(BusinessException.class, () ->
                discoveryService.searchCandidates("Bar", -91.0, -50.0, null, 10)
        );
        assertEquals("INVALID_COORDINATES", exLatOutOfRange.getErrorCode());

        // Longitude fora de -180 a 180
        BusinessException exLngOutOfRange = assertThrows(BusinessException.class, () ->
                discoveryService.searchCandidates("Bar", -25.0, 181.0, null, 10)
        );
        assertEquals("INVALID_COORDINATES", exLngOutOfRange.getErrorCode());

        // Raio negativo ou zero
        BusinessException exNegativeRadius = assertThrows(BusinessException.class, () ->
                discoveryService.searchCandidates("Bar", -25.0, -50.0, -10.0, 10)
        );
        assertEquals("INVALID_RADIUS", exNegativeRadius.getErrorCode());

        verifyNoInteractions(placeDiscoveryPort);
    }

    @Test
    @DisplayName("Deve passar coordenadas e raio válidos para o port")
    void shouldPassValidCoordinatesAndRadiusToPort() {
        when(placeDiscoveryPort.searchByText(eq("Padaria"), eq(-25.0916), eq(-50.1581), eq(3000.0), eq(10), eq("pt-BR")))
                .thenReturn(List.of());

        discoveryService.searchCandidates("Padaria", -25.0916, -50.1581, 3000.0, 10);

        verify(placeDiscoveryPort).searchByText("Padaria", -25.0916, -50.1581, 3000.0, 10, "pt-BR");
    }

    @Test
    @DisplayName("Deve obter detalhes com sucesso para o provedor GOOGLE")
    void shouldGetDetailsForGoogleProvider() {
        PlaceCandidate candidate = new PlaceCandidate(
                "GOOGLE", "place_abc", "Museu Histórico", "Av. Brasil, 500", -25.0, -50.0, List.of("museum")
        );
        when(placeDiscoveryPort.getDetails("place_abc", "pt-BR")).thenReturn(Optional.of(candidate));

        PlaceCandidate result = discoveryService.getCandidateDetails("google", "  place_abc  ");

        assertNotNull(result);
        assertEquals("GOOGLE", result.getProvider());
        assertEquals("place_abc", result.getExternalId());
        assertEquals("Museu Histórico", result.getDisplayName());
        verify(placeDiscoveryPort).getDetails("place_abc", "pt-BR");
    }

    @Test
    @DisplayName("Deve rejeitar provedor diferente de GOOGLE")
    void shouldRejectUnsupportedProvider() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                discoveryService.getCandidateDetails("FOURSQUARE", "ext_123")
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("UNSUPPORTED_PROVIDER", ex.getErrorCode());

        BusinessException exNull = assertThrows(BusinessException.class, () ->
                discoveryService.getCandidateDetails(null, "ext_123")
        );
        assertEquals(HttpStatus.BAD_REQUEST, exNull.getStatus());
        assertEquals("INVALID_PROVIDER", exNull.getErrorCode());

        verifyNoInteractions(placeDiscoveryPort);
    }

    @Test
    @DisplayName("Deve rejeitar externalId nulo ou vazio")
    void shouldRejectBlankOrNullExternalId() {
        BusinessException exNull = assertThrows(BusinessException.class, () ->
                discoveryService.getCandidateDetails("GOOGLE", null)
        );
        assertEquals(HttpStatus.BAD_REQUEST, exNull.getStatus());
        assertEquals("INVALID_EXTERNAL_ID", exNull.getErrorCode());

        BusinessException exBlank = assertThrows(BusinessException.class, () ->
                discoveryService.getCandidateDetails("GOOGLE", "   ")
        );
        assertEquals(HttpStatus.BAD_REQUEST, exBlank.getStatus());
        assertEquals("INVALID_EXTERNAL_ID", exBlank.getErrorCode());

        verifyNoInteractions(placeDiscoveryPort);
    }

    @Test
    @DisplayName("Deve lançar EXTERNAL_PLACE_NOT_FOUND quando o port retornar Optional.empty()")
    void shouldThrowNotFoundWhenPortReturnsEmpty() {
        when(placeDiscoveryPort.getDetails("non_existent", "pt-BR")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                discoveryService.getCandidateDetails("GOOGLE", "non_existent")
        );
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("EXTERNAL_PLACE_NOT_FOUND", ex.getErrorCode());
    }
}
