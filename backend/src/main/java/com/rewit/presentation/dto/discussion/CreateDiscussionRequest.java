package com.rewit.presentation.dto.discussion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Payload para criação de nova discussão ou resposta em uma avaliação (Step 20.0).
 */
public record CreateDiscussionRequest(
        @NotBlank(message = "O conteúdo do comentário é obrigatório")
        @Size(max = 2000, message = "O conteúdo do comentário excede o limite máximo permitido de 2000 caracteres")
        String content,

        UUID parentId
) {}
