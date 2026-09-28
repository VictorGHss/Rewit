package com.rewit.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Validador estrito de inicialização para JWT_SECRET (HMAC-SHA256).
 * Impede que a aplicação inicialize com segredos ausentes, curtos, de baixa entropia ou placeholders conhecidos.
 * Garante que o segredo NUNCA seja exibido em mensagens de log ou exceptions.
 */
public final class JwtSecretValidator {

    public static final int MIN_KEY_BYTES = 32;

    private static final List<String> FORBIDDEN_PATTERNS = List.of(
            "change-me",
            "changeme",
            "password",
            "secret",
            "default",
            "placeholder",
            "123456",
            "jwt-secret",
            "your-secret"
    );

    private JwtSecretValidator() {
    }

    /**
     * Valida o segredo JWT fornecido.
     *
     * @param secret o segredo configurado (não nulo)
     * @throws IllegalArgumentException se o segredo for inválido ou inseguro
     */
    public static void validate(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    "JWT_SECRET inválido: segredo ausente ou vazio. Configure uma chave com no mínimo 32 bytes de entropia."
            );
        }

        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "JWT_SECRET inválido: comprimento insuficiente para HMAC-SHA256 (mínimo de 32 bytes / 256 bits exigido)."
            );
        }

        String normalized = secret.trim().toLowerCase();

        for (String pattern : FORBIDDEN_PATTERNS) {
            if (normalized.equals(pattern) || normalized.startsWith(pattern) || normalized.endsWith(pattern)) {
                throw new IllegalArgumentException(
                        "JWT_SECRET inválido: valor inseguro detectado (placeholder ou padrão fraco proibido). Modifique o JWT_SECRET no ambiente."
                );
            }
        }

        // Validação de entropia mínima: rejeita se contiver pouca variedade de caracteres
        long distinctChars = secret.chars().distinct().count();
        if (distinctChars < 8) {
            throw new IllegalArgumentException(
                    "JWT_SECRET inválido: entropia insuficiente detectada (baixa variedade de caracteres). Forneça um segredo aleatório forte."
            );
        }
    }
}
