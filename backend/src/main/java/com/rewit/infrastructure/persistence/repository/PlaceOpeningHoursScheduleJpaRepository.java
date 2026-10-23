package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.PlaceOpeningHoursScheduleJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PlaceOpeningHoursScheduleJpaRepository
        extends JpaRepository<PlaceOpeningHoursScheduleJpaEntity, UUID> {
}
