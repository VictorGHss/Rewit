package com.rewit.infrastructure.security;

/**
 * Validador estrito de inicialização para JWT_SECRET (HMAC-SHA256).
 * Impede que a aplicação inicialize com segredos ausentes, curtos, de baixa entropia ou placeholders conhecidos.
 * Garante que o segredo NUNCA seja exibido em mensagens de log ou exceptions.
 */
public final class JwtSecretValidator {

    public static final int MIN_KEY_BYTES = SecuritySecretValidator.MIN_KEY_BYTES;

    private JwtSecretValidator() {
    }

    /**
     * Valida o segredo JWT fornecido.
     *
     * @param secret o segredo configurado (não nulo)
     * @throws IllegalArgumentException se o segredo for inválido ou inseguro
     */
    public static void validate(String secret) {
        SecuritySecretValidator.validateSecret("JWT_SECRET", secret);
    }
}
