package com.rewit.presentation.dto.business;

import com.rewit.domain.model.BusinessResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Payload de entrada para publicação de resposta oficial de empresa (C11).
 *
 * <p>O autor é extraído exclusivamente do token JWT. O alvo ({@code reviewId} ou {@code discussionId})
 * é extraído do caminho da requisição HTTP.
 */
public record PublishBusinessResponseRequest(
        @NotNull(message = "O identificador da conta comercial é obrigatório")
        UUID businessAccountId,

        @NotNull(message = "O identificador do local representado é obrigatório")
        UUID placeId,

        @NotBlank(message = "O conteúdo da resposta é obrigatório")
        @Size(max = BusinessResponse.MAX_CONTENT_LENGTH, message = "O conteúdo da resposta excede o limite permitido")
        String content
) {
}
