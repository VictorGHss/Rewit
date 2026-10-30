package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.RankingConfigurationJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repositório Spring Data JPA para ranking_configurations.
 */
@Repository
public interface RankingConfigurationJpaRepository extends JpaRepository<RankingConfigurationJpaEntity, String> {
}
