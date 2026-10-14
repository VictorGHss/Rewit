package com.rewit.application.dto.catalog;

import com.rewit.domain.model.OpeningHoursInterval;
import com.rewit.domain.model.PlaceDaySchedule;
import com.rewit.domain.model.PlaceOpeningHoursSchedule;

import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * DTOs da camada de aplicação para horários de funcionamento.
 */
public final class PlaceOpeningHoursDtos {

    private PlaceOpeningHoursDtos() {
    }

    public record TimeIntervalCommand(
            LocalTime openTime,
            LocalTime closeTime
    ) {
        public OpeningHoursInterval toDomain() {
            return new OpeningHoursInterval(openTime, closeTime);
        }
    }

    public record DayScheduleCommand(
            int dayOfWeek,
            boolean closed,
            List<TimeIntervalCommand> intervals
    ) {
        public PlaceDaySchedule toDomain() {
            List<OpeningHoursInterval> domainIntervals = intervals != null
                    ? intervals.stream().map(TimeIntervalCommand::toDomain).toList()
                    : Collections.emptyList();
            return new PlaceDaySchedule(dayOfWeek, closed, domainIntervals);
        }
    }

    public record UpdatePlaceOpeningHoursCommand(
            UUID actorUserId,
            UUID placeId,
            UUID businessAccountId,
            List<DayScheduleCommand> days
    ) {
    }

    public record TimeIntervalView(
            LocalTime openTime,
            LocalTime closeTime
    ) {
        public static TimeIntervalView fromDomain(OpeningHoursInterval interval) {
            return new TimeIntervalView(interval.getOpenTime(), interval.getCloseTime());
        }
    }

    public record DayScheduleView(
            int dayOfWeek,
            String dayName,
            boolean closed,
            List<TimeIntervalView> intervals
    ) {
        public static DayScheduleView fromDomain(PlaceDaySchedule schedule) {
            return new DayScheduleView(
                    schedule.getDayOfWeek(),
                    schedule.getDayOfWeekEnum().name(),
                    schedule.isClosed(),
                    schedule.getIntervals().stream().map(TimeIntervalView::fromDomain).toList()
            );
        }
    }

    public record PlaceOpeningHoursView(
            UUID placeId,
            boolean configured,
            List<DayScheduleView> days
    ) {
        public static PlaceOpeningHoursView fromDomain(PlaceOpeningHoursSchedule schedule) {
            List<DayScheduleView> dayViews = schedule.getWeeklySchedule().stream()
                    .map(DayScheduleView::fromDomain)
                    .toList();
            return new PlaceOpeningHoursView(schedule.getPlaceId(), true, dayViews);
        }

        public static PlaceOpeningHoursView unconfigured(UUID placeId) {
            return new PlaceOpeningHoursView(placeId, false, Collections.emptyList());
        }
    }
}
