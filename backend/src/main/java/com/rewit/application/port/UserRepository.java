package com.rewit.application.port;

import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.User;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída da aplicação para operações de persistência e recuperação da entidade User.
 * Mantém a aplicação e o domínio desacoplados do mecanismo subjacente (Spring Data / JPA).
 */
public interface UserRepository {

    User save(User user);

    Optional<User> findById(UUID id);

    Optional<User> findByIdIncludingDeleted(UUID id);

    /**
     * Carrega a conta em qualquer estado, inclusive excluída, travando a linha até o fim da transação. Toda escrita
     * no estado da conta parte dessa leitura: duas operações concorrentes sobre a mesma conta são serializadas e a
     * segunda enxerga o estado já confirmado pela primeira (sem lost update).
     */
    Optional<User> findByIdForUpdate(UUID id);

    boolean existsById(UUID id);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByAuthProviderAndProviderUserId(AuthProvider authProvider, String providerUserId);
}
