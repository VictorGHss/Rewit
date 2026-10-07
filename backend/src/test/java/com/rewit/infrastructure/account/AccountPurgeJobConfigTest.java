package com.rewit.infrastructure.account;

import com.rewit.application.port.EmailReservation;
import com.rewit.application.port.UserRepository;
import com.rewit.application.usecase.PurgeDeletedAccountUseCase;
import com.rewit.application.usecase.PurgeEligibleAccountsUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("Testes de Configuração: job de purge de contas excluídas (C2.3)")
class AccountPurgeJobConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, AccountPurgeJobConfig.class, AccountPurgeJobScheduler.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(PurgeDeletedAccountUseCase.class, () -> mock(PurgeDeletedAccountUseCase.class))
            .withBean(EmailReservation.class, () -> mock(EmailReservation.class));

    @Configuration
    @EnableConfigurationProperties(AccountPurgeJobProperties.class)
    static class PropertiesConfig {
    }

    @Test
    @DisplayName("Sem configuração explícita o job é montado: uma passada por dia, lotes limitados")
    void enabledByDefaultDaily() throws Exception {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(AccountPurgeJobScheduler.class).size());
            assertEquals(1, context.getBeansOfType(PurgeEligibleAccountsUseCase.class).size());
            AccountPurgeJobProperties properties = context.getBean(AccountPurgeJobProperties.class);
            assertEquals(86_400_000, properties.getIntervalMs());
            assertEquals(100, properties.getBatchSize());
            assertEquals(10, properties.getMaxBatchesPerRun());
        });

        Scheduled scheduled = AccountPurgeJobScheduler.class.getMethod("purgeDeletedAccounts").getAnnotation(Scheduled.class);
        assertEquals("${rewit.account.purge.interval-ms:86400000}", scheduled.fixedDelayString());
    }

    @Test
    @DisplayName("Desligado, nenhum componente do job existe")
    void disabledCreatesNothing() {
        runner.withPropertyValues("rewit.account.purge.enabled=false").run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(AccountPurgeJobScheduler.class).isEmpty());
            assertTrue(context.getBeansOfType(PurgeEligibleAccountsUseCase.class).isEmpty());
        });
    }

    @Test
    @DisplayName("Configuração inválida falha no boot")
    void invalidConfigurationFails() {
        for (String invalid : List.of("rewit.account.purge.batch-size=0",
                "rewit.account.purge.max-batches-per-run=0",
                "rewit.account.purge.interval-ms=0",
                "rewit.account.purge.initial-delay-ms=-1")) {
            runner.withPropertyValues(invalid).run(context -> assertNotNull(context.getStartupFailure(), invalid));
        }
    }
}
