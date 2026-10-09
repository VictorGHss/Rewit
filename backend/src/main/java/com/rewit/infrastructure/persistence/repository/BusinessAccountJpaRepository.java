package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.BusinessAccountJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data JPA das contas comerciais (business_accounts).
 */
public interface BusinessAccountJpaRepository extends JpaRepository<BusinessAccountJpaEntity, UUID> {

    @Query("SELECT b FROM BusinessAccountJpaEntity b WHERE b.userId = :userId ORDER BY b.createdAt ASC, b.id ASC")
    List<BusinessAccountJpaEntity> findByUserIdOrdered(@Param("userId") UUID userId);

    boolean existsByTaxId(String taxId);
}
