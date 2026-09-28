package com.rewit.infrastructure.persistence;

import com.rewit.application.port.*;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.enums.VerificationStatus;
import com.rewit.domain.model.*;
import com.rewit.infrastructure.persistence.entity.PlaceJpaEntity;
import com.rewit.infrastructure.persistence.repository.PlaceJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração de Persistência e Integridade do Catálogo (Step 7)")
class CatalogPersistenceIntegrationTest {

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductIdentifierRepository productIdentifierRepository;

    @Autowired
    private ProductPresenceRepository productPresenceRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    @Autowired
    private PlaceJpaRepository placeJpaRepository;

    @Test
    @DisplayName("1. Place: Salvar Place deve criar automaticamente RateableTarget raiz e persistir no PostgreSQL com PostGIS")
    void shouldPersistPlaceAndAutoCreateRateableTargetRoot() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
                null,
                "Café Curitibano " + suffix,
                "cafe-curitibano-" + suffix,
                "CAFE",
                "Café artesanal no centro",
                "Rua XV de Novembro",
                "150",
                "Centro",
                "Curitiba",
                "PR",
                "BR",
                -25.4297,
                -49.2719,
                50,
                "USER",
                false,
                null,
                "ACTIVE"
        );

        Place saved = placeRepository.save(place);
        assertNotNull(saved);
        assertNotNull(saved.getId());

        // Comprova que o RateableTarget raiz foi criado com target_type = PLACE
        Optional<RateableTarget> rootTarget = rateableTargetRepository.findById(saved.getId());
        assertTrue(rootTarget.isPresent());
        assertEquals(TargetType.PLACE, rootTarget.get().getTargetType());

        // Comprova recuperação com coordenadas PostGIS
        Optional<Place> retrieved = placeRepository.findById(saved.getId());
        assertTrue(retrieved.isPresent());
        assertEquals("Café Curitibano " + suffix, retrieved.get().getName());
        assertEquals("150", retrieved.get().getStreetNumber());
        assertEquals("Centro", retrieved.get().getNeighborhood());
        assertEquals(-25.4297, retrieved.get().getLatitude(), 0.0001);
        assertEquals(-49.2719, retrieved.get().getLongitude(), 0.0001);
    }

    @Test
    @DisplayName("2. Integridade: Trigger do PostgreSQL deve rejeitar Place vinculado a RateableTarget do tipo PRODUCT")
    void shouldRejectPlaceWhenRateableTargetIsOfWrongType() {
        UUID mismatchedId = UUID.randomUUID();

        // 1. Cria um RateableTarget do tipo PRODUCT
        RateableTarget productTarget = new RateableTarget(mismatchedId, TargetType.PRODUCT);
        rateableTargetRepository.save(productTarget);

        // 2. Tenta forçar inserção direta na tabela places com esse ID
        Place mismatchedPlace = new Place(
                mismatchedId,
                "Lugar Incoerente",
                "lugar-incoerente-" + mismatchedId,
                "BAR",
                "Desc",
                "Rua X",
                "Curitiba",
                "PR",
                "BR",
                -25.0,
                -49.0,
                50,
                "USER",
                false,
                null,
                "ACTIVE"
        );

        Exception ex = assertThrows(Exception.class, () -> {
            placeJpaRepository.saveAndFlush(PlaceJpaEntity.fromDomain(mismatchedPlace));
        });

        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("incoerência de especialização") || msg.contains("places") || msg.contains("rateable_target"));
    }

    @Test
    @DisplayName("3. Unicidade: Constraint uq_places_slug deve rejeitar slugs duplicados no PostgreSQL")
    void shouldRejectDuplicatePlaceSlugAtDatabaseLevel() {
        String slug = "slug-conflito-" + UUID.randomUUID().toString().substring(0, 8);

        Place placeA = new Place(
                null, "Lugar A", slug, "BAR", "Desc",
                "Rua 1", "Curitiba", "PR", "BR", -25.4, -49.2, 50, "USER", false, null, "ACTIVE"
        );
        placeRepository.save(placeA);

        Place placeB = new Place(
                null, "Lugar B", slug, "CAFE", "Outro",
                "Rua 2", "Curitiba", "PR", "BR", -25.5, -49.3, 50, "USER", false, null, "ACTIVE"
        );

        assertThrows(Exception.class, () -> {
            placeJpaRepository.saveAndFlush(PlaceJpaEntity.fromDomain(placeB));
        });
    }

    @Test
    @DisplayName("4. PostGIS: Consulta geoespacial findNearby deve filtrar e ordenar corretamente por distância")
    void shouldFilterAndOrderPlacesByProximityUsingPostGis() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // Ponto de referência: Praça Tiradentes, Curitiba (-25.4297, -49.2719)
        // Local 1: Rua XV de Novembro (aprox 300m)
        Place placeCenter = new Place(
                null, "Centro " + suffix, "centro-" + suffix, "CAFE", "Perto",
                "Rua XV", "Curitiba", "PR", "BR", -25.4310, -49.2700, 50, "USER", false, null, "ACTIVE"
        );
        placeRepository.save(placeCenter);

        // Local 2: Batel (aprox 2.2 km)
        Place placeBatel = new Place(
                null, "Batel " + suffix, "batel-" + suffix, "RESTAURANTE", "Médio",
                "Av Batel", "Curitiba", "PR", "BR", -25.4440, -49.2880, 50, "USER", false, null, "ACTIVE"
        );
        placeRepository.save(placeBatel);

        // Local 3: Ponta Grossa (aprox 100 km)
        Place placeFar = new Place(
                null, "Ponta Grossa " + suffix, "pg-" + suffix, "HOTEL", "Longe",
                "Av Centro", "Ponta Grossa", "PR", "BR", -25.0950, -50.1610, 50, "USER", false, null, "ACTIVE"
        );
        placeRepository.save(placeFar);

        // Busca com raio de 1000m (deve trazer apenas placeCenter)
        List<Place> results1km = placeRepository.findNearby(-25.4297, -49.2719, 1000.0);
        assertTrue(results1km.stream().anyMatch(p -> p.getId().equals(placeCenter.getId())));
        assertFalse(results1km.stream().anyMatch(p -> p.getId().equals(placeBatel.getId())));
        assertFalse(results1km.stream().anyMatch(p -> p.getId().equals(placeFar.getId())));

        // Busca com raio de 5000m (deve trazer placeCenter e placeBatel, ordenados por distância)
        List<Place> results5km = placeRepository.findNearby(-25.4297, -49.2719, 5000.0);
        assertTrue(results5km.stream().anyMatch(p -> p.getId().equals(placeCenter.getId())));
        assertTrue(results5km.stream().anyMatch(p -> p.getId().equals(placeBatel.getId())));
        assertFalse(results5km.stream().anyMatch(p -> p.getId().equals(placeFar.getId())));

        // Comprovar ordenação por distância (placeCenter a ~300m vem antes de placeBatel a ~2.2km)
        int idxCenter = results5km.indexOf(results5km.stream().filter(p -> p.getId().equals(placeCenter.getId())).findFirst().orElseThrow());
        int idxBatel = results5km.indexOf(results5km.stream().filter(p -> p.getId().equals(placeBatel.getId())).findFirst().orElseThrow());
        assertTrue(idxCenter < idxBatel, "Local mais próximo deve preceder o mais distante na ordenação");

        // Raio zero ou negativo deve retornar lista vazia
        assertTrue(placeRepository.findNearby(-25.4297, -49.2719, 0.0).isEmpty());
        assertTrue(placeRepository.findNearby(-25.4297, -49.2719, -50.0).isEmpty());
    }

    @Test
    @DisplayName("5. Product e Identifiers: Salvar Product deve criar root e rejeitar identificador duplicado")
    void shouldPersistProductAndEnforceIdentifierUniqueness() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Product product = new Product(
                null, "Cerveja Artesanal " + suffix, "Lúpulo Real", "IPA 500ml", "Cerveja forte", "BEBIDAS", null, "ACTIVE"
        );

        Product savedProd = productRepository.save(product);
        assertNotNull(savedProd.getId());

        // Comprova root RateableTarget
        Optional<RateableTarget> root = rateableTargetRepository.findById(savedProd.getId());
        assertTrue(root.isPresent());
        assertEquals(TargetType.PRODUCT, root.get().getTargetType());

        // Adiciona identificador EAN
        String barcode = "789" + suffix;
        ProductIdentifier identifier = new ProductIdentifier(null, savedProd.getId(), "EAN", barcode);
        ProductIdentifier savedId = productIdentifierRepository.save(identifier);
        assertNotNull(savedId.getId());

        // Tentar duplicar o mesmo EAN (mesmo tipo e valor) deve falhar por constraint uq_product_identifier
        ProductIdentifier dup = new ProductIdentifier(null, savedProd.getId(), "EAN", barcode);
        assertThrows(Exception.class, () -> productIdentifierRepository.save(dup));

        // Tentar salvar identificador com productId inexistente deve falhar por FK constraint
        ProductIdentifier orphan = new ProductIdentifier(null, UUID.randomUUID(), "EAN", "999" + suffix);
        assertThrows(Exception.class, () -> productIdentifierRepository.save(orphan));
    }

    @Test
    @DisplayName("6. ProductPresence: Deve persistir relação Product <-> Place e rejeitar duplicidade de par")
    void shouldPersistProductPresenceAndRejectDuplicatePair() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Place place = new Place(
                null, "Supermercado " + suffix, "mercado-" + suffix, "MERCADO", "Desc",
                "Rua M", "Curitiba", "PR", "BR", -25.4, -49.2, 50, "USER", false, null, "ACTIVE"
        );
        Place savedPlace = placeRepository.save(place);

        Product product = new Product(
                null, "Chocolate " + suffix, "Cacau Puro", "Barra 100g", "Doces", "DOCES", null, "ACTIVE"
        );
        Product savedProduct = productRepository.save(product);

        // 1. Associa produto ao local
        ProductPresence presence = new ProductPresence(
                null,
                savedProduct.getId(),
                savedPlace.getId(),
                null,
                VerificationStatus.UNCONFIRMED,
                "AVAILABLE"
        );
        ProductPresence savedPresence = productPresenceRepository.save(presence);
        assertNotNull(savedPresence.getId());

        // 2. Consulta presença pelo par
        Optional<ProductPresence> found = productPresenceRepository.findByProductIdAndPlaceId(savedProduct.getId(), savedPlace.getId());
        assertTrue(found.isPresent());
        assertEquals(savedPresence.getId(), found.get().getId());

        // 3. Tentar inserir duplicidade com o mesmo par (product_id, place_id) deve falhar por constraint uq_product_place
        assertThrows(Exception.class, () -> {
            // Chamada direta forçando inserção de nova entidade
            productPresenceRepository.save(new ProductPresence(
                    UUID.randomUUID(), savedProduct.getId(), savedPlace.getId(), null, VerificationStatus.APPROVED, "AVAILABLE"
            ));
        });

        // 4. FK inexistente deve falhar
        assertThrows(Exception.class, () -> {
            productPresenceRepository.save(new ProductPresence(
                    null, UUID.randomUUID(), savedPlace.getId(), null, null, null
            ));
        });
        assertThrows(Exception.class, () -> {
            productPresenceRepository.save(new ProductPresence(
                    null, savedProduct.getId(), UUID.randomUUID(), null, null, null
            ));
        });
    }
}
