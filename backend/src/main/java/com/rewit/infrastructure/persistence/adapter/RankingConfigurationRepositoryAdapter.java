package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.RankingConfigurationRepository;
import com.rewit.infrastructure.persistence.entity.RankingConfigurationJpaEntity;
import com.rewit.infrastructure.persistence.repository.RankingConfigurationJpaRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * Adaptador de infraestrutura para acesso às configurações dos rankings.
 */
@Component
public class RankingConfigurationRepositoryAdapter implements RankingConfigurationRepository {

    public static final String KEY_RANKINGS_ACTIVATED_AT = "rankings_activated_at";

    private final RankingConfigurationJpaRepository repository;

    public RankingConfigurationRepositoryAdapter(RankingConfigurationJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "RankingConfigurationJpaRepository must not be null");
    }

    @Override
    public Instant getRankingsActivatedAt() {
        return repository.findById(KEY_RANKINGS_ACTIVATED_AT)
                .map(RankingConfigurationJpaEntity::getValueTimestamp)
                .orElse(Instant.EPOCH);
    }
}
