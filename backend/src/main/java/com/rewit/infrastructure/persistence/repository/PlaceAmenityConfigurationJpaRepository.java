package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.PlaceAmenityConfigurationJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PlaceAmenityConfigurationJpaRepository
        extends JpaRepository<PlaceAmenityConfigurationJpaEntity, UUID> {
}
