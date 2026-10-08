package com.rewit.application.port;

import com.rewit.domain.model.RateableTarget;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída da aplicação para operações de persistência e recuperação da raiz RateableTarget.
 */
public interface RateableTargetRepository {

    RateableTarget save(RateableTarget target);

    Optional<RateableTarget> findById(UUID id);

    boolean existsById(UUID id);

    /**
     * Se o alvo pode ser exposto em leituras públicas (stats e reviews do alvo): existe e, sendo place ou product,
     * tem status {@code ACTIVE}, a mesma regra da busca global. Alvo ausente ou indisponível são indistinguíveis.
     */
    boolean existsPubliclyVisibleById(UUID id);
}
