package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.ProfileJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para a entidade ProfileJpaEntity.
 */
public interface ProfileJpaRepository extends JpaRepository<ProfileJpaEntity, UUID> {

    @Query("SELECT p FROM ProfileJpaEntity p WHERE p.user.id = :userId")
    Optional<ProfileJpaEntity> findByUserId(@Param("userId") UUID userId);

    @Query("SELECT p FROM ProfileJpaEntity p WHERE p.user.id IN :userIds")
    java.util.List<ProfileJpaEntity> findByUserIdIn(@Param("userIds") java.util.Collection<UUID> userIds);

    @Query("SELECT p FROM ProfileJpaEntity p WHERE LOWER(p.handle) = LOWER(:handle)")
    Optional<ProfileJpaEntity> findByHandleIgnoreCase(@Param("handle") String handle);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM ProfileJpaEntity p WHERE LOWER(p.handle) = LOWER(:handle)")
    boolean existsByHandleIgnoreCase(@Param("handle") String handle);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM ProfileJpaEntity p WHERE p.user.id = :userId")
    boolean existsByUserId(@Param("userId") UUID userId);
}
