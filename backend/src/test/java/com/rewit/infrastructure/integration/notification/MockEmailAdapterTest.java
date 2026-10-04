package com.rewit.infrastructure.integration.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários: MockEmailAdapter sem e-mail no log (Observabilidade V1)")
class MockEmailAdapterTest {

    @Test
    @DisplayName("O log do envio simulado não traz destinatário, assunto nem corpo")
    void logHasNoRecipientOrContent() {
        Logger logger = (Logger) LoggerFactory.getLogger(MockEmailAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new MockEmailAdapter().sendEmail("destinatario@rewit.test", "Assunto pessoal", "<p>corpo</p>");
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(1, appender.list.size());
        String message = appender.list.getFirst().getFormattedMessage();
        assertTrue(message.startsWith("[MOCK EMAIL]"));
        assertFalse(message.contains("destinatario@rewit.test"));
        assertFalse(message.contains("@"));
        assertFalse(message.contains("Assunto pessoal"));
        assertFalse(message.contains("corpo"));
    }
}
