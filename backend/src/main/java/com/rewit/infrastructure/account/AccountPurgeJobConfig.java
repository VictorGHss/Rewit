package com.rewit.infrastructure.account;

import com.rewit.application.port.EmailReservation;
import com.rewit.application.port.UserRepository;
import com.rewit.application.usecase.PurgeDeletedAccountUseCase;
import com.rewit.application.usecase.PurgeEligibleAccountsUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Montagem do job de purge de contas excluídas (C2.3). A orquestração é classe pura de aplicação, registrada aqui;
 * a configuração é validada antes da montagem. Desligado, nenhum componente do job existe.
 */
@Configuration
@ConditionalOnProperty(prefix = "rewit.account.purge", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AccountPurgeJobConfig {

    @Bean
    public PurgeEligibleAccountsUseCase purgeEligibleAccountsUseCase(
            UserRepository userRepository,
            PurgeDeletedAccountUseCase purgeDeletedAccountUseCase,
            EmailReservation emailReservation,
            AccountPurgeJobProperties properties
    ) {
        properties.validate();
        return new PurgeEligibleAccountsUseCase(userRepository, purgeDeletedAccountUseCase, emailReservation,
                properties.getBatchSize(), properties.getMaxBatchesPerRun());
    }

    @Bean
    public AccountPurgeJobMetrics accountPurgeJobMetrics(MeterRegistry meterRegistry) {
        return new AccountPurgeJobMetrics(meterRegistry);
    }
}
