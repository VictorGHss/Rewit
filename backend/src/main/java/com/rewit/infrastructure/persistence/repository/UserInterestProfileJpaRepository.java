package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.UserInterestProfileJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserInterestProfileJpaRepository extends JpaRepository<UserInterestProfileJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM UserInterestProfileJpaEntity p WHERE p.userId = :userId")
    Optional<UserInterestProfileJpaEntity> findByUserIdForUpdate(@Param("userId") UUID userId);
}
