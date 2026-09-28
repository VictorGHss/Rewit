package com.rewit.application.service;

import com.rewit.application.dto.catalog.CatalogDtos.AddProductIdentifierCommand;
import com.rewit.application.dto.catalog.CatalogDtos.AssociateProductPresenceCommand;
import com.rewit.application.dto.catalog.CatalogDtos.CreatePlaceCommand;
import com.rewit.application.dto.catalog.CatalogDtos.CreateProductCommand;
import com.rewit.application.dto.catalog.CatalogDtos.ExternalReferenceInput;
import com.rewit.application.dto.catalog.CatalogDtos.NearbyPlaceResult;
import com.rewit.application.port.PlaceExternalReferenceRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProductIdentifierRepository;
import com.rewit.application.port.ProductPresenceRepository;
import com.rewit.application.port.ProductRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceExternalReference;
import com.rewit.domain.model.Product;
import com.rewit.domain.model.ProductIdentifier;
import com.rewit.domain.model.ProductPresence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: CatalogService (Step 7 e Step 9.3)")
class CatalogServiceUnitTest {

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductIdentifierRepository productIdentifierRepository;

    @Mock
    private ProductPresenceRepository productPresenceRepository;

    @Mock
    private PlaceExternalReferenceRepository placeExternalReferenceRepository;

    private CatalogService catalogService;

    @BeforeEach
    void setUp() {
        catalogService = new CatalogService(
                placeRepository,
                productRepository,
                productIdentifierRepository,
                productPresenceRepository,
                placeExternalReferenceRepository
        );
    }

    @Test
    @DisplayName("createPlace: Deve criar e salvar um Place sem referência externa quando o slug for único")
    void shouldCreatePlaceSuccessfully() {
        CreatePlaceCommand cmd = new CreatePlaceCommand(
                "Café do Centro", "cafe-do-centro", "CAFE", "Ótimo café",
                "Rua XV", "10", "Centro", "Curitiba", "PR", "BR",
                -25.42, -49.27, 50, "USER", null
        );

        when(placeRepository.existsBySlug("cafe-do-centro")).thenReturn(false);
        when(placeRepository.save(any(Place.class))).thenAnswer(i -> i.getArgument(0));

        Place created = catalogService.createPlace(cmd);

        assertNotNull(created);
        assertEquals("Café do Centro", created.getName());
        assertEquals("cafe-do-centro", created.getSlug());
        assertEquals("USER", created.getOrigin(), "Origin deve ser sempre USER");
        assertEquals(TargetType.PLACE, created.getTargetType());
        verify(placeRepository).save(any(Place.class));
        verify(placeExternalReferenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("createPlace: Deve lançar PLACE_SLUG_ALREADY_EXISTS (409) se slug explícito já estiver em uso")
    void shouldRejectPlaceWhenExplicitSlugAlreadyExists() {
        CreatePlaceCommand cmd = new CreatePlaceCommand(
                "Café Repetido", "cafe-ocupado", "CAFE", "Desc",
                "Rua 1", null, null, "Curitiba", "PR", "BR",
                -25.42, -49.27, 50, "USER", null
        );

        when(placeRepository.existsBySlug("cafe-ocupado")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () -> catalogService.createPlace(cmd));
        assertEquals("PLACE_SLUG_ALREADY_EXISTS", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(placeRepository, never()).save(any());
    }

    @Test
    @DisplayName("createPlace: Deve resolver colisão com sufixo determinístico -2 quando slug for omitido e derivado de nome colidir")
    void shouldResolveSlugCollisionWithDeterministicSuffixWhenSlugOmitted() {
        CreatePlaceCommand cmd = new CreatePlaceCommand(
                "Café Repetido", null, "CAFE", "Desc",
                "Rua 1", null, null, "Curitiba", "PR", "BR",
                -25.42, -49.27, 50, "USER", null
        );

        when(placeRepository.existsBySlug("cafe-repetido")).thenReturn(true);
        when(placeRepository.existsBySlug("cafe-repetido-2")).thenReturn(false);
        when(placeRepository.save(any(Place.class))).thenAnswer(i -> i.getArgument(0));

        Place created = catalogService.createPlace(cmd);

        assertNotNull(created);
        assertEquals("cafe-repetido-2", created.getSlug());
        verify(placeRepository).save(any(Place.class));
    }

    @Test
    @DisplayName("createPlace: Deve gerar slug a partir do nome quando slug for omitido")
    void shouldGenerateSlugFromNameWhenOmitted() {
        CreatePlaceCommand cmd = new CreatePlaceCommand(
                "Pizzaria do Bairro", null, "PIZZARIA", "Desc",
                "Rua 2", null, null, "Curitiba", "PR", "BR",
                -25.42, -49.27, 50, "USER", null
        );

        when(placeRepository.existsBySlug("pizzaria-do-bairro")).thenReturn(false);
        when(placeRepository.save(any(Place.class))).thenAnswer(i -> i.getArgument(0));

        Place created = catalogService.createPlace(cmd);

        assertNotNull(created);
        assertEquals("pizzaria-do-bairro", created.getSlug());
    }

    @Test
    @DisplayName("createPlace Concorrência Slug Explícito: Violação de uq_places_slug na persistência lança 409 sem tentar auto-geração")
    void shouldRejectPlaceWhenExplicitSlugCollidesDuringPersistence() {
        CreatePlaceCommand cmd = new CreatePlaceCommand(
                "Café Exclusivo", "cafe-exclusivo", "CAFE", "Desc",
                "Rua 1", null, null, "Curitiba", "PR", "BR",
                -25.42, -49.27, 50, "USER", null
        );

        when(placeRepository.existsBySlug("cafe-exclusivo")).thenReturn(false);
        when(placeRepository.save(any(Place.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_places_slug\""));

        BusinessException ex = assertThrows(BusinessException.class, () -> catalogService.createPlace(cmd));
        assertEquals("PLACE_SLUG_ALREADY_EXISTS", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        // Deve tentar apenas 1 vez o slug explícito e NÃO tentar sufixo -2
        verify(placeRepository, times(1)).save(any(Place.class));
    }

    @Test
    @DisplayName("createPlace Concorrência Slug Automático: Violação de uq_places_slug faz retry transacional com sufixo -2")
    void shouldRetryWithSuffixWhenAutomaticSlugCollidesDuringPersistence() {
        CreatePlaceCommand cmd = new CreatePlaceCommand(
                "Café Central", null, "CAFE", "Desc",
                "Rua 1", null, null, "Curitiba", "PR", "BR",
                -25.42, -49.27, 50, "USER", null
        );

        when(placeRepository.existsBySlug("cafe-central")).thenReturn(false);
        // Na primeira tentativa, ocorre colisão concorrente no banco de dados
        // Na segunda tentativa (com -2), tem sucesso
        when(placeRepository.save(any(Place.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_places_slug\""))
                .thenAnswer(i -> i.getArgument(0));

        Place created = catalogService.createPlace(cmd);

        assertNotNull(created);
        assertEquals("cafe-central-2", created.getSlug(), "Deve realizar retry e persistir com sufixo -2");
        verify(placeRepository, times(2)).save(any(Place.class));
    }

    @Test
    @DisplayName("createPlace Concorrência: Não mascarar outras violações de integridade que não sejam de slug ou referência")
    void shouldNotMaskOtherIntegrityViolations() {
        CreatePlaceCommand cmd = new CreatePlaceCommand(
                "Café Qualquer", null, "CAFE", "Desc",
                "Rua 1", null, null, "Curitiba", "PR", "BR",
                -25.42, -49.27, 50, "USER", null
        );

        when(placeRepository.existsBySlug("cafe-qualquer")).thenReturn(false);
        when(placeRepository.save(any(Place.class)))
                .thenThrow(new DataIntegrityViolationException("violates foreign key constraint \"fk_other\""));

        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> catalogService.createPlace(cmd));
        assertTrue(ex.getMessage().contains("fk_other"));
        verify(placeRepository, times(1)).save(any(Place.class));
    }

    @Test
    @DisplayName("createPlace Concorrência Referência Externa: Violação de uq_place_ext_ref recupera Place comitado pela vencedora")
    void shouldRecoverPlaceWhenExternalReferenceCollidesDuringPersistence() {
        UUID winnerPlaceId = UUID.randomUUID();
        Place winnerPlace = new Place(
                winnerPlaceId, "Vencedor", "vencedor", "BAR", "Desc",
                "End", "Curitiba", "PR", "BR", -25.0, -49.0, 50, "USER", false, null, "ACTIVE"
        );
        PlaceExternalReference winnerRef = new PlaceExternalReference(
                UUID.randomUUID(), winnerPlaceId, "GOOGLE", "ChIJ_RACE_123", null
        );

        ExternalReferenceInput extInput = new ExternalReferenceInput("GOOGLE", "ChIJ_RACE_123");
        CreatePlaceCommand cmd = new CreatePlaceCommand(
                "Concorrente Perdedor", "concorrente-perdedor", "BAR", "Desc",
                "End", null, null, "Curitiba", "PR", "BR",
                -25.0, -49.0, 50, "USER", null, extInput
        );

        when(placeExternalReferenceRepository.findByProviderAndExternalId("GOOGLE", "ChIJ_RACE_123"))
                .thenReturn(Optional.empty()) // Checagem preliminar ainda não viu
                .thenReturn(Optional.of(winnerRef)); // Recuperação após colisão vê a vencedora
        when(placeRepository.existsBySlug("concorrente-perdedor")).thenReturn(false);
        when(placeRepository.save(any(Place.class))).thenAnswer(i -> i.getArgument(0));
        doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_place_ext_ref\""))
                .when(placeExternalReferenceRepository).save(any(PlaceExternalReference.class));
        when(placeRepository.findById(winnerPlaceId)).thenReturn(Optional.of(winnerPlace));

        Place returned = catalogService.createPlace(cmd);

        assertSame(winnerPlace, returned);
        assertEquals("Vencedor", returned.getName());
    }

    @Test
    @DisplayName("createPlace com Referência Externa: Deve criar Place e persistir PlaceExternalReference atomicamente")
    void shouldCreatePlaceAndExternalReferenceAtomically() {
        ExternalReferenceInput extInput = new ExternalReferenceInput("GOOGLE", "ChIJ_TEST_123");
        CreatePlaceCommand cmd = new CreatePlaceCommand(
                "Bistrô Francês", "bistro-frances", "RESTAURANTE", "Desc",
                "Rua 3", null, null, "Curitiba", "PR", "BR",
                -25.42, -49.27, 50, "USER", null, extInput
        );

        when(placeExternalReferenceRepository.findByProviderAndExternalId("GOOGLE", "ChIJ_TEST_123"))
                .thenReturn(Optional.empty());
        when(placeRepository.existsBySlug("bistro-frances")).thenReturn(false);
        when(placeRepository.save(any(Place.class))).thenAnswer(i -> i.getArgument(0));

        Place created = catalogService.createPlace(cmd);

        assertNotNull(created);
        assertEquals("Bistrô Francês", created.getName());
        assertEquals("USER", created.getOrigin());

        ArgumentCaptor<PlaceExternalReference> refCaptor = ArgumentCaptor.forClass(PlaceExternalReference.class);
        verify(placeExternalReferenceRepository).save(refCaptor.capture());
        PlaceExternalReference savedRef = refCaptor.getValue();

        assertEquals(created.getId(), savedRef.getPlaceId());
        assertEquals("GOOGLE", savedRef.getProvider());
        assertEquals("ChIJ_TEST_123", savedRef.getExternalId());
        assertNull(savedRef.getMetadataJson(), "Metadata deve ser nulo no MVP");
    }

    @Test
    @DisplayName("createPlace Idempotência: Se referência externa já existir, retorna Place existente sem criar novo e sem mutações")
    void shouldReturnExistingPlaceOnIdempotentCallWithoutMutations() {
        UUID existingPlaceId = UUID.randomUUID();
        Place existingPlace = new Place(
                existingPlaceId, "Nome Original", "nome-original", "ORIGINAL", "Desc Original",
                "End Original", "Curitiba", "PR", "BR", -25.0, -49.0, 50, "USER", true, null, "ACTIVE"
        );

        PlaceExternalReference existingRef = new PlaceExternalReference(
                UUID.randomUUID(), existingPlaceId, "GOOGLE", "ChIJ_EXISTING_456", null
        );

        when(placeExternalReferenceRepository.findByProviderAndExternalId("GOOGLE", "ChIJ_EXISTING_456"))
                .thenReturn(Optional.of(existingRef));
        when(placeRepository.findById(existingPlaceId)).thenReturn(Optional.of(existingPlace));

        // Usuário tenta enviar novos dados com o mesmo Place ID do Google
        ExternalReferenceInput extInput = new ExternalReferenceInput("GOOGLE", "ChIJ_EXISTING_456");
        CreatePlaceCommand cmd = new CreatePlaceCommand(
                "Novo Nome Não Aceito", "novo-slug", "OUTRA", "Nova Desc",
                "Novo End", null, null, "Curitiba", "PR", "BR",
                -25.50, -49.50, 100, "USER", null, extInput
        );

        Place returned = catalogService.createPlace(cmd);

        // Deve retornar o existente sem alterações
        assertSame(existingPlace, returned);
        assertEquals("Nome Original", returned.getName());
        assertEquals("nome-original", returned.getSlug());
        assertEquals("Desc Original", returned.getDescription());

        // Nenhuma gravação adicional pode ter ocorrido
        verify(placeRepository, never()).save(any());
        verify(placeExternalReferenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("createPlace: Rejeitar referência externa com campos em branco")
    void shouldRejectInvalidExternalReferenceWhenBlank() {
        ExternalReferenceInput invalidInput = new ExternalReferenceInput("   ", "ChIJ123");
        CreatePlaceCommand cmd = new CreatePlaceCommand(
                "Local Teste", "local-teste", "GERAL", "Desc",
                "End", null, null, "Curitiba", "PR", "BR",
                -25.0, -49.0, 50, "USER", null, invalidInput
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> catalogService.createPlace(cmd));
        assertEquals("INVALID_EXTERNAL_REFERENCE", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("getPlaceById: Deve retornar local se encontrado ou lançar 404 se inexistente")
    void shouldGetPlaceByIdOrThrow() {
        UUID placeId = UUID.randomUUID();
        Place place = new Place(placeId, "Lugar A", "lugar-a", "BAR", "Desc",
                "End", "Curitiba", "PR", "BR", -25.0, -49.0, 50, "USER", false, null, "ACTIVE");

        when(placeRepository.findById(placeId)).thenReturn(Optional.of(place));

        Place result = catalogService.getPlaceById(placeId);
        assertNotNull(result);
        assertEquals("Lugar A", result.getName());

        UUID nonexistent = UUID.randomUUID();
        when(placeRepository.findById(nonexistent)).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class, () -> catalogService.getPlaceById(nonexistent));
        assertEquals("PLACE_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("createProduct: Deve criar e persistir um Product")
    void shouldCreateProductSuccessfully() {
        CreateProductCommand cmd = new CreateProductCommand(
                "Café Gourmet", "Café Real", "100% Arábica", "Excelente", "ALIMENTOS", "http://img.com"
        );

        when(productRepository.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        Product product = catalogService.createProduct(cmd);
        assertNotNull(product);
        assertEquals("Café Gourmet", product.getName());
        assertEquals(TargetType.PRODUCT, product.getTargetType());
        verify(productRepository).save(any(Product.class));
    }

    @Test
    @DisplayName("addProductIdentifier: Deve adicionar código e rejeitar produto inexistente ou código duplicado")
    void shouldManageProductIdentifierCorrectly() {
        UUID prodId = UUID.randomUUID();
        AddProductIdentifierCommand cmd = new AddProductIdentifierCommand(prodId, "EAN", "7891000100100");

        // 1. Produto inexistente -> 404
        when(productRepository.findById(prodId)).thenReturn(Optional.empty());
        BusinessException exNotFound = assertThrows(BusinessException.class, () -> catalogService.addProductIdentifier(cmd));
        assertEquals("PRODUCT_NOT_FOUND", exNotFound.getErrorCode());

        // 2. Produto existe, mas código já está em uso -> 409
        Product prod = new Product(prodId, "Prod", null, null, null, null, null, "ACTIVE");
        when(productRepository.findById(prodId)).thenReturn(Optional.of(prod));
        when(productIdentifierRepository.existsByTypeAndValue("EAN", "7891000100100")).thenReturn(true);

        BusinessException exConflict = assertThrows(BusinessException.class, () -> catalogService.addProductIdentifier(cmd));
        assertEquals("IDENTIFIER_ALREADY_EXISTS", exConflict.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, exConflict.getStatus());

        // 3. Sucesso
        when(productIdentifierRepository.existsByTypeAndValue("EAN", "7891000100100")).thenReturn(false);
        when(productIdentifierRepository.save(any(ProductIdentifier.class))).thenAnswer(i -> i.getArgument(0));

        ProductIdentifier saved = catalogService.addProductIdentifier(cmd);
        assertNotNull(saved);
        assertEquals("EAN", saved.getIdentifierType());
        assertEquals("7891000100100", saved.getIdentifierValue());
    }

    @Test
    @DisplayName("findProductByIdentifier: Deve localizar produto a partir do identificador")
    void shouldFindProductByIdentifier() {
        UUID prodId = UUID.randomUUID();
        ProductIdentifier identifier = new ProductIdentifier(UUID.randomUUID(), prodId, "EAN", "78912345");
        Product product = new Product(prodId, "Produto EAN", null, null, null, null, null, "ACTIVE");

        when(productIdentifierRepository.findByTypeAndValue("EAN", "78912345")).thenReturn(Optional.of(identifier));
        when(productRepository.findById(prodId)).thenReturn(Optional.of(product));

        Optional<Product> found = catalogService.findProductByIdentifier("EAN", "78912345");
        assertTrue(found.isPresent());
        assertEquals("Produto EAN", found.get().getName());
    }

    @Test
    @DisplayName("associateProductToPlace: Deve validar integridade e associar Product a Place")
    void shouldAssociateProductToPlace() {
        UUID prodId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        AssociateProductPresenceCommand cmd = new AssociateProductPresenceCommand(prodId, placeId, userId);

        // 1. Produto inexistente -> 404
        when(productRepository.findById(prodId)).thenReturn(Optional.empty());
        BusinessException exProd = assertThrows(BusinessException.class, () -> catalogService.associateProductToPlace(cmd));
        assertEquals("PRODUCT_NOT_FOUND", exProd.getErrorCode());

        // 2. Local inexistente -> 404
        Product prod = new Product(prodId, "P", null, null, null, null, null, "ACTIVE");
        when(productRepository.findById(prodId)).thenReturn(Optional.of(prod));
        when(placeRepository.findById(placeId)).thenReturn(Optional.empty());

        BusinessException exPlace = assertThrows(BusinessException.class, () -> catalogService.associateProductToPlace(cmd));
        assertEquals("PLACE_NOT_FOUND", exPlace.getErrorCode());

        // 3. Sucesso
        Place place = new Place(placeId, "L", "l", "C", "D", "E", "C", "P", "BR", 0.0, 0.0, 50, "U", false, null, "A");
        when(placeRepository.findById(placeId)).thenReturn(Optional.of(place));
        when(productPresenceRepository.findByProductIdAndPlaceId(prodId, placeId)).thenReturn(Optional.empty());
        when(productPresenceRepository.save(any(ProductPresence.class))).thenAnswer(i -> i.getArgument(0));

        ProductPresence presence = catalogService.associateProductToPlace(cmd);
        assertNotNull(presence);
        assertEquals(prodId, presence.getProductId());
        assertEquals(placeId, presence.getPlaceId());
        verify(productPresenceRepository).save(any(ProductPresence.class));
    }

    @Test
    @DisplayName("findNearbyPlaces: Coordenadas válidas chamam o repositório e propagam distância corretamente")
    void findNearbyPlaces_ValidCoordinates_CallsRepositoryAndPropagatesDistance() {
        Place place = new Place(UUID.randomUUID(), "Lugar Perto", "lugar-perto", "CAFE", null, "Rua A", null, null, "Curitiba", "PR", "BR", -25.4, -49.2, 50, "USER", false, null, "ACTIVE");
        NearbyPlaceResult result = new NearbyPlaceResult(place, 150.5);

        when(placeRepository.findNearbyWithDistance(-25.4, -49.2, 1000.0, 20))
                .thenReturn(java.util.List.of(result));

        java.util.List<NearbyPlaceResult> list = catalogService.findNearbyPlaces(-25.4, -49.2, 1000.0, 20);

        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals(place.getId(), list.get(0).place().getId());
        assertEquals(150.5, list.get(0).distanceMeters(), 0.001);
        verify(placeRepository).findNearbyWithDistance(-25.4, -49.2, 1000.0, 20);
    }

    @Test
    @DisplayName("findNearbyPlaces: Latitude abaixo de -90 lança BusinessException com INVALID_NEARBY_COORDINATES")
    void findNearbyPlaces_LatitudeBelowMin_ThrowsException() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                catalogService.findNearbyPlaces(-90.1, -49.2, 1000.0, 20));
        assertEquals("INVALID_NEARBY_COORDINATES", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("findNearbyPlaces: Latitude acima de 90 lança BusinessException com INVALID_NEARBY_COORDINATES")
    void findNearbyPlaces_LatitudeAboveMax_ThrowsException() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                catalogService.findNearbyPlaces(90.1, -49.2, 1000.0, 20));
        assertEquals("INVALID_NEARBY_COORDINATES", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("findNearbyPlaces: Longitude abaixo de -180 lança BusinessException com INVALID_NEARBY_COORDINATES")
    void findNearbyPlaces_LongitudeBelowMin_ThrowsException() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                catalogService.findNearbyPlaces(-25.4, -180.1, 1000.0, 20));
        assertEquals("INVALID_NEARBY_COORDINATES", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("findNearbyPlaces: Longitude acima de 180 lança BusinessException com INVALID_NEARBY_COORDINATES")
    void findNearbyPlaces_LongitudeAboveMax_ThrowsException() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                catalogService.findNearbyPlaces(-25.4, 180.1, 1000.0, 20));
        assertEquals("INVALID_NEARBY_COORDINATES", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("findNearbyPlaces: Raio igual a zero lança BusinessException com INVALID_NEARBY_RADIUS")
    void findNearbyPlaces_RadiusZero_ThrowsException() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                catalogService.findNearbyPlaces(-25.4, -49.2, 0.0, 20));
        assertEquals("INVALID_NEARBY_RADIUS", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("findNearbyPlaces: Raio negativo lança BusinessException com INVALID_NEARBY_RADIUS")
    void findNearbyPlaces_RadiusNegative_ThrowsException() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                catalogService.findNearbyPlaces(-25.4, -49.2, -100.0, 20));
        assertEquals("INVALID_NEARBY_RADIUS", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("findNearbyPlaces: Raio acima do máximo (50km) lança BusinessException com INVALID_NEARBY_RADIUS")
    void findNearbyPlaces_RadiusAboveMax_ThrowsException() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                catalogService.findNearbyPlaces(-25.4, -49.2, 50000.1, 20));
        assertEquals("INVALID_NEARBY_RADIUS", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("findNearbyPlaces: Limit igual a zero lança BusinessException com INVALID_NEARBY_LIMIT")
    void findNearbyPlaces_LimitZero_ThrowsException() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                catalogService.findNearbyPlaces(-25.4, -49.2, 1000.0, 0));
        assertEquals("INVALID_NEARBY_LIMIT", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("findNearbyPlaces: Limit negativo lança BusinessException com INVALID_NEARBY_LIMIT")
    void findNearbyPlaces_LimitNegative_ThrowsException() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                catalogService.findNearbyPlaces(-25.4, -49.2, 1000.0, -5));
        assertEquals("INVALID_NEARBY_LIMIT", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("findNearbyPlaces: Limit acima de 100 lança BusinessException com INVALID_NEARBY_LIMIT")
    void findNearbyPlaces_LimitAboveMax_ThrowsException() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                catalogService.findNearbyPlaces(-25.4, -49.2, 1000.0, 101));
        assertEquals("INVALID_NEARBY_LIMIT", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("findNearbyPlaces: NaN ou Infinity rejeitados com BAD_REQUEST")
    void findNearbyPlaces_NaNAndInfinity_ThrowsException() {
        assertThrows(BusinessException.class, () -> catalogService.findNearbyPlaces(Double.NaN, -49.2, 1000.0, 20));
        assertThrows(BusinessException.class, () -> catalogService.findNearbyPlaces(-25.4, Double.POSITIVE_INFINITY, 1000.0, 20));
        assertThrows(BusinessException.class, () -> catalogService.findNearbyPlaces(-25.4, -49.2, Double.NaN, 20));
    }
}
