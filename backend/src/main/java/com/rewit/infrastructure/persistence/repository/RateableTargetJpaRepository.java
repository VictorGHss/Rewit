package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.RateableTargetJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Repositório Spring Data JPA para a raiz de alvos avaliáveis (rateable_targets).
 */
public interface RateableTargetJpaRepository extends JpaRepository<RateableTargetJpaEntity, UUID> {
}
