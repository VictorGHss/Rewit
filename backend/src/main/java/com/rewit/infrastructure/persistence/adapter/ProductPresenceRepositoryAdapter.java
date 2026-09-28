package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.ProductPresenceRepository;
import com.rewit.domain.model.ProductPresence;
import com.rewit.infrastructure.persistence.entity.ProductPresenceJpaEntity;
import com.rewit.infrastructure.persistence.repository.ProductPresenceJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para ProductPresence.
 */
@Component
public class ProductPresenceRepositoryAdapter implements ProductPresenceRepository {

    private final ProductPresenceJpaRepository repository;

    public ProductPresenceRepositoryAdapter(ProductPresenceJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "ProductPresenceJpaRepository must not be null");
    }

    @Override
    public ProductPresence save(ProductPresence presence) {
        Objects.requireNonNull(presence, "ProductPresence cannot be null");
        if (presence.getId() != null) {
            return repository.findById(presence.getId())
                    .map(existing -> {
                        existing.updateFromDomain(presence);
                        ProductPresenceJpaEntity saved = repository.saveAndFlush(existing);
                        return Objects.requireNonNull(saved, "Saved ProductPresenceJpaEntity cannot be null").toDomain();
                    })
                    .orElseGet(() -> {
                        ProductPresenceJpaEntity newEntity = ProductPresenceJpaEntity.fromDomain(presence);
                        ProductPresenceJpaEntity saved = repository.saveAndFlush(newEntity);
                        return Objects.requireNonNull(saved, "Saved ProductPresenceJpaEntity cannot be null").toDomain();
                    });
        }
        ProductPresenceJpaEntity newEntity = ProductPresenceJpaEntity.fromDomain(presence);
        ProductPresenceJpaEntity saved = repository.saveAndFlush(newEntity);
        return Objects.requireNonNull(saved, "Saved ProductPresenceJpaEntity cannot be null").toDomain();
    }

    @Override
    public Optional<ProductPresence> findByProductIdAndPlaceId(UUID productId, UUID placeId) {
        if (productId == null || placeId == null) {
            return Optional.empty();
        }
        return repository.findByProductIdAndPlaceId(productId, placeId).map(ProductPresenceRepositoryAdapter::toDomain);
    }

    @Override
    public List<ProductPresence> findByPlaceId(UUID placeId) {
        if (placeId == null) {
            return List.of();
        }
        return repository.findByPlaceId(placeId).stream()
                .map(ProductPresenceRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    public List<ProductPresence> findByProductId(UUID productId) {
        if (productId == null) {
            return List.of();
        }
        return repository.findByProductId(productId).stream()
                .map(ProductPresenceRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    public boolean existsByProductIdAndPlaceId(UUID productId, UUID placeId) {
        if (productId == null || placeId == null) {
            return false;
        }
        return repository.existsByProductIdAndPlaceId(productId, placeId);
    }

    private static ProductPresence toDomain(ProductPresenceJpaEntity entity) {
        return entity != null ? entity.toDomain() : null;
    }
}
