package com.rewit.application.outbox;

import java.util.regex.Pattern;

/**
 * Sanitização de {@code last_error} do Outbox (Step 27.2, Parte P). Nunca armazenar
 * stack trace completo, senha, token, API key, credenciais, payload completo, URL
 * com segredo ou headers sensíveis: persiste apenas uma mensagem curta, em linha
 * única, com segredos rotulados redigidos e truncada ao limite configurado
 * ({@code rewit.outbox.error-max-length}).
 */
public final class OutboxErrorSanitizer {

    /**
     * Rótulos de segredo redigidos onde quer que apareçam como atribuição
     * (ex.: {@code password=abc}, {@code Authorization: Bearer xyz}).
     */
    private static final Pattern SENSITIVE_ASSIGNMENTS = Pattern.compile(
            "(?i)(password|passwd|secret|token|access[-_]?token|refresh[-_]?token|api[-_]?key|apikey"
                    + "|authorization|credential)s?\\s*[=:]\\s*(?:bearer\\s+)?[^\\s,;\"']+");

    private static final String REDACTED = "$1=[REDACTED]";

    /** Colapsa quebras de linha, tabs e demais caracteres de controle em espaço único. */
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cntrl}\\s]+");

    private final int maxLength;

    public OutboxErrorSanitizer(int maxLength) {
        if (maxLength < 32) {
            throw new IllegalArgumentException("O limite de tamanho do last_error deve ser >= 32");
        }
        this.maxLength = maxLength;
    }

    /**
     * Extrai apenas o tipo simples da exceção e a mensagem — sem stack trace e sem
     * causa encadeada — e aplica a mesma sanitização de mensagem bruta.
     */
    public String sanitize(Throwable error) {
        if (error == null) {
            return null;
        }
        String type = error.getClass().getSimpleName();
        String message = error.getMessage();
        return sanitize(type + (message == null || message.isBlank() ? "" : ": " + message));
    }

    public String sanitize(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return null;
        }
        String singleLine = CONTROL_CHARACTERS.matcher(rawMessage.trim()).replaceAll(" ").trim();
        String redacted = SENSITIVE_ASSIGNMENTS.matcher(singleLine).replaceAll(REDACTED);
        if (redacted.length() <= maxLength) {
            return redacted;
        }
        return redacted.substring(0, maxLength);
    }
}
