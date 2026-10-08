package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.ProductRepository;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Product;
import com.rewit.infrastructure.persistence.entity.ProductJpaEntity;
import com.rewit.infrastructure.persistence.entity.RateableTargetJpaEntity;
import com.rewit.infrastructure.persistence.repository.ProductJpaRepository;
import com.rewit.infrastructure.persistence.repository.RateableTargetJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para a entidade Product.
 * Garante a integridade física com a raiz RateableTarget conforme ADR-009 e triggers do PostgreSQL.
 */
@Component
public class ProductRepositoryAdapter implements ProductRepository {

    private final ProductJpaRepository productJpaRepository;
    private final RateableTargetJpaRepository rateableTargetJpaRepository;

    public ProductRepositoryAdapter(ProductJpaRepository productJpaRepository,
                                  RateableTargetJpaRepository rateableTargetJpaRepository) {
        this.productJpaRepository = Objects.requireNonNull(productJpaRepository, "ProductJpaRepository must not be null");
        this.rateableTargetJpaRepository = Objects.requireNonNull(rateableTargetJpaRepository, "RateableTargetJpaRepository must not be null");
    }

    @Override
    @Transactional
    public Product save(Product product) {
        Objects.requireNonNull(product, "Product cannot be null");

        // 1. Assegura a existência do RateableTarget raiz associado antes de persistir o Product
        if (!rateableTargetJpaRepository.existsById(product.getId())) {
            RateableTargetJpaEntity target = new RateableTargetJpaEntity(
                    product.getId(),
                    TargetType.PRODUCT,
                    product.getCreatedAt()
            );
            rateableTargetJpaRepository.saveAndFlush(target);
        }

        // 2. Persiste ou atualiza o Product especializado
        return productJpaRepository.findById(product.getId())
                .map(existing -> {
                    existing.updateFromDomain(product);
                    ProductJpaEntity saved = productJpaRepository.saveAndFlush(existing);
                    return Objects.requireNonNull(saved, "Saved ProductJpaEntity cannot be null").toDomain();
                })
                .orElseGet(() -> {
                    ProductJpaEntity newEntity = ProductJpaEntity.fromDomain(product);
                    ProductJpaEntity saved = productJpaRepository.saveAndFlush(newEntity);
                    return Objects.requireNonNull(saved, "Saved ProductJpaEntity cannot be null").toDomain();
                });
    }

    @Override
    public Optional<Product> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return productJpaRepository.findById(id).map(ProductRepositoryAdapter::toDomain);
    }

    @Override
    public List<Product> searchByName(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        return productJpaRepository.findByNameContainingIgnoreCase(name.trim()).stream()
                .map(ProductRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    public PageResult<Product> findActiveByPlace(UUID placeId, int page, int size) {
        Objects.requireNonNull(placeId, "placeId must not be null");
        List<Product> content = productJpaRepository.findActiveByPlace(placeId, size, (long) page * size).stream()
                .map(ProductRepositoryAdapter::toDomain)
                .toList();
        return PageResult.of(content, page, size, productJpaRepository.countActiveByPlace(placeId));
    }

    private static Product toDomain(ProductJpaEntity entity) {
        return entity != null ? entity.toDomain() : null;
    }
}
