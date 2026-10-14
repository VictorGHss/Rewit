package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.PlaceOpeningHoursRepository;
import com.rewit.domain.model.OpeningHoursInterval;
import com.rewit.domain.model.PlaceDaySchedule;
import com.rewit.domain.model.PlaceOpeningHoursSchedule;
import com.rewit.infrastructure.persistence.entity.PlaceOpeningHoursIntervalJpaEntity;
import com.rewit.infrastructure.persistence.entity.PlaceOpeningHoursScheduleJpaEntity;
import com.rewit.infrastructure.persistence.repository.PlaceOpeningHoursIntervalJpaRepository;
import com.rewit.infrastructure.persistence.repository.PlaceOpeningHoursScheduleJpaRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class PlaceOpeningHoursRepositoryAdapter implements PlaceOpeningHoursRepository {

    private final PlaceOpeningHoursScheduleJpaRepository scheduleRepository;
    private final PlaceOpeningHoursIntervalJpaRepository intervalRepository;

    public PlaceOpeningHoursRepositoryAdapter(
            PlaceOpeningHoursScheduleJpaRepository scheduleRepository,
            PlaceOpeningHoursIntervalJpaRepository intervalRepository
    ) {
        this.scheduleRepository = Objects.requireNonNull(scheduleRepository,
                "PlaceOpeningHoursScheduleJpaRepository must not be null");
        this.intervalRepository = Objects.requireNonNull(intervalRepository,
                "PlaceOpeningHoursIntervalJpaRepository must not be null");
    }

    @Override
    public Optional<PlaceOpeningHoursSchedule> findScheduleByPlaceId(UUID placeId) {
        if (placeId == null) {
            return Optional.empty();
        }

        Optional<PlaceOpeningHoursScheduleJpaEntity> scheduleOpt = scheduleRepository.findById(placeId);
        if (scheduleOpt.isEmpty()) {
            return Optional.empty();
        }

        PlaceOpeningHoursScheduleJpaEntity scheduleEntity = scheduleOpt.get();
        List<PlaceOpeningHoursIntervalJpaEntity> intervalEntities =
                intervalRepository.findByPlaceIdOrderByDayOfWeekAscOpenTimeAsc(placeId);

        Map<Integer, List<OpeningHoursInterval>> intervalsByDay = new HashMap<>();
        for (PlaceOpeningHoursIntervalJpaEntity entity : intervalEntities) {
            intervalsByDay.computeIfAbsent((int) entity.getDayOfWeek(), k -> new ArrayList<>())
                    .add(new OpeningHoursInterval(entity.getOpenTime(), entity.getCloseTime()));
        }

        List<PlaceDaySchedule> daySchedules = new ArrayList<>(7);
        for (int d = 1; d <= 7; d++) {
            List<OpeningHoursInterval> dayIntervals = intervalsByDay.get(d);
            if (dayIntervals == null || dayIntervals.isEmpty()) {
                daySchedules.add(PlaceDaySchedule.closed(d));
            } else {
                daySchedules.add(PlaceDaySchedule.open(d, dayIntervals));
            }
        }

        return Optional.of(new PlaceOpeningHoursSchedule(
                scheduleEntity.getPlaceId(),
                scheduleEntity.getBusinessAccountId(),
                scheduleEntity.getUpdatedByUserId(),
                scheduleEntity.getCreatedAt(),
                scheduleEntity.getUpdatedAt(),
                daySchedules
        ));
    }

    @Override
    public void saveSchedule(PlaceOpeningHoursSchedule schedule) {
        Objects.requireNonNull(schedule, "schedule must not be null");

        PlaceOpeningHoursScheduleJpaEntity entity = scheduleRepository.findById(schedule.getPlaceId())
                .orElseGet(() -> new PlaceOpeningHoursScheduleJpaEntity(
                        schedule.getPlaceId(),
                        schedule.getBusinessAccountId(),
                        schedule.getUpdatedByUserId(),
                        schedule.getCreatedAt(),
                        schedule.getUpdatedAt()
                ));

        entity.setBusinessAccountId(schedule.getBusinessAccountId());
        entity.setUpdatedByUserId(schedule.getUpdatedByUserId());
        entity.setUpdatedAt(schedule.getUpdatedAt());
        scheduleRepository.save(entity);

        // Substituição atômica dos intervalos
        intervalRepository.deleteByPlaceId(schedule.getPlaceId());

        List<PlaceOpeningHoursIntervalJpaEntity> intervalEntities = new ArrayList<>();
        Instant now = Instant.now();

        for (PlaceDaySchedule day : schedule.getWeeklySchedule()) {
            if (day.isClosed()) {
                continue;
            }
            for (OpeningHoursInterval interval : day.getIntervals()) {
                intervalEntities.add(new PlaceOpeningHoursIntervalJpaEntity(
                        UUID.randomUUID(),
                        schedule.getPlaceId(),
                        (short) day.getDayOfWeek(),
                        interval.getOpenTime(),
                        interval.getCloseTime(),
                        now
                ));
            }
        }

        if (!intervalEntities.isEmpty()) {
            intervalRepository.saveAll(intervalEntities);
        }
    }
}
