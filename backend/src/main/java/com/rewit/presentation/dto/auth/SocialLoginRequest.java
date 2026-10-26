package com.rewit.presentation.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login social: somente o ID token devolvido pelo provedor e o nonce emitido pelo Rewit. E-mail, nome, provedor e
 * identificador externo vêm exclusivamente das claims do token validado.
 */
public record SocialLoginRequest(
        @NotBlank(message = "O ID token do provedor é obrigatório")
        @Size(max = 8192, message = "ID token excede o tamanho máximo")
        String idToken,

        @NotBlank(message = "O nonce é obrigatório")
        @Size(max = 64, message = "Nonce excede o tamanho máximo")
        String nonce
) {}
