package com.rewit.infrastructure.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.StandardStackTracePrinter;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários: stack trace dos logs JSON sem mensagens de exceção (Observabilidade V1)")
class SanitizedStackTracePrinterTest {

    @Test
    @DisplayName("Imprime classes e frames de toda a cadeia, sem as mensagens")
    void printsClassesAndFramesWithoutMessages() {
        SQLException cause = new SQLException("Key (token_hash)=(abc123) already exists; ip=10.0.0.9");
        IllegalStateException error = new IllegalStateException("falha para usuario@rewit.test", cause);

        String printed = new SanitizedStackTracePrinter(StandardStackTracePrinter.rootLast()).printStackTraceToString(error);

        assertTrue(printed.startsWith("java.lang.IllegalStateException"));
        assertTrue(printed.contains("java.sql.SQLException"));
        assertTrue(printed.contains("SanitizedStackTracePrinterTest"));
        assertFalse(printed.contains("token_hash"));
        assertFalse(printed.contains("abc123"));
        assertFalse(printed.contains("10.0.0.9"));
        assertFalse(printed.contains("usuario@rewit.test"));
    }

    @Test
    @DisplayName("Respeita os limites de tamanho do printer configurado pelo Spring Boot")
    void keepsConfiguredLimits() {
        String printed = new SanitizedStackTracePrinter(StandardStackTracePrinter.rootLast().withMaximumLength(80))
                .printStackTraceToString(new IllegalStateException("x"));

        assertTrue(printed.length() <= 80);
    }
}
