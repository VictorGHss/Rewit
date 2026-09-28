package com.rewit.presentation.dto.user;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload de requisição para atualização parcial de perfil do usuário autenticado (PATCH /api/v1/me/profile).
 * Expõe somente campos mutáveis e permitidos para edição pelo próprio usuário.
 */
public record UpdateProfileRequest(
        @Pattern(regexp = "^\\s*@?[a-zA-Z0-9_]{3,30}\\s*$", message = "O handle deve ter entre 3 e 30 caracteres alfanuméricos ou sublinhado")
        String handle,

        @Size(min = 2, max = 100, message = "O nome de exibição deve ter entre 2 e 100 caracteres")
        String displayName,

        @Size(max = 500, message = "A bio deve ter no máximo 500 caracteres")
        String bio,

        Boolean isAnonymousDefault
) {}
