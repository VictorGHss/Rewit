package com.rewit.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.outbox.OutboxErrorSanitizer;
import com.rewit.application.outbox.OutboxRetryPolicy;
import com.rewit.application.outbox.PushNotificationHandler;
import com.rewit.application.port.NotificationProvider;
import com.rewit.application.port.NotificationRepository;
import com.rewit.application.port.OutboxHandler;
import com.rewit.application.port.OutboxRepository;
import com.rewit.application.usecase.ProcessOutboxBatchUseCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Montagem do dispatcher do Outbox (Step 27.2). O use case é classe pura de
 * aplicação — deliberadamente sem @Service/@Transactional — e é registrado
 * aqui como bean de infraestrutura, garantindo que nenhuma transação o envolva
 * durante o processamento dos handlers.
 */
@Configuration
public class OutboxDispatcherConfig {

    @Bean
    public OutboxRetryPolicy outboxRetryPolicy(OutboxDispatcherProperties properties) {
        OutboxDispatcherProperties.Retry retry = properties.getRetry();
        return new OutboxRetryPolicy(
                retry.getInitialDelay(),
                retry.getMultiplier(),
                retry.getMaxDelay(),
                retry.getMaxAttempts());
    }

    @Bean
    public OutboxErrorSanitizer outboxErrorSanitizer(OutboxDispatcherProperties properties) {
        return new OutboxErrorSanitizer(properties.getErrorMaxLength());
    }

    @Bean
    public PushNotificationHandler pushNotificationHandler(
            NotificationRepository notificationRepository,
            NotificationProvider notificationProvider
    ) {
        return new PushNotificationHandler(notificationRepository, notificationProvider, new ObjectMapper());
    }

    @Bean
    public ProcessOutboxBatchUseCase processOutboxBatchUseCase(
            OutboxRepository outboxRepository,
            OutboxDispatcherProperties properties,
            OutboxRetryPolicy outboxRetryPolicy,
            OutboxErrorSanitizer outboxErrorSanitizer,
            WorkerIdProvider workerIdProvider,
            @Autowired(required = false) List<OutboxHandler> handlers
    ) {
        return new ProcessOutboxBatchUseCase(
                outboxRepository,
                handlers == null ? List.of() : handlers,
                outboxRetryPolicy,
                outboxErrorSanitizer,
                workerIdProvider.getWorkerId(),
                properties.getBatchSize(),
                properties.getLeaseDuration());
    }
}
