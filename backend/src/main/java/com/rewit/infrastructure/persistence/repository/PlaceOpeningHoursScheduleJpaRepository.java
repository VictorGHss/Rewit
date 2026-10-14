package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.PlaceOpeningHoursScheduleJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PlaceOpeningHoursScheduleJpaRepository
        extends JpaRepository<PlaceOpeningHoursScheduleJpaEntity, UUID> {
}
