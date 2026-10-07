package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.catalog.CatalogDtos.CreatePlaceCommand;
import com.rewit.application.dto.catalog.CatalogDtos.NearbyPlaceResult;
import com.rewit.application.dto.catalog.CatalogDtos.PlaceAdoptionResult;
import com.rewit.application.service.CatalogService;
import com.rewit.common.exception.BusinessException;
import com.rewit.common.exception.GlobalExceptionHandler;
import com.rewit.domain.model.Place;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.AdoptPlaceRequest;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.AdoptPlaceRequest.ExternalReferenceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: PlaceController (Step 9.4)")
class PlaceControllerUnitTest {

    @Mock
    private CatalogService catalogService;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private PlaceController placeController;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID authenticatedUserId = UUID.randomUUID();

    private Authentication createAuth() {
        return new UsernamePasswordAuthenticationToken(authenticatedUserId.toString(), null, Collections.emptyList());
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(placeController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Place createSamplePlace(UUID id, String name, String slug) {
        return new Place(
                id,
                name,
                slug,
                "RESTAURANTE",
                "Descrição de teste",
                "Rua das Flores, 100",
                "100",
                "Centro",
                "Curitiba",
                "PR",
                "BR",
                -25.4284,
                -49.2733,
                50,
                "USER",
                false,
                null,
                "ACTIVE"
        );
    }

    @Test
    @DisplayName("1. POST /api/v1/places/adopt: Criar novo local retorna 201 Created com header Location")
    void shouldAdoptPlaceSuccessfullyAndReturnCreated() throws Exception {
        UUID placeId = UUID.randomUUID();
        Place place = createSamplePlace(placeId, "Café das Flores", "cafe-das-flores");
        PlaceAdoptionResult adoptionResult = new PlaceAdoptionResult(place, true);

        when(catalogService.adoptPlace(eq(authenticatedUserId), any(CreatePlaceCommand.class))).thenReturn(adoptionResult);

        AdoptPlaceRequest request = new AdoptPlaceRequest(
                "Café das Flores",
                "cafe-das-flores",
                "CAFE",
                "Café artesanal",
                "Rua XV, 50",
                "50",
                "Centro",
                "Curitiba",
                "PR",
                "BR",
                -25.4280,
                -49.2680,
                50,
                new ExternalReferenceRequest("GOOGLE", "ChIJ_TEST_123")
        );

        mockMvc.perform(post("/api/v1/places/adopt")
                        .principal(createAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/places/" + placeId))
                .andExpect(jsonPath("$.id").value(placeId.toString()))
                .andExpect(jsonPath("$.name").value("Café das Flores"))
                .andExpect(jsonPath("$.slug").value("cafe-das-flores"))
                .andExpect(jsonPath("$.origin").value("USER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(catalogService).adoptPlace(eq(authenticatedUserId), any(CreatePlaceCommand.class));
    }

    @Test
    @DisplayName("2. POST /api/v1/places/adopt: Requisição sem autenticação retorna 401 Unauthorized")
    void shouldReturnUnauthorizedWhenNotAuthenticated() throws Exception {
        AdoptPlaceRequest request = new AdoptPlaceRequest(
                "Café Sem Auth",
                "cafe-sem-auth",
                "CAFE",
                "Desc",
                "Rua 1",
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
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(catalogService, never()).adoptPlace(any(), any());
    }

    @Test
    @DisplayName("3. POST /api/v1/places/adopt: userId enviado no JSON é ignorado e não afeta comando")
    void shouldIgnoreUserIdInPayload() throws Exception {
        UUID placeId = UUID.randomUUID();
        Place place = createSamplePlace(placeId, "Local A", "local-a");
        when(catalogService.adoptPlace(eq(authenticatedUserId), any(CreatePlaceCommand.class))).thenReturn(new PlaceAdoptionResult(place, true));

        // Envia JSON com campo espúrio "userId"
        Map<String, Object> payloadWithUserId = Map.of(
                "name", "Local A",
                "slug", "local-a",
                "addressText", "Rua A",
                "city", "Curitiba",
                "state", "PR",
                "latitude", -25.0,
                "longitude", -49.0,
                "userId", UUID.randomUUID().toString()
        );

        mockMvc.perform(post("/api/v1/places/adopt")
                        .principal(createAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payloadWithUserId)))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreatePlaceCommand> captor = ArgumentCaptor.forClass(CreatePlaceCommand.class);
        verify(catalogService).adoptPlace(eq(authenticatedUserId), captor.capture());
        CreatePlaceCommand cmd = captor.getValue();
        assertEquals("Local A", cmd.name());
        assertEquals("local-a", cmd.slug());
    }

    @Test
    @DisplayName("4. POST /api/v1/places/adopt: Criação com externalReference repassa dados ao comando")
    void shouldAdoptWithExternalReferenceSuccessfully() throws Exception {
        UUID placeId = UUID.randomUUID();
        Place place = createSamplePlace(placeId, "Local Com Ref", "local-com-ref");
        when(catalogService.adoptPlace(eq(authenticatedUserId), any(CreatePlaceCommand.class))).thenReturn(new PlaceAdoptionResult(place, true));

        AdoptPlaceRequest request = new AdoptPlaceRequest(
                "Local Com Ref",
                "local-com-ref",
                "CAFE",
                "Desc",
                "Rua B",
                "2",
                "Bairro",
                "Curitiba",
                "PR",
                "BR",
                -25.0,
                -49.0,
                50,
                new ExternalReferenceRequest("GOOGLE", "ChIJ_OPAQUE_456")
        );

        mockMvc.perform(post("/api/v1/places/adopt")
                        .principal(createAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreatePlaceCommand> captor = ArgumentCaptor.forClass(CreatePlaceCommand.class);
        verify(catalogService).adoptPlace(eq(authenticatedUserId), captor.capture());
        CreatePlaceCommand cmd = captor.getValue();
        assertNotNull(cmd.externalReference());
        assertEquals("GOOGLE", cmd.externalReference().provider());
        assertEquals("ChIJ_OPAQUE_456", cmd.externalReference().externalId());
    }

    @Test
    @DisplayName("5. POST /api/v1/places/adopt: Segunda adoção da mesma referência retorna 200 OK sem header Location")
    void shouldReturnOkWhenAdoptingExistingPlace() throws Exception {
        UUID placeId = UUID.randomUUID();
        Place existingPlace = createSamplePlace(placeId, "Lugar Já Existente", "lugar-ja-existente");
        // newlyCreated = false -> Idempotência
        PlaceAdoptionResult adoptionResult = new PlaceAdoptionResult(existingPlace, false);

        when(catalogService.adoptPlace(eq(authenticatedUserId), any(CreatePlaceCommand.class))).thenReturn(adoptionResult);

        AdoptPlaceRequest request = new AdoptPlaceRequest(
                "Tentativa com Outro Nome",
                null,
                "CAFE",
                "Outra desc",
                "Outro end",
                null,
                null,
                "Curitiba",
                "PR",
                "BR",
                -25.0,
                -49.0,
                50,
                new ExternalReferenceRequest("GOOGLE", "ChIJ_SAME_REF")
        );

        mockMvc.perform(post("/api/v1/places/adopt")
                        .principal(createAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.id").value(placeId.toString()))
                .andExpect(jsonPath("$.name").value("Lugar Já Existente"));
    }

    @Test
    @DisplayName("6. POST /api/v1/places/adopt: Segunda adoção não altera dados do local original")
    void shouldNotAlterExistingPlaceOnSecondAdoption() throws Exception {
        UUID placeId = UUID.randomUUID();
        Place originalPlace = createSamplePlace(placeId, "Nome Imutável", "slug-imutavel");
        when(catalogService.adoptPlace(eq(authenticatedUserId), any(CreatePlaceCommand.class))).thenReturn(new PlaceAdoptionResult(originalPlace, false));

        AdoptPlaceRequest secondRequest = new AdoptPlaceRequest(
                "Nome Alterado Pelo Cliente",
                "novo-slug",
                "NOVA_CAT",
                "Nova desc",
                "Novo endereço",
                "999",
                "Outro Bairro",
                "São Paulo",
                "SP",
                "BR",
                -23.55,
                -46.63,
                100,
                new ExternalReferenceRequest("GOOGLE", "ChIJ_SAME_REF")
        );

        mockMvc.perform(post("/api/v1/places/adopt")
                        .principal(createAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(placeId.toString()))
                .andExpect(jsonPath("$.name").value("Nome Imutável"))
                .andExpect(jsonPath("$.slug").value("slug-imutavel"))
                .andExpect(jsonPath("$.city").value("Curitiba"));
    }

    @Test
    @DisplayName("7. POST /api/v1/places/adopt: Slug explícito duplicado retorna 409 Conflict")
    void shouldReturnConflictWhenExplicitSlugAlreadyExists() throws Exception {
        when(catalogService.adoptPlace(eq(authenticatedUserId), any(CreatePlaceCommand.class)))
                .thenThrow(new BusinessException("Slug do local já está em uso", HttpStatus.CONFLICT, "PLACE_SLUG_ALREADY_EXISTS"));

        AdoptPlaceRequest request = new AdoptPlaceRequest(
                "Café Colidindo",
                "cafe-colidindo",
                "CAFE",
                "Desc",
                "Rua 1",
                "1",
                "Centro",
                "Curitiba",
                "PR",
                "BR",
                -25.0,
                -49.0,
                50,
                null
        );

        mockMvc.perform(post("/api/v1/places/adopt")
                        .principal(createAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLACE_SLUG_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("8. POST /api/v1/places/adopt: Payload com origin não consegue alterar valor USER")
    void shouldNotAllowPayloadToOverrideOrigin() throws Exception {
        UUID placeId = UUID.randomUUID();
        Place place = createSamplePlace(placeId, "Lugar", "lugar");
        when(catalogService.adoptPlace(eq(authenticatedUserId), any(CreatePlaceCommand.class))).thenReturn(new PlaceAdoptionResult(place, true));

        Map<String, Object> payloadWithOrigin = Map.of(
                "name", "Lugar",
                "addressText", "Rua A",
                "city", "Curitiba",
                "state", "PR",
                "latitude", -25.0,
                "longitude", -49.0,
                "origin", "GOOGLE"
        );

        mockMvc.perform(post("/api/v1/places/adopt")
                        .principal(createAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payloadWithOrigin)))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreatePlaceCommand> captor = ArgumentCaptor.forClass(CreatePlaceCommand.class);
        verify(catalogService).adoptPlace(eq(authenticatedUserId), captor.capture());
        assertEquals("USER", captor.getValue().origin(), "Origin deve ser sempre forçado como USER");
    }

    @Test
    @DisplayName("9. POST /api/v1/places/adopt: Payload com metadados Google extras não é mapeado")
    void shouldNotSendGoogleMetadataToService() throws Exception {
        UUID placeId = UUID.randomUUID();
        Place place = createSamplePlace(placeId, "Lugar Zero-Store", "lugar-zero-store");
        when(catalogService.adoptPlace(eq(authenticatedUserId), any(CreatePlaceCommand.class))).thenReturn(new PlaceAdoptionResult(place, true));

        Map<String, Object> payloadWithGoogleData = Map.ofEntries(
                Map.entry("name", "Lugar Zero-Store"),
                Map.entry("addressText", "Rua A"),
                Map.entry("city", "Curitiba"),
                Map.entry("state", "PR"),
                Map.entry("latitude", -25.0),
                Map.entry("longitude", -49.0),
                Map.entry("externalReference", Map.of(
                        "provider", "GOOGLE",
                        "externalId", "ChIJ_CLEAN_123"
                )),
                Map.entry("displayName", "Google Display Name"),
                Map.entry("formattedAddress", "Google Formatted Address"),
                Map.entry("rating", 4.9),
                Map.entry("reviews", "raw reviews"),
                Map.entry("metadataJson", "{\"google\": true}")
        );

        mockMvc.perform(post("/api/v1/places/adopt")
                        .principal(createAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payloadWithGoogleData)))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreatePlaceCommand> captor = ArgumentCaptor.forClass(CreatePlaceCommand.class);
        verify(catalogService).adoptPlace(eq(authenticatedUserId), captor.capture());
        CreatePlaceCommand cmd = captor.getValue();
        assertEquals("Lugar Zero-Store", cmd.name());
        assertEquals("ChIJ_CLEAN_123", cmd.externalReference().externalId());
    }

    @Test
    @DisplayName("10. GET /api/v1/places/external/{provider}/{externalId}: Retorna 200 OK com dados do local associado")
    void shouldGetPlaceByExternalReferenceSuccessfully() throws Exception {
        UUID placeId = UUID.randomUUID();
        Place place = createSamplePlace(placeId, "Lugar Encontrado", "lugar-encontrado");
        when(catalogService.getPlaceByExternalReference("GOOGLE", "ChIJ_FOUND_123")).thenReturn(place);

        mockMvc.perform(get("/api/v1/places/external/{provider}/{externalId}", "GOOGLE", "ChIJ_FOUND_123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(placeId.toString()))
                .andExpect(jsonPath("$.name").value("Lugar Encontrado"))
                .andExpect(jsonPath("$.slug").value("lugar-encontrado"));

        verify(catalogService).getPlaceByExternalReference("GOOGLE", "ChIJ_FOUND_123");
    }

    @Test
    @DisplayName("11. GET /api/v1/places/external/{provider}/{externalId}: Referência inexistente retorna 404 PLACE_EXTERNAL_REFERENCE_NOT_FOUND")
    void shouldReturnNotFoundWhenExternalReferenceDoesNotExist() throws Exception {
        when(catalogService.getPlaceByExternalReference("GOOGLE", "ChIJ_NOT_FOUND"))
                .thenThrow(new BusinessException("Referência externa de local não encontrada",
                        HttpStatus.NOT_FOUND, "PLACE_EXTERNAL_REFERENCE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/places/external/{provider}/{externalId}", "GOOGLE", "ChIJ_NOT_FOUND"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLACE_EXTERNAL_REFERENCE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Referência externa de local não encontrada"));
    }

    @Test
    @DisplayName("12. GET /api/v1/places/external/{provider}/{externalId}: externalId é preservado como token opaco")
    void shouldTreatExternalIdAsOpaqueToken() throws Exception {
        String opaqueToken = "ChIJ_CaseSensitive-123_456.XYZ";
        UUID placeId = UUID.randomUUID();
        Place place = createSamplePlace(placeId, "Local Opaco", "local-opaco");
        when(catalogService.getPlaceByExternalReference("GOOGLE", opaqueToken)).thenReturn(place);

        mockMvc.perform(get("/api/v1/places/external/{provider}/{externalId}", "GOOGLE", opaqueToken))
                .andExpect(status().isOk());

        verify(catalogService).getPlaceByExternalReference("GOOGLE", opaqueToken);
    }

    @Test
    @DisplayName("13. GET /api/v1/places/nearby: Chamada válida retorna 200 OK com itens e limit")
    void shouldReturnNearbyPlacesSuccessfully() throws Exception {
        UUID placeId = UUID.randomUUID();
        Place place = createSamplePlace(placeId, "Café Próximo", "cafe-proximo");
        NearbyPlaceResult result = new NearbyPlaceResult(place, 125.75);

        when(catalogService.findNearbyPlaces(-25.4297, -49.2719, 1000.0, 15))
                .thenReturn(java.util.List.of(result));

        mockMvc.perform(get("/api/v1/places/nearby")
                        .param("latitude", "-25.4297")
                        .param("longitude", "-49.2719")
                        .param("radiusMeters", "1000.0")
                        .param("limit", "15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(15))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(placeId.toString()))
                .andExpect(jsonPath("$.items[0].name").value("Café Próximo"))
                .andExpect(jsonPath("$.items[0].distanceMeters").value(125.75));

        verify(catalogService).findNearbyPlaces(-25.4297, -49.2719, 1000.0, 15);
    }

    @Test
    @DisplayName("14. GET /api/v1/places/nearby: Chamada sem limit assume o padrão 20")
    void shouldReturnNearbyPlacesWithDefaultLimit() throws Exception {
        when(catalogService.findNearbyPlaces(-25.4297, -49.2719, 500.0, 20))
                .thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/v1/places/nearby")
                        .param("latitude", "-25.4297")
                        .param("longitude", "-49.2719")
                        .param("radiusMeters", "500.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.items").isEmpty());

        verify(catalogService).findNearbyPlaces(-25.4297, -49.2719, 500.0, 20);
    }

    @Test
    @DisplayName("15. GET /api/v1/places/nearby: Lista vazia retorna 200 OK")
    void shouldReturn200OkWhenNoPlacesFoundNearby() throws Exception {
        when(catalogService.findNearbyPlaces(-25.4297, -49.2719, 100.0, 20))
                .thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/v1/places/nearby")
                        .param("latitude", "-25.4297")
                        .param("longitude", "-49.2719")
                        .param("radiusMeters", "100.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.limit").value(20));
    }

    @Test
    @DisplayName("16. GET /api/v1/places/nearby: Coordenada inválida retorna 400 Bad Request com Problem Details")
    void shouldReturnBadRequestWhenCoordinatesAreInvalid() throws Exception {
        when(catalogService.findNearbyPlaces(-95.0, -49.2719, 1000.0, 20))
                .thenThrow(new BusinessException("Latitude deve estar entre -90.0 e 90.0",
                        HttpStatus.BAD_REQUEST, "INVALID_NEARBY_COORDINATES"));

        mockMvc.perform(get("/api/v1/places/nearby")
                        .param("latitude", "-95.0")
                        .param("longitude", "-49.2719")
                        .param("radiusMeters", "1000.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NEARBY_COORDINATES"))
                .andExpect(jsonPath("$.detail").value("Latitude deve estar entre -90.0 e 90.0"));
    }

    @Test
    @DisplayName("17. GET /api/v1/places/nearby: Raio inválido retorna 400 Bad Request com Problem Details")
    void shouldReturnBadRequestWhenRadiusIsInvalid() throws Exception {
        when(catalogService.findNearbyPlaces(-25.4297, -49.2719, 60000.0, 20))
                .thenThrow(new BusinessException("O raio de busca deve ser maior que zero e até 50.000 metros",
                        HttpStatus.BAD_REQUEST, "INVALID_NEARBY_RADIUS"));

        mockMvc.perform(get("/api/v1/places/nearby")
                        .param("latitude", "-25.4297")
                        .param("longitude", "-49.2719")
                        .param("radiusMeters", "60000.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NEARBY_RADIUS"))
                .andExpect(jsonPath("$.detail").value("O raio de busca deve ser maior que zero e até 50.000 metros"));
    }

    @Test
    @DisplayName("18. GET /api/v1/places/nearby: Limit inválido retorna 400 Bad Request com Problem Details")
    void shouldReturnBadRequestWhenLimitIsInvalid() throws Exception {
        when(catalogService.findNearbyPlaces(-25.4297, -49.2719, 1000.0, 0))
                .thenThrow(new BusinessException("O limite de resultados deve ser entre 1 e 100",
                        HttpStatus.BAD_REQUEST, "INVALID_NEARBY_LIMIT"));

        mockMvc.perform(get("/api/v1/places/nearby")
                        .param("latitude", "-25.4297")
                        .param("longitude", "-49.2719")
                        .param("radiusMeters", "1000.0")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NEARBY_LIMIT"))
                .andExpect(jsonPath("$.detail").value("O limite de resultados deve ser entre 1 e 100"));
    }
}
