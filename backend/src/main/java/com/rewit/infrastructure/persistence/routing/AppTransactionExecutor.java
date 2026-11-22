package com.rewit.infrastructure.persistence.routing;

import com.rewit.application.port.AppTransactionPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * Executor transacional explícito sob a role pública padrão {@code rewit_app}.
 */
@Component
public class AppTransactionExecutor implements AppTransactionPort {

    @Override
    @Transactional
    public <T> T execute(Supplier<T> action) {
        return action.get();
    }

    @Override
    @Transactional
    public void run(Runnable action) {
        action.run();
    }
}
