package com.rewit.infrastructure.authsession;

import com.rewit.application.port.AuthSessionRepository;
import com.rewit.application.usecase.CleanupAuthSessionsUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Montagem do cleanup de {@code auth_sessions} (Step 29.3). O use case é classe pura de aplicação, registrada
 * aqui como bean; a configuração é validada antes da montagem. Desligado, nenhum componente do cleanup existe.
 */
@Configuration
@ConditionalOnProperty(prefix = "rewit.auth-session-cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuthSessionCleanupConfig {

    @Bean
    public CleanupAuthSessionsUseCase cleanupAuthSessionsUseCase(
            AuthSessionRepository authSessionRepository,
            AuthSessionCleanupProperties properties
    ) {
        properties.validate();
        return new CleanupAuthSessionsUseCase(authSessionRepository, properties.getBatchSize(),
                properties.getMaxBatchesPerRun());
    }

    @Bean
    public AuthSessionCleanupMetrics authSessionCleanupMetrics(MeterRegistry meterRegistry) {
        return new AuthSessionCleanupMetrics(meterRegistry);
    }
}
