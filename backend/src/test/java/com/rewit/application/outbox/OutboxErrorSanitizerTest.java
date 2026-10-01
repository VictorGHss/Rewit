package com.rewit.application.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Unidade: Sanitização do last_error do Outbox (Step 27.2, Parte P)")
class OutboxErrorSanitizerTest {

    private OutboxErrorSanitizer sanitizer() {
        return new OutboxErrorSanitizer(512);
    }

    @Test
    @DisplayName("P.1: segredos rotulados (password, token, api_key) são redigidos")
    void shouldRedactLabeledSecrets() {
        String sanitized = sanitizer().sanitize(
                "Falha no provider: password=supersecret token=abc123 api_key=XYZ-000");

        assertFalse(sanitized.contains("supersecret"));
        assertFalse(sanitized.contains("abc123"));
        assertFalse(sanitized.contains("XYZ-000"));
        assertTrue(sanitized.contains("password=[REDACTED]"));
        assertTrue(sanitized.contains("token=[REDACTED]"));
        assertTrue(sanitized.contains("api_key=[REDACTED]"));
    }

    @Test
    @DisplayName("P.2: header Authorization com Bearer é redigido")
    void shouldRedactAuthorizationHeader() {
        String sanitized = sanitizer().sanitize(
                "401 ao chamar provider com Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.assinatura");

        assertFalse(sanitized.contains("eyJhbGciOiJIUzI1NiJ9"));
        assertTrue(sanitized.contains("Authorization=[REDACTED]"));
    }

    @Test
    @DisplayName("P.3: quebras de linha, tabs e carriage returns colapsam em linha única")
    void shouldCollapseControlCharactersToSingleLine() {
        String sanitized = sanitizer().sanitize("linha1\nlinha2\tcoluna\r\nfim");

        assertFalse(sanitized.contains("\n"));
        assertFalse(sanitized.contains("\t"));
        assertFalse(sanitized.contains("\r"));
        assertEquals("linha1 linha2 coluna fim", sanitized);
    }

    @Test
    @DisplayName("P.4: sanitize(Throwable) extrai apenas tipo simples + mensagem — sem stack trace, sem FQCN, sem causa")
    void shouldNeverContainStackTraceFromThrowable() {
        RuntimeException error = new RuntimeException("boom com token=abc123",
                new IllegalStateException("causa interna que NÃO deve aparecer"));

        String sanitized = new OutboxErrorSanitizer(512).sanitize(error);

        assertTrue(sanitized.startsWith("RuntimeException: "), "Deve usar apenas o tipo simples: " + sanitized);
        assertFalse(sanitized.contains("java.lang"), "FQCN não deve aparecer");
        assertFalse(sanitized.contains("IllegalStateException"), "Causa encadeada não deve aparecer");
        assertFalse(sanitized.contains("\n"), "Stack trace não deve aparecer");
        assertFalse(sanitized.contains("abc123"));
        assertTrue(sanitized.contains("token=[REDACTED]"));
    }

    @Test
    @DisplayName("P.5: mensagens mais longas que o limite são truncadas ao maxLength configurado")
    void shouldTruncateToConfiguredMaxLength() {
        OutboxErrorSanitizer tight = new OutboxErrorSanitizer(64);
        String longMessage = "x".repeat(200);

        String sanitized = tight.sanitize(longMessage);

        assertEquals(64, sanitized.length());
    }

    @Test
    @DisplayName("P.6: mensagem nula, vazia ou em branco resulta em null (nada é persistido)")
    void shouldReturnNullForNullOrBlank() {
        assertNull(sanitizer().sanitize((String) null));
        assertNull(sanitizer().sanitize("   "));
        assertNull(sanitizer().sanitize((Throwable) null));
    }

    @Test
    @DisplayName("P.7: mensagem comum é preservada sem alterações")
    void shouldKeepOrdinaryMessageUnchanged() {
        String message = "Conexão com provider recusada após timeout";

        String sanitized = sanitizer().sanitize(message);

        assertEquals(message, sanitized);
    }

    @Test
    @DisplayName("Construtor rejeita maxLength abaixo do mínimo de 32 caracteres")
    void shouldRejectMaxLengthBelowMinimum() {
        assertThrows(IllegalArgumentException.class, () -> new OutboxErrorSanitizer(31));
    }
}
