package com.rewit.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Domínio: Identidade Externa e PlaceCandidate (Step 8)")
class PlaceCandidateExternalIdentityTest {

    @Test
    @DisplayName("PlaceCandidate deve manter identificador externo desacoplado da chave primária interna (Place.id)")
    void candidateShouldMaintainExternalIdIsolatedFromInternalId() {
        String googlePlaceId = "ChIJN1t_tDeuEmsRUsoyG83frY4";

        PlaceCandidate candidate = new PlaceCandidate(
                "GOOGLE",
                googlePlaceId,
                "Restaurante Ponta Grossa",
                "Av. Vicente Machado, 100",
                -25.0916,
                -50.1581,
                List.of("restaurant")
        );

        // O candidato possui apenas identidade externa e atributos transitórios
        assertEquals("GOOGLE", candidate.getProvider());
        assertEquals(googlePlaceId, candidate.getExternalId());
        assertEquals("Restaurante Ponta Grossa", candidate.getDisplayName());

        // Comprovação: Uma entidade Place do Rewit possui identidade primária própria (UUID gerado internamente)
        UUID internalPlaceId = UUID.randomUUID();
        Place place = new Place(
                internalPlaceId,
                candidate.getDisplayName(),
                "restaurante-ponta-grossa",
                "GASTRONOMIA",
                "Descrição local",
                candidate.getFormattedAddress(),
                "Ponta Grossa",
                "PR",
                "BR",
                candidate.getLatitude(),
                candidate.getLongitude(),
                50,
                "USER",
                false,
                null,
                "ACTIVE"
        );

        // O Place.id NÃO é e nem pode ser a string do Google Place ID
        assertNotEquals(googlePlaceId, place.getId().toString());
        assertEquals(internalPlaceId, place.getId());

        // O vínculo entre a identidade do Rewit e a externa pertence a PlaceExternalReference
        PlaceExternalReference ref = new PlaceExternalReference(
                UUID.randomUUID(),
                place.getId(),
                candidate.getProvider(),
                candidate.getExternalId(),
                "{\"types\":[\"restaurant\"]}"
        );

        assertEquals(place.getId(), ref.getPlaceId());
        assertEquals("GOOGLE", ref.getProvider());
        assertEquals(googlePlaceId, ref.getExternalId());
    }

    @Test
    @DisplayName("PlaceCandidate deve validar obrigatoriedade de campos essenciais e consistência de coordenadas")
    void candidateShouldValidateIntegrity() {
        assertThrows(RuntimeException.class, () ->
                new PlaceCandidate(null, "id_123", "Nome", "Endereço", -25.0, -50.0, List.of())
        );

        assertThrows(RuntimeException.class, () ->
                new PlaceCandidate("GOOGLE", "", "Nome", "Endereço", -25.0, -50.0, List.of())
        );

        assertThrows(RuntimeException.class, () ->
                new PlaceCandidate("GOOGLE", "id_123", "", "Endereço", -25.0, -50.0, List.of())
        );

        assertThrows(RuntimeException.class, () ->
                new PlaceCandidate("GOOGLE", "id_123", "Nome", "Endereço", 95.0, -50.0, List.of())
        );

        assertThrows(RuntimeException.class, () ->
                new PlaceCandidate("GOOGLE", "id_123", "Nome", "Endereço", -25.0, -190.0, List.of())
        );
    }

    @Test
    @DisplayName("PlaceCandidate deve proteger a lista de atribuições contra mutabilidade externa e garantir lista vazia quando ausente")
    void candidateShouldProtectAttributionsImmutability() {
        PlaceCandidate candidateWithoutAttrs = new PlaceCandidate(
                "GOOGLE", "ext_1", "Lugar 1", "Rua 1", -25.0, -50.0, List.of()
        );
        assertNotNull(candidateWithoutAttrs.getAttributions());
        assertTrue(candidateWithoutAttrs.getAttributions().isEmpty());

        PlaceAttribution attr = new PlaceAttribution("OpenStreetMap", "https://www.openstreetmap.org");
        PlaceCandidate candidateWithAttrs = new PlaceCandidate(
                "GOOGLE", "ext_2", "Lugar 2", "Rua 2", -25.0, -50.0, List.of(), List.of(attr)
        );

        assertEquals(1, candidateWithAttrs.getAttributions().size());
        assertEquals("OpenStreetMap", candidateWithAttrs.getAttributions().get(0).provider());
        assertEquals("https://www.openstreetmap.org", candidateWithAttrs.getAttributions().get(0).providerUri());

        assertThrows(UnsupportedOperationException.class, () ->
                candidateWithAttrs.getAttributions().add(new PlaceAttribution("Outro", "http://outro"))
        );
    }
}
