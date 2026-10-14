package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.LocalTime;
import java.util.Objects;

/**
 * Value Object imutável representando um intervalo de funcionamento [openTime, closeTime).
 */
public final class OpeningHoursInterval implements Comparable<OpeningHoursInterval> {

    private final LocalTime openTime;
    private final LocalTime closeTime;

    public OpeningHoursInterval(LocalTime openTime, LocalTime closeTime) {
        if (openTime == null || closeTime == null) {
            throw new BusinessException("Os horários de abertura e fechamento são obrigatórios",
                    HttpStatus.BAD_REQUEST, "INVALID_OPENING_HOURS_INTERVAL");
        }
        if (!openTime.isBefore(closeTime)) {
            throw new BusinessException("O horário de abertura deve ser anterior ao horário de fechamento",
                    HttpStatus.BAD_REQUEST, "INVALID_OPENING_HOURS_INTERVAL");
        }
        this.openTime = openTime;
        this.closeTime = closeTime;
    }

    public LocalTime getOpenTime() {
        return openTime;
    }

    public LocalTime getCloseTime() {
        return closeTime;
    }

    /**
     * Verifica se este intervalo se sobrepõe a outro intervalo no mesmo dia.
     * Considera intervalos semiabertos [openTime, closeTime). Intervalos consecutivos adjacentes
     * (onde closeTime de um coincide com openTime do outro) não se sobrepõem.
     */
    public boolean overlapsWith(OpeningHoursInterval other) {
        if (other == null) {
            return false;
        }
        return this.openTime.isBefore(other.closeTime) && other.openTime.isBefore(this.closeTime);
    }

    @Override
    public int compareTo(OpeningHoursInterval other) {
        int cmp = this.openTime.compareTo(other.openTime);
        if (cmp != 0) {
            return cmp;
        }
        return this.closeTime.compareTo(other.closeTime);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OpeningHoursInterval that = (OpeningHoursInterval) o;
        return Objects.equals(openTime, that.openTime) && Objects.equals(closeTime, that.closeTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(openTime, closeTime);
    }

    @Override
    public String toString() {
        return openTime + " - " + closeTime;
    }
}

