package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.PlaceOpeningHoursIntervalJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PlaceOpeningHoursIntervalJpaRepository
        extends JpaRepository<PlaceOpeningHoursIntervalJpaEntity, UUID> {

    List<PlaceOpeningHoursIntervalJpaEntity> findByPlaceIdOrderByDayOfWeekAscOpenTimeAsc(UUID placeId);

    @Modifying
    @Query("DELETE FROM PlaceOpeningHoursIntervalJpaEntity i WHERE i.placeId = :placeId")
    void deleteByPlaceId(@Param("placeId") UUID placeId);
}
