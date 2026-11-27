package com.rewit.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validador e decodificador canônico, estrito e fail-closed para chaves e segredos criptográficos da aplicação
 * ({@code JWT_SECRET}, {@code PASSWORD_PEPPER_SECRET}, {@code REFRESH_TOKEN_SECRET} e chaves anteriores).
 *
 * <p>Contrato de representação de segredos:
 * <ul>
 *   <li><b>Base64 padrão</b> ({@code [A-Za-z0-9+/]+={0,2}} com comprimento múltiplo de 4): decodificado em bytes
 *       e validado contra o mínimo de 32 bytes reais (256 bits). Nunca faz fallback silencioso para UTF-8 se o
 *       resultado decodificado possuir menos de 32 bytes.</li>
 *   <li><b>Base64URL</b> ({@code [A-Za-z0-9_-]+={0,2}}, com ou sem padding): decodificado em bytes e validado
 *       contra o mínimo de 32 bytes reais (256 bits). Nunca faz fallback silencioso para UTF-8.</li>
 *   <li><b>Texto UTF-8</b> (contendo caracteres fora dos alfabetos Base64/Base64URL): utilizado como bytes UTF-8
 *       somente se satisfizer integralmente o mínimo de 32 bytes e demais requisitos de validação.</li>
 *   <li>Placeholders conhecidos, padrões fracos, strings malformadas de Base64 e chaves de bytes idênticos são
 *       rejeitados.</li>
 *   <li>Nenhum segredo (ativo ou anterior) pode reutilizar o mesmo material binário de outro propósito.</li>
 *   <li>Segredos NUNCA são registrados em logs ou exibidos em mensagens de exceção.</li>
 * </ul>
 */
public final class SecuritySecretValidator {

    public static final int MIN_KEY_BYTES = 32;

    private static final Pattern BASE64_CHARSET_PATTERN = Pattern.compile("^[A-Za-z0-9+/=_-]+$");
    private static final Pattern STANDARD_BASE64_PATTERN = Pattern.compile("^[A-Za-z0-9+/]+={0,2}$");
    private static final Pattern BASE64URL_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+={0,2}$");

    private static final List<String> FORBIDDEN_PATTERNS = List.of(
            "change-me",
            "change_me",
            "changeme",
            "password",
            "secret",
            "default",
            "placeholder",
            "123456",
            "jwt-secret",
            "your-secret",
            "rewit_local_password",
            "rewit_local_access",
            "rewit_local_secret"
    );

    private SecuritySecretValidator() {
    }

    /**
     * Valida um segredo criptográfico individual.
     *
     * @param secretName nome identificador do segredo (ex.: "JWT_SECRET") para mensagens de erro
     * @param secret     valor do segredo
     * @throws IllegalArgumentException se o segredo for nulo, em branco, malformado, curto, fraco ou placeholder
     */
    public static void validateSecret(String secretName, String secret) {
        decodeSecret(secretName, secret);
    }

    /**
     * Valida e decodifica os bytes efetivos do segredo criptográfico por um único caminho canônico.
     * Suporta Base64 padrão, Base64URL e texto UTF-8 (quando não pertencente ao alfabeto Base64),
     * sem fallback silencioso de Base64 curto para UTF-8.
     */
    public static byte[] decodeSecret(String secretName, String secret) {
        String displayName = (secretName != null && !secretName.isBlank()) ? secretName : "Segredo criptográfico";

        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    displayName + " inválido: segredo ausente ou vazio. Configure uma chave gerada por CSPRNG com no mínimo 32 bytes."
            );
        }

        String trimmed = secret.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT);

        if (trimmed.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                    displayName + " inválido: comprimento insuficiente (mínimo de 32 bytes / 256 bits exigido)."
            );
        }

        for (String pattern : FORBIDDEN_PATTERNS) {
            if (normalized.equals(pattern) || normalized.startsWith(pattern) || normalized.endsWith(pattern)) {
                throw new IllegalArgumentException(
                        displayName + " inválido: valor inseguro detectado (placeholder ou padrão fraco proibido). Modifique a configuração no ambiente."
                );
            }
        }

        if (allCharsIdentical(trimmed)) {
            throw new IllegalArgumentException(
                    displayName + " inválido: entropia insuficiente detectada (todos os caracteres são idênticos). Forneça um segredo aleatório forte."
            );
        }

        byte[] decoded = decodeCanonicalBytes(displayName, trimmed);
        if (decoded.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                    displayName + " inválido: comprimento insuficiente (mínimo de 32 bytes / 256 bits exigido após decodificação)."
            );
        }

        if (allBytesIdentical(decoded)) {
            throw new IllegalArgumentException(
                    displayName + " inválido: entropia binária insuficiente (todos os bytes são idênticos)."
            );
        }

        return decoded;
    }

    private static byte[] decodeCanonicalBytes(String displayName, String trimmed) {
        if (!BASE64_CHARSET_PATTERN.matcher(trimmed).matches()) {
            return trimmed.getBytes(StandardCharsets.UTF_8);
        }

        if (STANDARD_BASE64_PATTERN.matcher(trimmed).matches()) {
            if (trimmed.length() % 4 != 0) {
                throw new IllegalArgumentException(
                        displayName + " inválido: formato Base64 malformado (comprimento incompatível)."
                );
            }
            try {
                return Base64.getDecoder().decode(trimmed);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        displayName + " inválido: formato Base64 malformado."
                );
            }
        }

        if (BASE64URL_PATTERN.matcher(trimmed).matches()) {
            try {
                return Base64.getUrlDecoder().decode(trimmed);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        displayName + " inválido: formato Base64URL malformado."
                );
            }
        }

        throw new IllegalArgumentException(
                displayName + " inválido: formato Base64 malformado (mistura de alfabetos ou padding inválido)."
        );
    }

    private static boolean allCharsIdentical(String value) {
        if (value.isEmpty()) {
            return true;
        }
        char first = value.charAt(0);
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) != first) {
                return false;
            }
        }
        return true;
    }

    private static boolean allBytesIdentical(byte[] bytes) {
        if (bytes.length == 0) {
            return true;
        }
        byte first = bytes[0];
        for (int i = 1; i < bytes.length; i++) {
            if (bytes[i] != first) {
                return false;
            }
        }
        return true;
    }

    /**
     * Valida que segredos com propósitos criptográficos distintos (incluindo chaves anteriores)
     * não reutilizem o mesmo material binário de chave.
     *
     * @param secretsByName mapa de nome do segredo -> valor do segredo
     * @throws IllegalArgumentException se dois ou mais segredos compartilharem o mesmo material
     */
    public static void validateDistinct(Map<String, String> secretsByName) {
        if (secretsByName == null || secretsByName.size() < 2) {
            return;
        }

        List<Map.Entry<String, String>> entries = secretsByName.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .toList();

        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, String> first = entries.get(i);
            byte[] firstBytes = decodeSecret(first.getKey(), first.getValue());

            for (int j = i + 1; j < entries.size(); j++) {
                Map.Entry<String, String> second = entries.get(j);
                byte[] secondBytes = decodeSecret(second.getKey(), second.getValue());

                validateDistinctSecrets(first.getKey(), firstBytes, second.getKey(), secondBytes);
            }
        }
    }

    /**
     * Valida que dois materiais binários de chave não sejam idênticos.
     */
    public static void validateDistinctSecrets(String name1, byte[] secret1, String name2, byte[] secret2) {
        if (secret1 != null && secret2 != null && MessageDigest.isEqual(secret1, secret2)) {
            throw new IllegalArgumentException(
                    "Reutilização de chave criptográfica proibida: '" + name1
                            + "' e '" + name2 + "' utilizam o mesmo material de segredo. "
                            + "Cada propósito deve possuir um segredo exclusivo gerado por CSPRNG."
            );
        }
    }
}
