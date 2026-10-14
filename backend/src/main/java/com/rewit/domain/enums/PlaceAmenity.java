package com.rewit.domain.enums;

import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * Catálogo controlado das comodidades factuais que um estabelecimento reivindicado pode declarar (F2.2).
 *
 * <p>São características físicas verificáveis do local, não serviços avaliáveis: atendimento, delivery e drive-thru
 * continuam em {@code services} ({@code PlaceService}, um {@code RateableTarget}). Não há texto livre: o código é o
 * nome da constante, estável na API e no banco. Incluir uma comodidade exige uma nova constante e uma migração que
 * amplie {@code chk_place_amenity_code}; remover exige antes limpar as seleções gravadas.
 */
public enum PlaceAmenity {
    WIFI("Wi-Fi para clientes"),
    PARKING("Estacionamento"),
    WHEELCHAIR_ACCESSIBLE("Acesso para cadeira de rodas"),
    ACCESSIBLE_RESTROOM("Banheiro acessível"),
    CUSTOMER_RESTROOM("Banheiro para clientes"),
    AIR_CONDITIONING("Ar-condicionado"),
    OUTDOOR_SEATING("Área externa"),
    PET_FRIENDLY("Aceita animais de estimação");

    private final String label;

    PlaceAmenity(String label) {
        this.label = label;
    }

    public String getCode() {
        return name();
    }

    public String getLabel() {
        return label;
    }

    /** Código exato da API; ausente ou fora do catálogo responde {@code 400 INVALID_AMENITY_CODE}. */
    public static PlaceAmenity fromCode(String code) {
        if (code != null) {
            for (PlaceAmenity amenity : values()) {
                if (amenity.name().equals(code)) {
                    return amenity;
                }
            }
        }
        throw new BusinessException("Comodidade desconhecida: use um código do catálogo de comodidades",
                HttpStatus.BAD_REQUEST, "INVALID_AMENITY_CODE");
    }
}
