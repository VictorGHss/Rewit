package com.rewit.application.port;

import com.rewit.domain.model.Profile;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída da aplicação para operações de persistência e recuperação da entidade Profile.
 * Mantém a aplicação e o domínio desacoplados do mecanismo subjacente (Spring Data / JPA).
 */
public interface ProfileRepository {

    Profile save(Profile profile);

    Optional<Profile> findById(UUID id);

    Optional<Profile> findByUserId(UUID userId);

    java.util.List<Profile> findByUserIdIn(java.util.Collection<UUID> userIds);

    Optional<Profile> findByHandle(String handle);

    boolean existsByHandle(String handle);

    boolean existsByUserId(UUID userId);
}
