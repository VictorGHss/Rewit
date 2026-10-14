package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.PlaceAmenity;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Seleção de comodidades de um estabelecimento reivindicado, configurada por uma representação comercial (F2.2).
 *
 * <p>Existir a configuração com a seleção vazia é diferente de não existir configuração: a empresa declarou que o
 * local não oferece nenhuma comodidade do catálogo. A conta comercial e o usuário que gravaram por último ficam
 * registrados; a visibilidade pública depende da elegibilidade corrente dessa representação.
 */
public final class PlaceAmenityConfiguration {

    private final UUID placeId;
    private final UUID businessAccountId;
    private final UUID updatedByUserId;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Set<PlaceAmenity> amenities;

    public PlaceAmenityConfiguration(UUID placeId, UUID businessAccountId, UUID updatedByUserId, Instant createdAt,
                                     Instant updatedAt, Collection<PlaceAmenity> amenities) {
        if (placeId == null) {
            throw new BusinessException("O local é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_PLACE_ID");
        }
        if (businessAccountId == null) {
            throw new BusinessException("A conta comercial é obrigatória", HttpStatus.BAD_REQUEST,
                    "MISSING_BUSINESS_ACCOUNT_ID");
        }
        if (updatedByUserId == null) {
            throw new BusinessException("O usuário responsável é obrigatório", HttpStatus.BAD_REQUEST,
                    "MISSING_ACTOR_USER_ID");
        }
        this.placeId = placeId;
        this.businessAccountId = businessAccountId;
        this.updatedByUserId = updatedByUserId;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.amenities = Collections.unmodifiableSet(distinct(amenities));
    }

    /** Nova configuração (ou substituição integral da anterior) gravada agora pelo ator. */
    public static PlaceAmenityConfiguration create(UUID placeId, UUID businessAccountId, UUID updatedByUserId,
                                                   Collection<PlaceAmenity> amenities, Instant now) {
        Instant at = now != null ? now : Instant.now();
        return new PlaceAmenityConfiguration(placeId, businessAccountId, updatedByUserId, at, at, amenities);
    }

    /**
     * Converte os códigos recebidos na API: lista ausente, elemento nulo, código fora do catálogo ou código repetido
     * são recusados com {@code 400}, antes de qualquer leitura ou escrita.
     */
    public static List<PlaceAmenity> fromCodes(List<String> codes) {
        if (codes == null) {
            throw new BusinessException("A lista de comodidades é obrigatória", HttpStatus.BAD_REQUEST,
                    "MISSING_AMENITIES");
        }
        List<PlaceAmenity> amenities = new ArrayList<>(codes.size());
        for (String code : codes) {
            amenities.add(PlaceAmenity.fromCode(code));
        }
        distinct(amenities);
        return List.copyOf(amenities);
    }

    private static Set<PlaceAmenity> distinct(Collection<PlaceAmenity> amenities) {
        Set<PlaceAmenity> set = EnumSet.noneOf(PlaceAmenity.class);
        if (amenities == null) {
            return set;
        }
        for (PlaceAmenity amenity : amenities) {
            if (amenity == null) {
                throw new BusinessException("Comodidade desconhecida: use um código do catálogo de comodidades",
                        HttpStatus.BAD_REQUEST, "INVALID_AMENITY_CODE");
            }
            if (!set.add(amenity)) {
                throw new BusinessException("A comodidade não pode ser repetida na requisição", HttpStatus.BAD_REQUEST,
                        "DUPLICATE_AMENITY");
            }
        }
        return set;
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public UUID getBusinessAccountId() {
        return businessAccountId;
    }

    public UUID getUpdatedByUserId() {
        return updatedByUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Comodidades selecionadas, na ordem do catálogo; vazio quando a empresa declarou não oferecer nenhuma. */
    public Set<PlaceAmenity> getAmenities() {
        return amenities;
    }
}
