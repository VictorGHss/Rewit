package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.ProductIdentifierRepository;
import com.rewit.domain.model.ProductIdentifier;
import com.rewit.infrastructure.persistence.entity.ProductIdentifierJpaEntity;
import com.rewit.infrastructure.persistence.repository.ProductIdentifierJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para ProductIdentifier.
 */
@Component
public class ProductIdentifierRepositoryAdapter implements ProductIdentifierRepository {

    private final ProductIdentifierJpaRepository repository;

    public ProductIdentifierRepositoryAdapter(ProductIdentifierJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "ProductIdentifierJpaRepository must not be null");
    }

    @Override
    public ProductIdentifier save(ProductIdentifier identifier) {
        Objects.requireNonNull(identifier, "ProductIdentifier cannot be null");
        ProductIdentifierJpaEntity entity = ProductIdentifierJpaEntity.fromDomain(identifier);
        ProductIdentifierJpaEntity saved = repository.saveAndFlush(entity);
        return Objects.requireNonNull(saved, "Saved ProductIdentifierJpaEntity cannot be null").toDomain();
    }

    @Override
    public Optional<ProductIdentifier> findByTypeAndValue(String identifierType, String identifierValue) {
        if (identifierType == null || identifierValue == null) {
            return Optional.empty();
        }
        return repository.findByIdentifierTypeAndIdentifierValue(identifierType.trim().toUpperCase(), identifierValue.trim())
                .map(ProductIdentifierRepositoryAdapter::toDomain);
    }

    @Override
    public List<ProductIdentifier> findByProductId(UUID productId) {
        if (productId == null) {
            return List.of();
        }
        return repository.findByProductId(productId).stream()
                .map(ProductIdentifierRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    public List<ProductIdentifier> findByProductIdAndTypes(UUID productId, Collection<String> identifierTypes) {
        if (productId == null || identifierTypes == null || identifierTypes.isEmpty()) {
            return List.of();
        }
        return repository.findByProductIdAndIdentifierTypeInOrderByIdentifierTypeAscIdentifierValueAsc(productId, identifierTypes)
                .stream()
                .map(ProductIdentifierRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    public boolean existsByTypeAndValue(String identifierType, String identifierValue) {
        if (identifierType == null || identifierValue == null) {
            return false;
        }
        return repository.existsByIdentifierTypeAndIdentifierValue(identifierType.trim().toUpperCase(), identifierValue.trim());
    }

    private static ProductIdentifier toDomain(ProductIdentifierJpaEntity entity) {
        return entity != null ? entity.toDomain() : null;
    }
}
