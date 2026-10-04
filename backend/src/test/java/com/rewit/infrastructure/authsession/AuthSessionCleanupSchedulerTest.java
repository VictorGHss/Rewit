package com.rewit.infrastructure.authsession;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rewit.application.usecase.CleanupAuthSessionsUseCase;
import com.rewit.application.usecase.CleanupAuthSessionsUseCase.AuthSessionCleanupResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Testes Unitários: AuthSessionCleanupScheduler e métricas (Step 29.3)")
class AuthSessionCleanupSchedulerTest {

    private final CleanupAuthSessionsUseCase useCase = mock(CleanupAuthSessionsUseCase.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AuthSessionCleanupScheduler scheduler =
            new AuthSessionCleanupScheduler(useCase, new AuthSessionCleanupMetrics(registry));

    @Test
    @DisplayName("Cada tick delega uma passada ao use case e registra as contagens")
    void tickDelegatesAndRecordsMetrics() {
        when(useCase.cleanup(any(Instant.class))).thenReturn(new AuthSessionCleanupResult(4, 1, false, 7, 1, false));

        scheduler.cleanupAuthSessions();

        verify(useCase, times(1)).cleanup(any(Instant.class));
        assertEquals(4, registry.counter(AuthSessionCleanupMetrics.COUNTER_METADATA_CLEARED).count());
        assertEquals(7, registry.counter(AuthSessionCleanupMetrics.COUNTER_SESSIONS_PURGED).count());
        assertEquals(0, registry.counter(AuthSessionCleanupMetrics.COUNTER_FAILURES).count());
        assertEquals(1, registry.get(AuthSessionCleanupMetrics.TIMER_DURATION).timer().count());
    }

    @Test
    @DisplayName("Falha da passada é contada e absorvida, e o log não traz a mensagem do erro")
    void failureIsCountedAndSanitized() {
        when(useCase.cleanup(any(Instant.class))).thenThrow(new IllegalStateException("token_hash=abc ip=10.0.0.1"));
        Logger logger = (Logger) LoggerFactory.getLogger(AuthSessionCleanupScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(() -> scheduler.cleanupAuthSessions());
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(1, registry.counter(AuthSessionCleanupMetrics.COUNTER_FAILURES).count());
        assertEquals(0, registry.counter(AuthSessionCleanupMetrics.COUNTER_SESSIONS_PURGED).count());
        assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("IllegalStateException")));
        assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("10.0.0.1")
                || event.getFormattedMessage().contains("abc") || event.getThrowableProxy() != null));
    }
}
