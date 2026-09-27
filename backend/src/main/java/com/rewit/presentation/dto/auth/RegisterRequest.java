package com.rewit.presentation.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "O e-mail é obrigatório")
        @Email(message = "Formato de e-mail inválido")
        String email,

        @NotBlank(message = "A senha é obrigatória")
        @Size(min = 8, max = 128, message = "A senha deve ter entre 8 e 128 caracteres")
        String password,

        @NotBlank(message = "O nome de usuário (@handle) é obrigatório")
        @Pattern(regexp = "^@?[a-zA-Z0-9_]{3,30}$", message = "O handle deve ter entre 3 e 30 caracteres alfanuméricos ou sublinhado")
        String handle,

        @NotBlank(message = "O nome de exibição é obrigatório")
        @Size(min = 2, max = 100, message = "O nome de exibição deve ter entre 2 e 100 caracteres")
        String displayName
) {}
