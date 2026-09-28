package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.enums.VerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Domínio: Invariantes do Catálogo (Place, Product, Identifiers, Presence e RateableTarget)")
class CatalogDomainInvariantsTest {

    @Test
    @DisplayName("RateableTarget: Deve exigir TargetType não nulo e gerar ID se não fornecido")
    void shouldValidateRateableTargetInvariants() {
        assertThrows(BusinessException.class, () -> new RateableTarget(null, null),
                "TargetType nulo deve lançar BusinessException");

        RateableTarget target = new RateableTarget(null, TargetType.PLACE);
        assertNotNull(target.getId());
        assertEquals(TargetType.PLACE, target.getTargetType());
        assertNotNull(target.getCreatedAt());
    }

    @Test
    @DisplayName("Place: Deve validar nome, slug, coordenadas WGS84 e raio de tolerância")
    void shouldValidatePlaceInvariants() {
        // Nome obrigatório
        assertThrows(BusinessException.class, () -> new Place(
                null, "   ", "slug-valido", "BAR", "Desc",
                "Rua 1", "Curitiba", "PR", "BR", -25.42, -49.27, 50, "USER", false, null, "ACTIVE"
        ));

        // Slug obrigatório
        assertThrows(BusinessException.class, () -> new Place(
                null, "Lugar Válido", "  ", "BAR", "Desc",
                "Rua 1", "Curitiba", "PR", "BR", -25.42, -49.27, 50, "USER", false, null, "ACTIVE"
        ));

        // Coordenadas fora dos limites WGS84
        assertThrows(BusinessException.class, () -> new Place(
                null, "Lugar", "lugar", "BAR", "Desc",
                "Rua 1", "Curitiba", "PR", "BR", -95.0, -49.27, 50, "USER", false, null, "ACTIVE"
        ));
        assertThrows(BusinessException.class, () -> new Place(
                null, "Lugar", "lugar", "BAR", "Desc",
                "Rua 1", "Curitiba", "PR", "BR", -25.0, 190.0, 50, "USER", false, null, "ACTIVE"
        ));

        // Raio de tolerância não positivo
        assertThrows(BusinessException.class, () -> new Place(
                null, "Lugar", "lugar", "BAR", "Desc",
                "Rua 1", "Curitiba", "PR", "BR", -25.0, -49.0, 0, "USER", false, null, "ACTIVE"
        ));

        // Construção válida com normalização de slug
        Place validPlace = new Place(
                null, "  Café & Livros  ", "  CAFE-E-LIVROS  ", "CAFE", "Cafeteria cultural",
                "Rua das Flores", "100", "Centro", "Curitiba", "PR", "BR",
                -25.4297, -49.2719, 60, "USER", true, null, "ACTIVE"
        );
        assertEquals("Café & Livros", validPlace.getName());
        assertEquals("cafe-e-livros", validPlace.getSlug(), "Slug deve ser normalizado para minúsculas e sem espaços");
        assertEquals(TargetType.PLACE, validPlace.getTargetType());
        assertEquals("100", validPlace.getStreetNumber());
        assertEquals("Centro", validPlace.getNeighborhood());
    }

    @Test
    @DisplayName("Product: Deve validar nome obrigatório e herdar TargetType.PRODUCT")
    void shouldValidateProductInvariants() {
        assertThrows(BusinessException.class, () -> new Product(
                null, "   ", "Marca", "Modelo", "Desc", "CAT", null, "ACTIVE"
        ));

        Product product = new Product(
                null, "  Café Especial 500g  ", "  Grão Nobre  ", "Catuaí", "Café torrado", "ALIMENTOS", "http://img.com", "ACTIVE"
        );
        assertEquals("Café Especial 500g", product.getName());
        assertEquals("Grão Nobre", product.getBrand());
        assertEquals(TargetType.PRODUCT, product.getTargetType());
        assertEquals("ACTIVE", product.getStatus());
    }

    @Test
    @DisplayName("ProductIdentifier: Deve exigir productId, identifierType e identifierValue não nulos")
    void shouldValidateProductIdentifierInvariants() {
        UUID prodId = UUID.randomUUID();

        assertThrows(BusinessException.class, () -> new ProductIdentifier(null, null, "EAN", "7891234567890"));
        assertThrows(BusinessException.class, () -> new ProductIdentifier(null, prodId, "  ", "7891234567890"));
        assertThrows(BusinessException.class, () -> new ProductIdentifier(null, prodId, "EAN", "   "));

        ProductIdentifier identifier = new ProductIdentifier(null, prodId, "  ean  ", "  7891234567890  ");
        assertEquals("EAN", identifier.getIdentifierType(), "Tipo de identificador deve ser normalizado para uppercase");
        assertEquals("7891234567890", identifier.getIdentifierValue(), "Valor deve ter trim()");
        assertEquals(prodId, identifier.getProductId());
    }

    @Test
    @DisplayName("ProductPresence: Deve exigir productId e placeId não nulos e inicializar status padrão")
    void shouldValidateProductPresenceInvariants() {
        UUID prodId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        assertThrows(BusinessException.class, () -> new ProductPresence(null, null, placeId, userId, null, null));
        assertThrows(BusinessException.class, () -> new ProductPresence(null, prodId, null, userId, null, null));

        ProductPresence presence = new ProductPresence(null, prodId, placeId, userId, null, null);
        assertEquals(prodId, presence.getProductId());
        assertEquals(placeId, presence.getPlaceId());
        assertEquals(userId, presence.getReportedByUserId());
        assertEquals(VerificationStatus.UNCONFIRMED, presence.getVerificationStatus(), "Status padrão deve ser UNCONFIRMED");
        assertEquals("AVAILABLE", presence.getStatus(), "Disponibilidade padrão deve ser AVAILABLE");
        assertNotNull(presence.getFirstDiscoveredAt());
        assertNotNull(presence.getLastConfirmedAt());
    }
}
