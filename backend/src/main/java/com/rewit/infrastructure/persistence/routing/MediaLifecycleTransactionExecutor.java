package com.rewit.infrastructure.persistence.routing;

import com.rewit.application.port.MediaLifecycleTransactionPort;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Executor transacional explícito sob a role dedicada de ciclo de vida de mídias ({@code rewit_media}).
 * Utilizado por fluxos híbridos ({@code PlaceMediaService.deleteMedia} e
 * {@code StorageQuarantineRepositoryAdapter.purgeConfirmedOrphanUnderCreationLock}) para despachar
 * operações de {@code place_media} sem abrir antes uma transação sob {@code rewit_app}.
 */
@Component
public class MediaLifecycleTransactionExecutor implements MediaLifecycleTransactionPort {

    @Override
    @MediaLifecycleTransactional
    public <T> T execute(Supplier<T> action) {
        return action.get();
    }

    @Override
    @MediaLifecycleTransactional
    public void run(Runnable action) {
        action.run();
    }
}
