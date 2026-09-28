package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.infrastructure.persistence.entity.PlaceExternalReferenceJpaEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários de Domínio e Mapeamento de PlaceExternalReference (Step 9.2)")
class PlaceExternalReferenceTest {

    @Test
    @DisplayName("1. Criação válida com normalização de provider (uppercase) e externalId (trim)")
    void shouldCreateWithValidDataAndNormalize() {
        UUID placeId = UUID.randomUUID();
        PlaceExternalReference ref = new PlaceExternalReference(
                null,
                placeId,
                "  google  ",
                "  ChIJN1t_tDeuEmsRUsoyG83frY4  ",
                null
        );

        assertNotNull(ref.getId(), "Deve gerar UUID aleatório caso nulo");
        assertEquals(placeId, ref.getPlaceId());
        assertEquals("GOOGLE", ref.getProvider(), "Provider deve ser normalizado em maiúsculas");
        assertEquals("ChIJN1t_tDeuEmsRUsoyG83frY4", ref.getExternalId(), "ExternalId deve ser trimmed");
        assertNull(ref.getMetadataJson());
        assertNotNull(ref.getCreatedAt());
    }

    @Test
    @DisplayName("2. Invariante: rejeitar placeId nulo")
    void shouldRejectNullPlaceId() {
        BusinessException ex = assertThrows(BusinessException.class, () -> {
            new PlaceExternalReference(null, null, "GOOGLE", "ChIJ123", null);
        });
        assertEquals("MISSING_PLACE_ID", ex.getErrorCode());
    }

    @Test
    @DisplayName("3. Invariante: rejeitar provider nulo ou em branco")
    void shouldRejectInvalidProvider() {
        UUID placeId = UUID.randomUUID();
        assertThrows(BusinessException.class, () -> {
            new PlaceExternalReference(null, placeId, null, "ChIJ123", null);
        });
        assertThrows(BusinessException.class, () -> {
            new PlaceExternalReference(null, placeId, "   ", "ChIJ123", null);
        });
    }

    @Test
    @DisplayName("4. Invariante: rejeitar externalId nulo ou em branco")
    void shouldRejectInvalidExternalId() {
        UUID placeId = UUID.randomUUID();
        assertThrows(BusinessException.class, () -> {
            new PlaceExternalReference(null, placeId, "GOOGLE", null, null);
        });
        assertThrows(BusinessException.class, () -> {
            new PlaceExternalReference(null, placeId, "GOOGLE", "   ", null);
        });
    }

    @Test
    @DisplayName("5. Mapeamento Bidirecional Entity ↔ Domain")
    void shouldMapBidirectionallyEntityAndDomain() {
        UUID id = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        Instant now = Instant.now();

        PlaceExternalReference domain = new PlaceExternalReference(
                id,
                placeId,
                "GOOGLE",
                "ChIJ_TEST_MAPPING",
                "{\"custom_key\": \"value\"}"
        );

        PlaceExternalReferenceJpaEntity entity = PlaceExternalReferenceJpaEntity.fromDomain(domain);
        assertNotNull(entity);
        assertEquals(id, entity.getId());
        assertEquals(placeId, entity.getPlaceId());
        assertEquals("GOOGLE", entity.getProvider());
        assertEquals("ChIJ_TEST_MAPPING", entity.getExternalId());
        assertEquals("{\"custom_key\": \"value\"}", entity.getMetadataJson());

        PlaceExternalReference convertedDomain = entity.toDomain();
        assertNotNull(convertedDomain);
        assertEquals(id, convertedDomain.getId());
        assertEquals(placeId, convertedDomain.getPlaceId());
        assertEquals("GOOGLE", convertedDomain.getProvider());
        assertEquals("ChIJ_TEST_MAPPING", convertedDomain.getExternalId());
        assertEquals("{\"custom_key\": \"value\"}", convertedDomain.getMetadataJson());

        assertNotNull(domain.getCreatedAt());
        assertFalse(domain.getCreatedAt().isBefore(now.minusSeconds(1)));
        assertEquals(domain.getCreatedAt(), entity.getCreatedAt(), "CreatedAt deve ser preservado na conversão para Entity");
        assertNotNull(convertedDomain.getCreatedAt(), "CreatedAt de convertedDomain não deve ser nulo");
    }

    @Test
    @DisplayName("6. Proteção Zero-Store: garantir que a entidade é puramente de identidade externa e não possui colunas de conteúdo Google")
    void shouldEnforceZeroStoreBoundaryOnExternalReference() {
        UUID placeId = UUID.randomUUID();
        PlaceExternalReference ref = new PlaceExternalReference(
                null,
                placeId,
                "GOOGLE",
                "ChIJ_VALID_PLACE_ID",
                null // Sem payload Google
        );

        // A entidade deve carregar unicamente a identidade de vinculação externa
        assertEquals("GOOGLE", ref.getProvider());
        assertEquals("ChIJ_VALID_PLACE_ID", ref.getExternalId());
        assertNull(ref.getMetadataJson(), "Metadata deve ser nulo no fluxo padrão");
    }

    @Test
    @DisplayName("7. Normalização Determinística de Provider com Locale.ROOT e Preservação de Identificador Opaco")
    void shouldNormalizeProviderDeterministicallyAndPreserveOpaqueIdentifier() {
        UUID placeId = UUID.randomUUID();
        // Provider em minúsculas com espaços
        String rawProvider = "  google  ";
        // Identificador opaco com caracteres especiais válidos (hífen, underline) e espaços laterais que devem ser trimados
        String rawExternalId = "  ChIJ-test_opaque_ID-123_XYZ  ";

        PlaceExternalReference ref = new PlaceExternalReference(
                null,
                placeId,
                rawProvider,
                rawExternalId,
                null
        );

        assertEquals("GOOGLE", ref.getProvider(), "Provider deve ser normalizado para uppercase determinístico (Locale.ROOT)");
        assertEquals("ChIJ-test_opaque_ID-123_XYZ", ref.getExternalId(), "ExternalId deve ter bordas sanitizadas com trim mas preservar sequência opaca interna intacta");
    }
}
