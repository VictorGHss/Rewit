package com.rewit.infrastructure.storagegc;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.storage.ObjectDeletionResult;
import com.rewit.application.dto.storage.StorageGcDtos.StorageGcCycleReport;
import com.rewit.application.dto.storage.StorageGcDtos.StorageGcLimits;
import com.rewit.application.dto.storage.StorageGcDtos.StorageGcRunStatus;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantinePurgeResolution;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantineResolution;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantinedStorageObject;
import com.rewit.application.dto.storage.StorageReconciliationDtos.OrphanCandidate;
import com.rewit.application.port.ObjectStorageDeletionPort;
import com.rewit.application.port.ObjectStorageListingPort;
import com.rewit.application.port.ObjectStoragePort;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.ReviewMediaRepository;
import com.rewit.application.port.StorageQuarantineRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.ReviewService;
import com.rewit.application.storage.FixedStorageQuarantineGracePolicy;
import com.rewit.application.usecase.PurgeConfirmedOrphanStorageObjectUseCase;
import com.rewit.application.usecase.RecheckQuarantinedStorageObjectUseCase;
import com.rewit.application.usecase.ReconcileReviewMediaStorageUseCase;
import com.rewit.application.usecase.RunStorageGcCycleUseCase;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.enums.StorageQuarantineStatus;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.ReviewMedia;
import com.rewit.domain.model.User;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ciclo real do GC contra PostgreSQL e SeaweedFS. A listagem e as consultas de quarentena são restritas ao
 * prefixo da review criada por cada teste: nenhum objeto ou linha de outros testes participa do ciclo.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração com PostgreSQL e SeaweedFS reais: ciclo do storage GC (Step 28.5)")
class StorageGcCycleIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-03-01T03:00:00Z");
    private static final Duration GRACE = Duration.ofMinutes(30);

    @Autowired
    private ObjectStoragePort storage;

    @Autowired
    private ObjectStorageListingPort listing;

    @Autowired
    private ReviewMediaRepository reviewMediaRepository;

    @Autowired
    private StorageQuarantineRepository quarantineRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateableTargetRepository targetRepository;

    private final List<String> ownKeys = new CopyOnWriteArrayList<>();
    private final MutableClock clock = new MutableClock(T0);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private String ownPrefix;
    private String orphanKey;
    private String referencedKey;

    @BeforeEach
    void createOwnObjects() {
        User author = createUser();
        UUID reviewId = createReview(author);
        ownPrefix = "reviews/" + reviewId + "/";
        orphanKey = put(ownPrefix + UUID.randomUUID() + "/image.jpg");
        UUID referencedMediaId = UUID.randomUUID();
        referencedKey = put(ownPrefix + referencedMediaId + "/image.jpg");
        reviewMediaRepository.save(new ReviewMedia(referencedMediaId, reviewId, author.getId(), referencedKey,
                ReviewMediaType.IMAGE, "image/jpeg", 1024, 10, 10));
    }

    // Remove somente os próprios objetos e encerra as próprias linhas de quarentena pelo fluxo normal
    @AfterEach
    void removeOwnState() {
        RecheckQuarantinedStorageObjectUseCase recheck = recheck();
        PurgeConfirmedOrphanStorageObjectUseCase purge = new PurgeConfirmedOrphanStorageObjectUseCase(quarantineRepository, storage);
        for (String key : ownKeys) {
            storage.delete(key);
            recheck.recheck(key, T0.plus(Duration.ofDays(365)));
            purge.purge(key);
        }
    }

    @Test
    @DisplayName("Dry-run real: observa e confirma o órfão, conta a exclusão, mas nenhum objeto é removido")
    void dryRunAgainstRealStorage() {
        RunStorageGcCycleUseCase gc = RunStorageGcCycleUseCase.dryRun(new PostgresAdvisoryStorageGcExecutionLock(dataSource),
                reconcile(), scopedQuarantine(), recheck(), metrics(), clock, limits());

        StorageGcCycleReport first = gc.runCycle();
        clock.set(T0.plus(GRACE));
        StorageGcCycleReport second = gc.runCycle();

        assertEquals(StorageGcRunStatus.COMPLETED, first.status());
        assertEquals(2, first.objectsListed());
        assertEquals(1, first.candidatesObserved());
        assertEquals(1, first.gracePending());
        assertEquals(1, second.confirmedOrphans());
        assertEquals(1, second.wouldDelete());
        assertEquals(0, second.deletesAttempted());
        assertTrue(storage.exists(orphanKey));
        assertTrue(storage.exists(referencedKey));
        assertEquals(StorageQuarantineStatus.CONFIRMED_ORPHAN, quarantineRepository.findByObjectKey(orphanKey).orElseThrow().status());
        assertTrue(quarantineRepository.findByObjectKey(referencedKey).isEmpty(), "objeto referenciado nunca entra na quarentena");
        assertEquals(2, registry.counter(StorageGcMetrics.COUNTER_RUNS_DRY_RUN).count());
    }

    @Test
    @DisplayName("Ciclo destrutivo real: remove só o órfão do próprio teste, preserva o referenciado e repetir é idempotente")
    void destructiveCycleAgainstRealStorage() {
        RunStorageGcCycleUseCase gc = destructive(storage);

        gc.runCycle();
        assertTrue(storage.exists(orphanKey), "dentro do grace period nada é removido");
        clock.set(T0.plus(GRACE));
        StorageGcCycleReport second = gc.runCycle();
        StorageGcCycleReport third = gc.runCycle();

        assertEquals(StorageGcRunStatus.COMPLETED, second.status());
        assertEquals(1, second.deleted());
        assertFalse(storage.exists(orphanKey));
        assertTrue(storage.exists(referencedKey));
        assertTrue(quarantineRepository.findByObjectKey(orphanKey).isEmpty());
        assertEquals(0, third.deletesAttempted());
        assertEquals(0, third.candidatesObserved());
        assertEquals(1, registry.counter(StorageGcMetrics.COUNTER_DELETES_SUCCEEDED).count());
    }

    @Test
    @DisplayName("Falha transitória real: a quarentena permanece CONFIRMED_ORPHAN e o ciclo seguinte conclui")
    void retryableFailureKeepsQuarantine() {
        ObjectStorageDeletionPort unavailable = key -> ObjectDeletionResult.TRANSIENT_FAILURE;
        destructive(unavailable).runCycle();
        clock.set(T0.plus(GRACE));

        StorageGcCycleReport failed = destructive(unavailable).runCycle();

        assertEquals(1, failed.retryableFailures());
        assertTrue(storage.exists(orphanKey));
        assertEquals(StorageQuarantineStatus.CONFIRMED_ORPHAN, quarantineRepository.findByObjectKey(orphanKey).orElseThrow().status());

        StorageGcCycleReport retried = destructive(storage).runCycle();
        assertEquals(1, retried.deleted());
        assertFalse(storage.exists(orphanKey));
    }

    private RunStorageGcCycleUseCase destructive(ObjectStorageDeletionPort deletionPort) {
        return RunStorageGcCycleUseCase.destructive(new PostgresAdvisoryStorageGcExecutionLock(dataSource), reconcile(),
                scopedQuarantine(), recheck(), new PurgeConfirmedOrphanStorageObjectUseCase(quarantineRepository, deletionPort),
                metrics(), clock, limits());
    }

    private ReconcileReviewMediaStorageUseCase reconcile() {
        ObjectStorageListingPort scopedListing = (prefix, startAfter, maxKeys) -> listing.listObjects(ownPrefix, startAfter, maxKeys);
        return new ReconcileReviewMediaStorageUseCase(scopedListing, reviewMediaRepository, quarantineRepository, 10, 1);
    }

    private RecheckQuarantinedStorageObjectUseCase recheck() {
        return new RecheckQuarantinedStorageObjectUseCase(quarantineRepository, new FixedStorageQuarantineGracePolicy(GRACE));
    }

    private StorageGcMetrics metrics() {
        return new StorageGcMetrics(registry, quarantineRepository);
    }

    private static StorageGcLimits limits() {
        return new StorageGcLimits(10, 10, 10, Duration.ofHours(1), 3);
    }

    private StorageQuarantineRepository scopedQuarantine() {
        return new OwnPrefixQuarantine(quarantineRepository, ownPrefix);
    }

    private String put(String key) {
        ownKeys.add(key);
        storage.put(key, "image/jpeg", "gc-cycle-test".getBytes(StandardCharsets.UTF_8));
        return key;
    }

    private User createUser() {
        String unique = "sgc_" + UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.save(new User(null, unique + "@rewit.test", "Password123!", AuthProvider.LOCAL, null));
        profileRepository.save(new Profile(UUID.randomUUID(), user.getId(), "u_" + unique, "User " + unique, "Bio", null));
        return user;
    }

    private UUID createReview(User user) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Place place = placeRepository.save(new Place(
                null, "Restaurante SGC " + unique, "restaurante-sgc-" + unique,
                "RESTAURANTE", "Descrição", "Rua Teste, 100", "100", "Centro", "São Paulo", "SP", "BR",
                -23.5505, -46.6333, 50, "USER", false, null, "ACTIVE"
        ));
        RateableTarget target = targetRepository.save(new RateableTarget(UUID.randomUUID(), TargetType.PLACE));
        return reviewService.createReview(new CreateReviewCommand(
                user.getId(), place.getId(), "Review para teste do ciclo de storage GC", false, "PUBLIC",
                null, null, null,
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("4.0"), "Bom"))
        )).id();
    }

    /** Quarentena real, com as consultas por status restritas ao prefixo do teste. */
    private static final class OwnPrefixQuarantine implements StorageQuarantineRepository {

        private final StorageQuarantineRepository delegate;
        private final String prefix;

        OwnPrefixQuarantine(StorageQuarantineRepository delegate, String prefix) {
            this.delegate = delegate;
            this.prefix = prefix;
        }

        @Override
        public List<QuarantinedStorageObject> findByStatus(StorageQuarantineStatus status, int limit) {
            return delegate.findByStatus(status, 10_000).stream()
                    .filter(entry -> entry.objectKey().startsWith(prefix))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void recordObservations(Collection<OrphanCandidate> candidates) {
            delegate.recordObservations(candidates);
        }

        @Override
        public Optional<QuarantinedStorageObject> findByObjectKey(String objectKey) {
            return delegate.findByObjectKey(objectKey);
        }

        @Override
        public long countByStatus(StorageQuarantineStatus status) {
            return delegate.countByStatus(status);
        }

        @Override
        public QuarantineResolution resolveUnderCreationLock(String objectKey, UUID reviewId,
                                                             Instant expectedFirstObservedAt, Instant now) {
            return delegate.resolveUnderCreationLock(objectKey, reviewId, expectedFirstObservedAt, now);
        }

        @Override
        public QuarantinePurgeResolution purgeConfirmedOrphanUnderCreationLock(String objectKey, UUID reviewId,
                                                                               Instant expectedFirstObservedAt,
                                                                               Function<String, ObjectDeletionResult> physicalDeletion) {
            return delegate.purgeConfirmedOrphanUnderCreationLock(objectKey, reviewId, expectedFirstObservedAt, physicalDeletion);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
