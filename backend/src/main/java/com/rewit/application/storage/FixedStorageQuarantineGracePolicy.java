package com.rewit.application.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Grace period de duração fixa. O valor é sempre fornecido por quem monta a política: este passo
 * não define um valor operacional padrão.
 */
public final class FixedStorageQuarantineGracePolicy implements StorageQuarantineGracePolicy {

    private final Duration gracePeriod;

    public FixedStorageQuarantineGracePolicy(Duration gracePeriod) {
        Objects.requireNonNull(gracePeriod, "gracePeriod must not be null");
        if (gracePeriod.isZero() || gracePeriod.isNegative()) {
            throw new IllegalArgumentException("gracePeriod must be positive");
        }
        this.gracePeriod = gracePeriod;
    }

    @Override
    public Instant eligibleAt(Instant firstObservedAt) {
        return Objects.requireNonNull(firstObservedAt, "firstObservedAt must not be null").plus(gracePeriod);
    }
}
