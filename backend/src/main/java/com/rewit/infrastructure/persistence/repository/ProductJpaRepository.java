package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.ProductJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para a entidade de produtos globais (products).
 */
public interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, UUID> {

    List<ProductJpaEntity> findByNameContainingIgnoreCase(String name);

    List<ProductJpaEntity> findByCategory(String category);

    /** Página de produtos ACTIVE presentes no local: idx_product_presence_place + PK de products. */
    @Query(value = """
            SELECT pr.* FROM product_presences pp
            JOIN products pr ON pr.id = pp.product_id
            WHERE pp.place_id = :placeId AND pr.status = 'ACTIVE'
            ORDER BY pr.name ASC, pr.id ASC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<ProductJpaEntity> findActiveByPlace(@Param("placeId") UUID placeId, @Param("limit") int limit,
                                             @Param("offset") long offset);

    @Query(value = """
            SELECT COUNT(*) FROM product_presences pp
            JOIN products pr ON pr.id = pp.product_id
            WHERE pp.place_id = :placeId AND pr.status = 'ACTIVE'
            """, nativeQuery = true)
    long countActiveByPlace(@Param("placeId") UUID placeId);
}
