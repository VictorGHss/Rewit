package com.rewit.presentation.dto.catalog;

import com.rewit.application.dto.catalog.PlaceBusinessProfileDtos.UpdatePlaceBusinessProfileCommand;
import com.rewit.domain.model.PlaceBusinessProfile;
import com.rewit.domain.model.PlaceContactInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Contratos REST do perfil comercial (descrição e contatos públicos) de estabelecimentos reivindicados.
 */
public final class PlaceBusinessProfilePresentationDtos {

    private PlaceBusinessProfilePresentationDtos() {
    }

    @Schema(description = "Substituição integral do perfil comercial: campo ausente, null ou só com espaços fica sem "
            + "valor e apaga o que havia antes")
    public record UpdatePlaceBusinessProfileRequest(
            @NotNull(message = "O identificador da conta comercial é obrigatório")
            @Schema(description = "Conta comercial aprovada que reivindica o local")
            UUID businessAccountId,

            @Size(max = PlaceBusinessProfile.MAX_DESCRIPTION_LENGTH,
                    message = "A descrição deve ter até 2000 caracteres")
            @Schema(nullable = true, maxLength = PlaceBusinessProfile.MAX_DESCRIPTION_LENGTH)
            String description,

            @Size(max = PlaceContactInfo.MAX_PHONE_LENGTH, message = "O telefone deve ter até 32 caracteres")
            @Schema(example = "+55 (41) 3333-4444", nullable = true,
                    description = "8 a 15 dígitos; \"+\" opcional no início; espaços, parênteses, pontos e hífens "
                            + "como separadores. Guardado como enviado")
            String phone,

            @Size(max = PlaceContactInfo.MAX_URL_LENGTH, message = "O endereço do site deve ter até 500 caracteres")
            @Schema(example = "https://www.exemplo.com.br", nullable = true, description = "Link http ou https")
            String websiteUrl,

            @Size(max = PlaceContactInfo.MAX_URL_LENGTH,
                    message = "O endereço do Instagram deve ter até 500 caracteres")
            @Schema(example = "https://www.instagram.com/exemplo", nullable = true, description = "Link http ou https")
            String instagramUrl,

            @Size(max = PlaceContactInfo.MAX_URL_LENGTH,
                    message = "O endereço do Facebook deve ter até 500 caracteres")
            @Schema(example = "https://www.facebook.com/exemplo", nullable = true, description = "Link http ou https")
            String facebookUrl
    ) {
        public UpdatePlaceBusinessProfileCommand toCommand(UUID actorUserId, UUID placeId) {
            return new UpdatePlaceBusinessProfileCommand(actorUserId, placeId, businessAccountId, description, phone,
                    websiteUrl, instagramUrl, facebookUrl);
        }
    }
}
