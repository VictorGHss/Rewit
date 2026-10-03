package com.rewit.infrastructure.storage;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.storage.ObjectDeletionResult;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantineRecheckOutcome;
import com.rewit.application.dto.storage.StorageQuarantineDtos.StoragePurgeOutcome;
import com.rewit.application.dto.storage.StorageQuarantineDtos.StoragePurgeResult;
import com.rewit.application.dto.storage.StorageReconciliationDtos.OrphanCandidate;
import com.rewit.application.port.ObjectStorageDeletionPort;
import com.rewit.application.port.ObjectStoragePort;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.ReviewMediaRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.StorageQuarantineRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.ReviewService;
import com.rewit.application.storage.FixedStorageQuarantineGracePolicy;
import com.rewit.application.usecase.PurgeConfirmedOrphanStorageObjectUseCase;
import com.rewit.application.usecase.RecheckQuarantinedStorageObjectUseCase;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.enums.StorageQuarantineStatus;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.ReviewMedia;
import com.rewit.domain.model.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração com PostgreSQL e SeaweedFS reais: exclusão segura de órfãos (Step 28.4)")
class StorageOrphanPurgeIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-02-01T08:00:00Z");
    private static final Duration GRACE = Duration.ofMinutes(30);

    @Autowired
    private ObjectStoragePort storage;

    @Autowired
    private StorageQuarantineRepository quarantineRepository;

    @Autowired
    private ReviewMediaRepository reviewMediaRepository;

    @Autowired
    private ReviewRepository reviewRepository;

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    // Somente objetos criados por este teste; nada mais no bucket é tocado
    private final List<String> createdObjects = new CopyOnWriteArrayList<>();

    @AfterEach
    void removeOwnObjects() {
        for (String key : createdObjects) {
            storage.delete(key);
        }
    }

    @Test
    @DisplayName("SeaweedFS real: objeto CONFIRMED_ORPHAN sob reviews/ é removido, a quarentena encerrada, e repetir é idempotente")
    void purgesConfirmedOrphanFromRealStorage() {
        String key = putManagedObject(UUID.randomUUID());
        confirmOrphan(key);

        StoragePurgeResult first = purgeUseCase(storage).purge(key);

        assertEquals(StoragePurgeOutcome.PURGED, first.outcome());
        assertEquals(ObjectDeletionResult.DELETED, first.deletionResult());
        assertFalse(storage.exists(key));
        assertTrue(quarantineRepository.findByObjectKey(key).isEmpty());
        assertEquals(StoragePurgeOutcome.NOT_QUARANTINED, purgeUseCase(storage).purge(key).outcome());
        assertEquals(ObjectDeletionResult.NOT_FOUND, storage.delete(key), "exclusão de objeto ausente é idempotente");
    }

    @Test
    @DisplayName("Janela de queda real: storage excluído e transação desfeita mantêm a quarentena; a nova tentativa obtém NOT_FOUND")
    void crashAfterPhysicalDeletionIsRecovered() {
        String key = putManagedObject(UUID.randomUUID());
        confirmOrphan(key);
        Instant firstObservedAt = quarantineRepository.findByObjectKey(key).orElseThrow().firstObservedAt();

        assertThrows(IllegalStateException.class, () -> quarantineRepository.purgeConfirmedOrphanUnderCreationLock(
                key, UUID.fromString(key.split("/")[1]), firstObservedAt, k -> {
                    assertEquals(ObjectDeletionResult.DELETED, storage.delete(k));
                    throw new IllegalStateException("queda simulada antes do commit");
                }));

        assertFalse(storage.exists(key), "rollback do PostgreSQL não desfaz a exclusão no storage");
        assertEquals(StorageQuarantineStatus.CONFIRMED_ORPHAN, quarantineRepository.findByObjectKey(key).orElseThrow().status());

        StoragePurgeResult retry = purgeUseCase(storage).purge(key);
        assertEquals(StoragePurgeOutcome.PURGED, retry.outcome());
        assertEquals(ObjectDeletionResult.NOT_FOUND, retry.deletionResult());
        assertTrue(quarantineRepository.findByObjectKey(key).isEmpty());
    }

    @Test
    @DisplayName("Corrida com upload: a exclusão espera o lock da review e, após o commit, encontra a referência e não apaga")
    void purgeWaitsForInFlightUploadAndKeepsObject() throws Exception {
        User author = createUser();
        UUID reviewId = createReview(author);
        UUID mediaId = UUID.randomUUID();
        String key = putObject("reviews/" + reviewId + "/" + mediaId + "/image.jpg");
        confirmOrphan(key);
        CountingDeletion counting = new CountingDeletion(storage, Duration.ZERO);

        CountDownLatch uploadHoldsLock = new CountDownLatch(1);
        CountDownLatch finishUpload = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Mesma sequência do upload: lock da review, objeto já no storage, INSERT, commit
            Future<?> upload = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                reviewRepository.findByIdForUpdate(reviewId).orElseThrow();
                uploadHoldsLock.countDown();
                saveMedia(reviewId, author.getId(), mediaId, key, false);
                await(finishUpload);
            }));
            assertTrue(uploadHoldsLock.await(10, TimeUnit.SECONDS));

            Future<StoragePurgeResult> purge = executor.submit(() -> purgeUseCase(counting).purge(key));

            assertThrows(TimeoutException.class, () -> purge.get(700, TimeUnit.MILLISECONDS));
            finishUpload.countDown();
            upload.get(10, TimeUnit.SECONDS);

            StoragePurgeResult result = purge.get(10, TimeUnit.SECONDS);
            assertEquals(StoragePurgeOutcome.WITH_REFERENCE, result.outcome());
            assertEquals(ReviewMediaStatus.ACTIVE, result.referenceStatus());
            assertEquals(0, counting.calls.size(), "objeto referenciado nunca chega ao storage delete");
            assertTrue(storage.exists(key));
            assertTrue(quarantineRepository.findByObjectKey(key).isEmpty());
        } finally {
            finishUpload.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Referência REMOVED criada após a confirmação protege o objeto")
    void removedReferenceProtectsObject() {
        User author = createUser();
        UUID reviewId = createReview(author);
        UUID mediaId = UUID.randomUUID();
        String key = putObject("reviews/" + reviewId + "/" + mediaId + "/image.jpg");
        confirmOrphan(key);
        saveMedia(reviewId, author.getId(), mediaId, key, true);
        CountingDeletion counting = new CountingDeletion(storage, Duration.ZERO);

        StoragePurgeResult result = purgeUseCase(counting).purge(key);

        assertEquals(StoragePurgeOutcome.WITH_REFERENCE, result.outcome());
        assertEquals(ReviewMediaStatus.REMOVED, result.referenceStatus());
        assertTrue(counting.calls.isEmpty());
        assertTrue(storage.exists(key));
    }

    @Test
    @DisplayName("Duas exclusões concorrentes da mesma chave: uma única chamada ao storage, a outra termina idempotente")
    void concurrentPurgesDeleteOnce() throws Exception {
        UUID reviewId = createReview(createUser());
        String key = putManagedObject(reviewId);
        confirmOrphan(key);
        // Storage lento: garante que a segunda execução chega enquanto a primeira ainda detém os locks
        CountingDeletion counting = new CountingDeletion(storage, Duration.ofMillis(400));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<StoragePurgeResult> results = new ArrayList<>();
        try {
            List<Future<StoragePurgeResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return purgeUseCase(counting).purge(key);
                }));
            }
            for (Future<StoragePurgeResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, counting.calls.size());
        assertEquals(1, results.stream().filter(r -> r.outcome() == StoragePurgeOutcome.PURGED).count());
        assertEquals(1, results.stream().filter(r -> r.outcome() == StoragePurgeOutcome.NOT_QUARANTINED).count());
        assertFalse(storage.exists(key));
        assertTrue(quarantineRepository.findByObjectKey(key).isEmpty());
    }

    private PurgeConfirmedOrphanStorageObjectUseCase purgeUseCase(ObjectStorageDeletionPort deletionPort) {
        return new PurgeConfirmedOrphanStorageObjectUseCase(quarantineRepository, deletionPort);
    }

    // Caminho real até CONFIRMED_ORPHAN: observação persistida + rechecagem após o grace period
    private void confirmOrphan(String key) {
        quarantineRepository.recordObservations(List.of(new OrphanCandidate(key, 16, null, T0)));
        assertEquals(QuarantineRecheckOutcome.CONFIRMED_ORPHAN,
                new RecheckQuarantinedStorageObjectUseCase(quarantineRepository, new FixedStorageQuarantineGracePolicy(GRACE))
                        .recheck(key, T0.plus(GRACE)).outcome());
    }

    private String putManagedObject(UUID reviewId) {
        return putObject("reviews/" + reviewId + "/" + UUID.randomUUID() + "/image.jpg");
    }

    private String putObject(String key) {
        createdObjects.add(key);
        storage.put(key, "image/jpeg", "orphan-test".getBytes(StandardCharsets.UTF_8));
        assertTrue(storage.exists(key));
        return key;
    }

    private void saveMedia(UUID reviewId, UUID userId, UUID mediaId, String key, boolean removed) {
        ReviewMedia media = new ReviewMedia(mediaId, reviewId, userId, key, ReviewMediaType.IMAGE, "image/jpeg", 1024, 10, 10);
        if (removed) {
            media.markRemoved();
        }
        reviewMediaRepository.save(media);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private User createUser() {
        String unique = "gc_" + UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.save(new User(null, unique + "@rewit.test", "Password123!", AuthProvider.LOCAL, null));
        profileRepository.save(new Profile(UUID.randomUUID(), user.getId(), "u_" + unique, "User " + unique, "Bio", null));
        return user;
    }

    private UUID createReview(User user) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Place place = placeRepository.save(new Place(
                null, "Restaurante GC " + unique, "restaurante-gc-" + unique,
                "RESTAURANTE", "Descrição", "Rua Teste, 100", "100", "Centro", "São Paulo", "SP", "BR",
                -23.5505, -46.6333, 50, "USER", false, null, "ACTIVE"
        ));
        RateableTarget target = targetRepository.save(new RateableTarget(UUID.randomUUID(), TargetType.PLACE));
        return reviewService.createReview(new CreateReviewCommand(
                user.getId(), place.getId(), "Review para teste de exclusão de órfãos", false, "PUBLIC",
                null, null, null,
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("4.0"), "Bom"))
        )).id();
    }

    /** Delega ao storage real, registrando cada chamada; o atraso opcional alonga a janela com locks mantidos. */
    private static final class CountingDeletion implements ObjectStorageDeletionPort {

        private final ObjectStorageDeletionPort delegate;
        private final Duration delay;
        private final List<String> calls = new CopyOnWriteArrayList<>();

        CountingDeletion(ObjectStorageDeletionPort delegate, Duration delay) {
            this.delegate = delegate;
            this.delay = delay;
        }

        @Override
        public ObjectDeletionResult delete(String key) {
            calls.add(key);
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return delegate.delete(key);
        }
    }
}
