package com.rewit.application.usecase;

import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantineRecheckOutcome;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantineRecheckResult;
import com.rewit.application.dto.storage.StorageReconciliationDtos.OrphanCandidate;
import com.rewit.application.port.ObjectStorageListingPort;
import com.rewit.application.port.ObjectStoragePort;
import com.rewit.application.storage.FixedStorageQuarantineGracePolicy;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.enums.StorageQuarantineStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários: RecheckQuarantinedStorageObjectUseCase (Step 28.3)")
class RecheckQuarantinedStorageObjectUseCaseTest {

    private static final Instant FIRST_OBSERVED = Instant.parse("2026-10-03T12:00:00Z");
    private static final Duration GRACE = Duration.ofMinutes(90);
    private static final Instant ELIGIBLE = FIRST_OBSERVED.plus(GRACE);

    private final UUID reviewId = UUID.randomUUID();
    private final String key = "reviews/" + reviewId + "/" + UUID.randomUUID() + "/image.jpg";
    private final InMemoryStorageQuarantineRepository quarantine = new InMemoryStorageQuarantineRepository();
    private final RecheckQuarantinedStorageObjectUseCase useCase =
            new RecheckQuarantinedStorageObjectUseCase(quarantine, new FixedStorageQuarantineGracePolicy(GRACE));

    @Test
    @DisplayName("Grace period não terminado: não consulta o banco nem altera a quarentena")
    void gracePeriodNotElapsed() {
        observe(FIRST_OBSERVED);

        QuarantineRecheckResult result = useCase.recheck(key, ELIGIBLE.minusMillis(1));

        assertEquals(QuarantineRecheckOutcome.GRACE_PERIOD_NOT_ELAPSED, result.outcome());
        assertEquals(FIRST_OBSERVED, result.firstObservedAt());
        assertEquals(ELIGIBLE, result.eligibleAt());
        assertTrue(quarantine.lockedReviewIds.isEmpty());
        assertEquals(StorageQuarantineStatus.OBSERVED, quarantine.entries.get(key).status());
    }

    @Test
    @DisplayName("Grace period conta da primeira observação, não da mais recente")
    void gracePeriodCountsFromFirstObservation() {
        observe(FIRST_OBSERVED);
        observe(ELIGIBLE.minusSeconds(1));

        QuarantineRecheckResult result = useCase.recheck(key, ELIGIBLE);

        assertEquals(QuarantineRecheckOutcome.CONFIRMED_ORPHAN, result.outcome());
        assertEquals(FIRST_OBSERVED, result.firstObservedAt());
    }

    @Test
    @DisplayName("Grace period terminado e sem referência: CONFIRMED_ORPHAN sob o lock da review da chave")
    void confirmedOrphanAfterGracePeriod() {
        observe(FIRST_OBSERVED);

        QuarantineRecheckResult result = useCase.recheck(key, ELIGIBLE);

        assertEquals(QuarantineRecheckOutcome.CONFIRMED_ORPHAN, result.outcome());
        assertNull(result.referenceStatus());
        assertEquals(ELIGIBLE, result.checkedAt());
        assertEquals(List.of(reviewId), quarantine.lockedReviewIds);
        assertEquals(StorageQuarantineStatus.CONFIRMED_ORPHAN, quarantine.entries.get(key).status());
        assertEquals(ELIGIBLE, quarantine.entries.get(key).confirmedAt());
    }

    @Test
    @DisplayName("Segunda consulta encontra referência ACTIVE: WITH_REFERENCE e quarentena liberada")
    void activeReferenceReleasesQuarantine() {
        observe(FIRST_OBSERVED);
        quarantine.references.put(key, ReviewMediaStatus.ACTIVE);

        QuarantineRecheckResult result = useCase.recheck(key, ELIGIBLE);

        assertEquals(QuarantineRecheckOutcome.WITH_REFERENCE, result.outcome());
        assertEquals(ReviewMediaStatus.ACTIVE, result.referenceStatus());
        assertFalse(quarantine.entries.containsKey(key));
    }

    @Test
    @DisplayName("Segunda consulta encontra referência REMOVED: continua sendo referência, não órfão")
    void removedReferenceIsStillAReference() {
        observe(FIRST_OBSERVED);
        quarantine.references.put(key, ReviewMediaStatus.REMOVED);

        QuarantineRecheckResult result = useCase.recheck(key, ELIGIBLE);

        assertEquals(QuarantineRecheckOutcome.WITH_REFERENCE, result.outcome());
        assertEquals(ReviewMediaStatus.REMOVED, result.referenceStatus());
    }

    @Test
    @DisplayName("Referência criada entre duas execuções: a segunda rechecagem deixa de confirmar")
    void referenceCreatedBetweenRuns() {
        observe(FIRST_OBSERVED);
        assertEquals(QuarantineRecheckOutcome.GRACE_PERIOD_NOT_ELAPSED, useCase.recheck(key, FIRST_OBSERVED).outcome());

        quarantine.references.put(key, ReviewMediaStatus.ACTIVE);

        assertEquals(QuarantineRecheckOutcome.WITH_REFERENCE, useCase.recheck(key, ELIGIBLE).outcome());
        assertEquals(QuarantineRecheckOutcome.NOT_QUARANTINED, useCase.recheck(key, ELIGIBLE).outcome());
    }

    @Test
    @DisplayName("Rechecagem repetida é idempotente e preserva o primeiro confirmedAt")
    void repeatedRecheckIsIdempotent() {
        observe(FIRST_OBSERVED);

        QuarantineRecheckResult first = useCase.recheck(key, ELIGIBLE);
        QuarantineRecheckResult second = useCase.recheck(key, ELIGIBLE.plusSeconds(60));

        assertEquals(QuarantineRecheckOutcome.CONFIRMED_ORPHAN, first.outcome());
        assertEquals(QuarantineRecheckOutcome.CONFIRMED_ORPHAN, second.outcome());
        assertEquals(1, quarantine.entries.size());
        assertEquals(ELIGIBLE, quarantine.entries.get(key).confirmedAt());
    }

    @Test
    @DisplayName("Chave sem quarentena: NOT_QUARANTINED, sem lock nem consulta")
    void unknownKeyIsNotQuarantined() {
        QuarantineRecheckResult result = useCase.recheck(key, ELIGIBLE);

        assertEquals(QuarantineRecheckOutcome.NOT_QUARANTINED, result.outcome());
        assertNull(result.firstObservedAt());
        assertTrue(quarantine.lockedReviewIds.isEmpty());
    }

    @Test
    @DisplayName("Chave malformada ou fora de reviews/ é rejeitada antes de qualquer consulta")
    void invalidKeysAreRejected() {
        for (String invalid : Arrays.asList(null, "avatars/x.jpg", "reviews/manual-upload.jpg",
                "reviews/" + reviewId + "/" + UUID.randomUUID() + "/image.gif")) {
            assertThrows(IllegalArgumentException.class, () -> useCase.recheck(invalid, ELIGIBLE), String.valueOf(invalid));
        }
        assertTrue(quarantine.lockedReviewIds.isEmpty());
    }

    @Test
    @DisplayName("Política de grace period exige duração positiva e não tem valor padrão")
    void gracePolicyRequiresExplicitPositiveDuration() {
        assertThrows(NullPointerException.class, () -> new FixedStorageQuarantineGracePolicy(null));
        assertThrows(IllegalArgumentException.class, () -> new FixedStorageQuarantineGracePolicy(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new FixedStorageQuarantineGracePolicy(Duration.ofSeconds(-1)));
        assertEquals(ELIGIBLE, new FixedStorageQuarantineGracePolicy(GRACE).eligibleAt(FIRST_OBSERVED));
    }

    @Test
    @DisplayName("O use case de rechecagem não recebe nenhuma porta de storage")
    void recheckHasNoStorageAccess() {
        List<Class<?>> storagePorts = List.of(ObjectStoragePort.class, ObjectStorageListingPort.class);
        for (Field field : RecheckQuarantinedStorageObjectUseCase.class.getDeclaredFields()) {
            assertFalse(storagePorts.contains(field.getType()), "campo " + field.getName());
        }
        for (Constructor<?> constructor : RecheckQuarantinedStorageObjectUseCase.class.getConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertFalse(storagePorts.contains(parameter), parameter.getName());
            }
        }
    }

    private void observe(Instant observedAt) {
        quarantine.recordObservations(List.of(new OrphanCandidate(key, 10, null, observedAt)));
    }
}
