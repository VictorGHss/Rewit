package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.*;

/**
 * Agregado de Domínio representando o cronograma semanal de funcionamento de um local reivindicado.
 */
public final class PlaceOpeningHoursSchedule {

    private final UUID placeId;
    private final UUID businessAccountId;
    private final UUID updatedByUserId;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Map<Integer, PlaceDaySchedule> daySchedules;

    public PlaceOpeningHoursSchedule(
            UUID placeId,
            UUID businessAccountId,
            UUID updatedByUserId,
            Instant createdAt,
            Instant updatedAt,
            Collection<PlaceDaySchedule> days
    ) {
        if (placeId == null) {
            throw new BusinessException("O local é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_PLACE_ID");
        }
        if (businessAccountId == null) {
            throw new BusinessException("A conta comercial é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_BUSINESS_ACCOUNT_ID");
        }
        if (updatedByUserId == null) {
            throw new BusinessException("O usuário responsável é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_ACTOR_USER_ID");
        }

        this.placeId = placeId;
        this.businessAccountId = businessAccountId;
        this.updatedByUserId = updatedByUserId;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;

        Map<Integer, PlaceDaySchedule> map = new TreeMap<>();
        if (days != null) {
            for (PlaceDaySchedule day : days) {
                if (day == null) continue;
                if (map.containsKey(day.getDayOfWeek())) {
                    throw new BusinessException("O dia da semana não pode ser repetido na requisição",
                            HttpStatus.BAD_REQUEST, "DUPLICATE_DAY_OF_WEEK");
                }
                map.put(day.getDayOfWeek(), day);
            }
        }

        // Garante que todos os 7 dias da semana (1..7) estejam presentes; dias não informados são fechados
        for (int d = 1; d <= 7; d++) {
            map.putIfAbsent(d, PlaceDaySchedule.closed(d));
        }

        this.daySchedules = Collections.unmodifiableMap(map);
    }

    public static PlaceOpeningHoursSchedule create(
            UUID placeId,
            UUID businessAccountId,
            UUID updatedByUserId,
            Collection<PlaceDaySchedule> days
    ) {
        Instant now = Instant.now();
        return new PlaceOpeningHoursSchedule(placeId, businessAccountId, updatedByUserId, now, now, days);
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public UUID getBusinessAccountId() {
        return businessAccountId;
    }

    public UUID getUpdatedByUserId() {
        return updatedByUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public PlaceDaySchedule getDay(int dayOfWeek) {
        return daySchedules.get(dayOfWeek);
    }

    public List<PlaceDaySchedule> getWeeklySchedule() {
        return new ArrayList<>(daySchedules.values());
    }

    public boolean isClosedOn(int dayOfWeek) {
        PlaceDaySchedule schedule = daySchedules.get(dayOfWeek);
        return schedule == null || schedule.isClosed();
    }
}

