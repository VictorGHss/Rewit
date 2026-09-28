package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.ProductJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para a entidade de produtos globais (products).
 */
@Repository
public interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, UUID> {

    List<ProductJpaEntity> findByNameContainingIgnoreCase(String name);

    List<ProductJpaEntity> findByCategory(String category);
}
