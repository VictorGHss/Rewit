package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Representa os horários de funcionamento de um dia específico da semana (1 = Segunda-feira ... 7 = Domingo).
 */
public final class PlaceDaySchedule {

    private final int dayOfWeek;
    private final boolean closed;
    private final List<OpeningHoursInterval> intervals;

    public PlaceDaySchedule(int dayOfWeek, boolean closed, List<OpeningHoursInterval> intervals) {
        if (dayOfWeek < 1 || dayOfWeek > 7) {
            throw new BusinessException("Dia da semana deve estar entre 1 (Segunda-feira) e 7 (Domingo)",
                    HttpStatus.BAD_REQUEST, "INVALID_DAY_OF_WEEK");
        }

        this.dayOfWeek = dayOfWeek;
        this.closed = closed;

        if (closed) {
            if (intervals != null && !intervals.isEmpty()) {
                throw new BusinessException("Dias marcados como fechados não podem conter intervalos de horário",
                        HttpStatus.BAD_REQUEST, "INVALID_OPENING_HOURS");
            }
            this.intervals = Collections.emptyList();
        } else {
            if (intervals == null || intervals.isEmpty()) {
                throw new BusinessException("Dias abertos devem conter pelo menos um intervalo de funcionamento",
                        HttpStatus.BAD_REQUEST, "INVALID_OPENING_HOURS");
            }
            // Ordena os intervalos e valida ausência de sobreposição
            List<OpeningHoursInterval> sorted = new ArrayList<>(intervals);
            Collections.sort(sorted);
            validateNoOverlap(sorted);
            this.intervals = Collections.unmodifiableList(sorted);
        }
    }

    public static PlaceDaySchedule closed(int dayOfWeek) {
        return new PlaceDaySchedule(dayOfWeek, true, Collections.emptyList());
    }

    public static PlaceDaySchedule open(int dayOfWeek, List<OpeningHoursInterval> intervals) {
        return new PlaceDaySchedule(dayOfWeek, false, intervals);
    }

    private static void validateNoOverlap(List<OpeningHoursInterval> sortedIntervals) {
        for (int i = 0; i < sortedIntervals.size() - 1; i++) {
            OpeningHoursInterval current = sortedIntervals.get(i);
            OpeningHoursInterval next = sortedIntervals.get(i + 1);
            if (current.overlapsWith(next)) {
                throw new BusinessException("Existem intervalos de horário sobrepostos para o mesmo dia",
                        HttpStatus.BAD_REQUEST, "OPENING_HOURS_OVERLAPPING_INTERVALS");
            }
        }
    }

    public int getDayOfWeek() {
        return dayOfWeek;
    }

    public DayOfWeek getDayOfWeekEnum() {
        return DayOfWeek.of(dayOfWeek);
    }

    public boolean isClosed() {
        return closed;
    }

    public List<OpeningHoursInterval> getIntervals() {
        return intervals;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlaceDaySchedule that = (PlaceDaySchedule) o;
        return dayOfWeek == that.dayOfWeek && closed == that.closed && Objects.equals(intervals, that.intervals);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dayOfWeek, closed, intervals);
    }
}

