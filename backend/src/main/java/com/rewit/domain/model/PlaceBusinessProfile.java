package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.Objects;

/**
 * Perfil comercial editável de um estabelecimento: descrição pública ({@code places.description}) e contatos.
 *
 * <p>É uma substituição integral: cada campo ausente, {@code null} ou só com espaços fica sem valor, apagando o que
 * havia antes. Nome, endereço, coordenadas, status, titularidade e verificação não fazem parte do perfil.
 */
public record PlaceBusinessProfile(String description, PlaceContactInfo contact) {

    /** Mesmo limite que {@link Place} aplica à descrição. */
    public static final int MAX_DESCRIPTION_LENGTH = 2000;

    public PlaceBusinessProfile {
        Objects.requireNonNull(contact, "contact must not be null");
    }

    /**
     * Valida e normaliza o perfil inteiro antes de qualquer leitura ou escrita.
     *
     * @throws BusinessException {@code 400 INVALID_DESCRIPTION} ou um dos códigos de {@link PlaceContactInfo#of}
     */
    public static PlaceBusinessProfile of(String description, String phone, String websiteUrl, String instagramUrl,
                                          String facebookUrl) {
        return new PlaceBusinessProfile(normalizeDescription(description),
                PlaceContactInfo.of(phone, websiteUrl, instagramUrl, facebookUrl));
    }

    static String normalizeDescription(String description) {
        String value = PlaceContactInfo.blankToNull(description);
        if (value != null && value.length() > MAX_DESCRIPTION_LENGTH) {
            throw new BusinessException("A descrição deve ter até " + MAX_DESCRIPTION_LENGTH + " caracteres",
                    HttpStatus.BAD_REQUEST, "INVALID_DESCRIPTION");
        }
        return value;
    }
}
