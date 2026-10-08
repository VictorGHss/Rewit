package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.RateableTargetRepository;
import com.rewit.domain.model.RateableTarget;
import com.rewit.infrastructure.persistence.entity.RateableTargetJpaEntity;
import com.rewit.infrastructure.persistence.repository.RateableTargetJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para a raiz RateableTarget.
 */
@Component
public class RateableTargetRepositoryAdapter implements RateableTargetRepository {

    private final RateableTargetJpaRepository repository;

    public RateableTargetRepositoryAdapter(RateableTargetJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "RateableTargetJpaRepository must not be null");
    }

    @Override
    public RateableTarget save(RateableTarget target) {
        Objects.requireNonNull(target, "RateableTarget cannot be null");
        RateableTargetJpaEntity entity = RateableTargetJpaEntity.fromDomain(target);
        RateableTargetJpaEntity saved = repository.saveAndFlush(entity);
        return Objects.requireNonNull(saved, "Saved RateableTargetJpaEntity cannot be null").toDomain();
    }

    @Override
    public Optional<RateableTarget> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id).map(RateableTargetRepositoryAdapter::toDomain);
    }

    @Override
    public boolean existsById(UUID id) {
        if (id == null) {
            return false;
        }
        return repository.existsById(id);
    }

    @Override
    public boolean existsPubliclyVisibleById(UUID id) {
        if (id == null) {
            return false;
        }
        return repository.existsPubliclyVisibleById(id);
    }

    private static RateableTarget toDomain(RateableTargetJpaEntity entity) {
        return entity != null ? entity.toDomain() : null;
    }
}
