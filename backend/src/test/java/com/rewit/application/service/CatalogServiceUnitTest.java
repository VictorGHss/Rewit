package com.rewit.application.service;

import com.rewit.application.dto.catalog.CatalogDtos.AddProductIdentifierCommand;
import com.rewit.application.dto.catalog.CatalogDtos.AssociateProductPresenceCommand;
import com.rewit.application.dto.catalog.CatalogDtos.CreatePlaceCommand;
import com.rewit.application.dto.catalog.CatalogDtos.CreateProductCommand;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProductIdentifierRepository;
import com.rewit.application.port.ProductPresenceRepository;
import com.rewit.application.port.ProductRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Product;
import com.rewit.domain.model.ProductIdentifier;
import com.rewit.domain.model.ProductPresence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: CatalogService (Step 7)")
class CatalogServiceUnitTest {

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductIdentifierRepository productIdentifierRepository;

    @Mock
    private ProductPresenceRepository productPresenceRepository;

    private CatalogService catalogService;

    @BeforeEach
    void setUp() {
        catalogService = new CatalogService(
                placeRepository,
                productRepository,
                productIdentifierRepository,
                productPresenceRepository
        );
    }

    @Test
    @DisplayName("createPlace: Deve criar e salvar um Place com sucesso quando o slug for único")
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
        assertEquals(TargetType.PLACE, created.getTargetType());
        verify(placeRepository).save(any(Place.class));
    }

    @Test
    @DisplayName("createPlace: Deve lançar PLACE_SLUG_ALREADY_EXISTS (409) se slug já estiver em uso")
    void shouldRejectPlaceWhenSlugAlreadyExists() {
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
}
