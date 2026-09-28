package com.rewit.presentation.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload de requisição para alteração de senha da conta local autenticada (POST /api/v1/me/password).
 * Protege a fronteira HTTP contra payloads malformados ou fora das restrições sintáticas,
 * retornando HTTP 400 Bad Request através do Bean Validation e GlobalExceptionHandler.
 */
public record ChangePasswordRequest(
        @NotBlank(message = "A senha atual é obrigatória")
        String currentPassword,

        @NotBlank(message = "A nova senha é obrigatória")
        @Size(min = 8, max = 128, message = "A nova senha deve conter entre 8 e 128 caracteres")
        String newPassword
) {}
