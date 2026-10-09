package com.rewit.application.port;

import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.RateableTarget;

import java.util.Collection;
import java.util.List;
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

    /**
     * Tipo e nome de exibição de cada alvo, numa única consulta, em qualquer status (uso administrativo). O nome vem
     * da especialização (place, product, service: name; event: title) e é null quando ela não existe.
     */
    List<TargetDisplay> findDisplaysByIds(Collection<UUID> ids);

    record TargetDisplay(UUID id, TargetType type, String displayName) {}
}
