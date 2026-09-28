package com.rewit.presentation.dto.user;

/**
 * Resposta de confirmação de alteração de senha bem-sucedida.
 * O DTO não expõe hashes, senhas, tokens de refresh ou dados de sessão.
 */
public record ChangePasswordResponse(
        String message
) {}
