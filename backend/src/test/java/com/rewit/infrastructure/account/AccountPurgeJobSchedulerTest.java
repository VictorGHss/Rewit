package com.rewit.infrastructure.account;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rewit.application.usecase.PurgeEligibleAccountsUseCase;
import com.rewit.application.usecase.PurgeEligibleAccountsUseCase.AccountPurgeRunResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Testes Unitários: AccountPurgeJobScheduler e métricas (C2.3)")
class AccountPurgeJobSchedulerTest {

    private final PurgeEligibleAccountsUseCase useCase = mock(PurgeEligibleAccountsUseCase.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AccountPurgeJobScheduler scheduler = new AccountPurgeJobScheduler(useCase, new AccountPurgeJobMetrics(registry));

    @Test
    @DisplayName("Cada tick delega uma passada ao caso de uso e registra as contagens")
    void tickDelegatesAndRecordsMetrics() {
        when(useCase.run(any(Instant.class))).thenReturn(
                new AccountPurgeRunResult(5, 3, 1, 0, 1, Map.of("DataAccessResourceFailureException", 1), false, false));

        scheduler.purgeDeletedAccounts();

        verify(useCase, times(1)).run(any(Instant.class));
        assertEquals(3, registry.counter(AccountPurgeJobMetrics.COUNTER_PURGED).count());
        assertEquals(1, registry.counter(AccountPurgeJobMetrics.COUNTER_ACCOUNT_FAILURES).count());
        assertEquals(0, registry.counter(AccountPurgeJobMetrics.COUNTER_BLOCKED_RUNS).count());
        assertEquals(0, registry.counter(AccountPurgeJobMetrics.COUNTER_RUN_FAILURES).count());
        assertEquals(1, registry.get(AccountPurgeJobMetrics.TIMER_DURATION).timer().count());
    }

    @Test
    @DisplayName("Passada bloqueada por falta do segredo é contada")
    void blockedRunIsCounted() {
        when(useCase.run(any(Instant.class))).thenReturn(new AccountPurgeRunResult(2, 0, 0, 0, 0, Map.of(), true, false));

        scheduler.purgeDeletedAccounts();

        assertEquals(1, registry.counter(AccountPurgeJobMetrics.COUNTER_BLOCKED_RUNS).count());
        assertEquals(0, registry.counter(AccountPurgeJobMetrics.COUNTER_PURGED).count());
    }

    @Test
    @DisplayName("Falha da passada é contada e absorvida, e o log não traz a mensagem do erro")
    void failureIsCountedAndSanitized() {
        when(useCase.run(any(Instant.class))).thenThrow(new IllegalStateException("pessoa@exemplo.com segredo=xyz"));
        Logger logger = (Logger) LoggerFactory.getLogger(AccountPurgeJobScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(() -> scheduler.purgeDeletedAccounts());
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(1, registry.counter(AccountPurgeJobMetrics.COUNTER_RUN_FAILURES).count());
        assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("IllegalStateException")));
        assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("pessoa@exemplo.com")
                || event.getFormattedMessage().contains("xyz") || event.getThrowableProxy() != null));
    }
}
