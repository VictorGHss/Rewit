package com.rewit.infrastructure.authsession;

import com.rewit.application.port.AuthSessionRepository;
import com.rewit.application.usecase.CleanupAuthSessionsUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("Testes de Configuração: cleanup de auth_sessions (Step 29.3)")
class AuthSessionCleanupConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, AuthSessionCleanupConfig.class, AuthSessionCleanupScheduler.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(AuthSessionRepository.class, () -> mock(AuthSessionRepository.class));

    @Configuration
    @EnableConfigurationProperties(AuthSessionCleanupProperties.class)
    static class PropertiesConfig {
    }

    @Test
    @DisplayName("Sem configuração explícita o cleanup é montado, como o purge do Outbox")
    void enabledByDefault() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(AuthSessionCleanupScheduler.class).size());
            assertEquals(1, context.getBeansOfType(CleanupAuthSessionsUseCase.class).size());
            AuthSessionCleanupProperties properties = context.getBean(AuthSessionCleanupProperties.class);
            assertEquals(100, properties.getBatchSize());
            assertEquals(1, properties.getMaxBatchesPerRun());
        });
    }

    @Test
    @DisplayName("Desligado, nenhum componente do cleanup existe")
    void disabledCreatesNothing() {
        runner.withPropertyValues("rewit.auth-session-cleanup.enabled=false").run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(AuthSessionCleanupScheduler.class).isEmpty());
            assertTrue(context.getBeansOfType(CleanupAuthSessionsUseCase.class).isEmpty());
        });
    }

    @Test
    @DisplayName("Configuração inválida falha no boot")
    void invalidConfigurationFails() {
        for (String invalid : List.of("rewit.auth-session-cleanup.batch-size=0",
                "rewit.auth-session-cleanup.max-batches-per-run=0",
                "rewit.auth-session-cleanup.interval-ms=0",
                "rewit.auth-session-cleanup.initial-delay-ms=-1")) {
            runner.withPropertyValues(invalid).run(context -> assertNotNull(context.getStartupFailure(), invalid));
        }
    }
}
