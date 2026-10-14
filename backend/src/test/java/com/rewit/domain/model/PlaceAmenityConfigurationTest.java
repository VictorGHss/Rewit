package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.PlaceAmenity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Domínio: catálogo e configuração de comodidades (F2.2)")
class PlaceAmenityConfigurationTest {

    private final UUID placeId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("Códigos do catálogo são os nomes das constantes e todo rótulo é preenchido")
    void catalogCodesAreStableNames() {
        for (PlaceAmenity amenity : PlaceAmenity.values()) {
            assertEquals(amenity.name(), amenity.getCode());
            assertTrue(amenity.getLabel() != null && !amenity.getLabel().isBlank());
            assertEquals(amenity, PlaceAmenity.fromCode(amenity.getCode()));
        }
    }

    @Test
    @DisplayName("fromCode recusa código desconhecido, nulo, em minúsculas ou com espaços")
    void fromCodeRejectsAnythingOutsideCatalog() {
        for (String code : Arrays.asList("SAUNA", null, "", "wifi", " WIFI", "WIFI ")) {
            BusinessException ex = assertThrows(BusinessException.class, () -> PlaceAmenity.fromCode(code));
            assertEquals("INVALID_AMENITY_CODE", ex.getErrorCode());
            assertEquals(400, ex.getStatus().value());
        }
    }

    @Test
    @DisplayName("fromCodes preserva a ordem recebida e recusa lista ausente, nulo e repetição")
    void fromCodesValidatesWholeList() {
        assertEquals(List.of(PlaceAmenity.PET_FRIENDLY, PlaceAmenity.WIFI),
                PlaceAmenityConfiguration.fromCodes(List.of("PET_FRIENDLY", "WIFI")));
        assertEquals(List.of(), PlaceAmenityConfiguration.fromCodes(List.of()));

        assertEquals("MISSING_AMENITIES", assertThrows(BusinessException.class,
                () -> PlaceAmenityConfiguration.fromCodes(null)).getErrorCode());
        assertEquals("INVALID_AMENITY_CODE", assertThrows(BusinessException.class,
                () -> PlaceAmenityConfiguration.fromCodes(Arrays.asList("WIFI", null))).getErrorCode());
        assertEquals("DUPLICATE_AMENITY", assertThrows(BusinessException.class,
                () -> PlaceAmenityConfiguration.fromCodes(List.of("WIFI", "PARKING", "WIFI"))).getErrorCode());
    }

    @Test
    @DisplayName("Configuração ordena pelo catálogo, aceita seleção vazia e é imutável")
    void configurationOrdersByCatalogAndIsImmutable() {
        Instant now = Instant.parse("2026-10-14T12:00:00Z");
        PlaceAmenityConfiguration configuration = PlaceAmenityConfiguration.create(placeId, accountId, userId,
                List.of(PlaceAmenity.PET_FRIENDLY, PlaceAmenity.WIFI, PlaceAmenity.PARKING), now);

        assertEquals(List.of(PlaceAmenity.WIFI, PlaceAmenity.PARKING, PlaceAmenity.PET_FRIENDLY),
                List.copyOf(configuration.getAmenities()));
        assertEquals(now, configuration.getCreatedAt());
        assertEquals(now, configuration.getUpdatedAt());
        assertThrows(UnsupportedOperationException.class,
                () -> configuration.getAmenities().add(PlaceAmenity.OUTDOOR_SEATING));

        assertTrue(PlaceAmenityConfiguration.create(placeId, accountId, userId, List.of(), now)
                .getAmenities().isEmpty());
    }

    @Test
    @DisplayName("Configuração exige local, conta comercial e autor, e recusa repetição")
    void configurationRequiresOwnershipAndDistinctAmenities() {
        List<PlaceAmenity> wifi = List.of(PlaceAmenity.WIFI);
        assertEquals("MISSING_PLACE_ID", assertThrows(BusinessException.class,
                () -> PlaceAmenityConfiguration.create(null, accountId, userId, wifi, null)).getErrorCode());
        assertEquals("MISSING_BUSINESS_ACCOUNT_ID", assertThrows(BusinessException.class,
                () -> PlaceAmenityConfiguration.create(placeId, null, userId, wifi, null)).getErrorCode());
        assertEquals("MISSING_ACTOR_USER_ID", assertThrows(BusinessException.class,
                () -> PlaceAmenityConfiguration.create(placeId, accountId, null, wifi, null)).getErrorCode());
        assertEquals("DUPLICATE_AMENITY", assertThrows(BusinessException.class,
                () -> PlaceAmenityConfiguration.create(placeId, accountId, userId,
                        List.of(PlaceAmenity.WIFI, PlaceAmenity.WIFI), null)).getErrorCode());
    }
}
