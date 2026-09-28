package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.ProductIdentifierJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para os identificadores de produtos estruturados (product_identifiers).
 */
@Repository
public interface ProductIdentifierJpaRepository extends JpaRepository<ProductIdentifierJpaEntity, UUID> {

    Optional<ProductIdentifierJpaEntity> findByIdentifierTypeAndIdentifierValue(String identifierType, String identifierValue);

    List<ProductIdentifierJpaEntity> findByProductId(UUID productId);

    boolean existsByIdentifierTypeAndIdentifierValue(String identifierType, String identifierValue);
}
