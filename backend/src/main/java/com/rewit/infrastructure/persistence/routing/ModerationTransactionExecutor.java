package com.rewit.infrastructure.persistence.routing;

import com.rewit.application.port.ModerationTransactionPort;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Executor transacional explícito sob a role administrativa {@code rewit_mod}.
 * Utilizado quando um serviço híbrido (como {@code PlaceMediaService.deleteMedia}) precisa despachar
 * exclusivamente o ramo de moderação para uma transação dedicada {@code rewit_mod} sem abrir antes
 * uma transação {@code rewit_app}.
 */
@Component
public class ModerationTransactionExecutor implements ModerationTransactionPort {

    @Override
    @ModerationTransactional
    public <T> T execute(Supplier<T> action) {
        return action.get();
    }

    @Override
    @ModerationTransactional
    public void run(Runnable action) {
        action.run();
    }
}
