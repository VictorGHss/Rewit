package com.rewit.infrastructure.persistence;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.ReviewDetailView;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantineRecheckOutcome;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantineRecheckResult;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantinedStorageObject;
import com.rewit.application.dto.storage.StorageReconciliationDtos.OrphanCandidate;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.ReviewMediaRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.StorageQuarantineRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.ReviewService;
import com.rewit.application.storage.FixedStorageQuarantineGracePolicy;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração com PostgreSQL Real: quarentena e rechecagem de storage (Step 28.3)")
class StorageQuarantineIntegrationTest {

    // Instantes fixos: nenhuma decisão depende do relógio da máquina
    private static final Instant T0 = Instant.parse("2026-01-10T10:00:00Z");
    private static final Duration GRACE = Duration.ofMinutes(30);
    private static final Instant AFTER_GRACE = T0.plus(GRACE);

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Primeira observação cria a linha; reobservações preservam first_observed_at")
    void observationUpsertPreservesFirstObservedAt() {
        String key = randomKey(UUID.randomUUID());
        Instant modified = T0.minusSeconds(120);

        quarantineRepository.recordObservations(List.of(new OrphanCandidate(key, 10, modified, T0)));
        quarantineRepository.recordObservations(List.of(new OrphanCandidate(key, 10, null, T0.plusSeconds(600))));
        // Observação atrasada (passada mais antiga concluindo depois) não faz last_observed_at regredir
        quarantineRepository.recordObservations(List.of(new OrphanCandidate(key, 10, null, T0.plusSeconds(60))));

        QuarantinedStorageObject entry = quarantineRepository.findByObjectKey(key).orElseThrow();
        assertEquals(T0, entry.firstObservedAt());
        assertEquals(T0.plusSeconds(600), entry.lastObservedAt());
        assertEquals(modified, entry.lastModifiedAt(), "lastModified ausente não apaga o valor conhecido");
        assertEquals(StorageQuarantineStatus.OBSERVED, entry.status());
        assertNull(entry.confirmedAt());
        assertEquals(1, rowsFor(key));
    }

    @Test
    @DisplayName("Observações concorrentes da mesma chave convergem para uma única linha")
    void concurrentObservationsKeepSingleRow() throws Exception {
        String key = randomKey(UUID.randomUUID());
        int workers = 8;
        CyclicBarrier barrier = new CyclicBarrier(workers);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                Instant observedAt = T0.plusSeconds(i);
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    quarantineRepository.recordObservations(List.of(new OrphanCandidate(key, 10, null, observedAt)));
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, rowsFor(key));
        QuarantinedStorageObject entry = quarantineRepository.findByObjectKey(key).orElseThrow();
        assertEquals(T0.plusSeconds(workers - 1), entry.lastObservedAt());
        assertFalse(entry.firstObservedAt().isAfter(entry.lastObservedAt()));
    }

    @Test
    @DisplayName("Rechecagem real: sem referência confirma; repetir preserva confirmed_at; nenhum delete de storage")
    void recheckConfirmsOrphanIdempotently() {
        UUID reviewId = createReview(createUser()).id();
        String key = randomKey(reviewId);
        observe(key);

        assertEquals(QuarantineRecheckOutcome.GRACE_PERIOD_NOT_ELAPSED, recheck(key, AFTER_GRACE.minusSeconds(1)).outcome());
        QuarantineRecheckResult first = recheck(key, AFTER_GRACE);
        QuarantineRecheckResult second = recheck(key, AFTER_GRACE.plusSeconds(300));

        assertEquals(QuarantineRecheckOutcome.CONFIRMED_ORPHAN, first.outcome());
        assertEquals(QuarantineRecheckOutcome.CONFIRMED_ORPHAN, second.outcome());
        QuarantinedStorageObject entry = quarantineRepository.findByObjectKey(key).orElseThrow();
        assertEquals(StorageQuarantineStatus.CONFIRMED_ORPHAN, entry.status());
        assertEquals(AFTER_GRACE, entry.confirmedAt());
        assertEquals(1, rowsFor(key));
    }

    @Test
    @DisplayName("Rechecagem real encontra review_media ACTIVE ou REMOVED: WITH_REFERENCE e quarentena liberada")
    void recheckReleasesQuarantineForActiveAndRemovedReferences() {
        User author = createUser();
        UUID reviewId = createReview(author).id();
        ReviewMedia active = saveMedia(reviewId, author.getId());
        ReviewMedia removed = saveMedia(reviewId, author.getId());
        removed.markRemoved();
        reviewMediaRepository.save(removed);
        observe(active.getObjectKey());
        observe(removed.getObjectKey());

        QuarantineRecheckResult activeResult = recheck(active.getObjectKey(), AFTER_GRACE);
        QuarantineRecheckResult removedResult = recheck(removed.getObjectKey(), AFTER_GRACE);

        assertEquals(QuarantineRecheckOutcome.WITH_REFERENCE, activeResult.outcome());
        assertEquals(ReviewMediaStatus.ACTIVE, activeResult.referenceStatus());
        assertEquals(QuarantineRecheckOutcome.WITH_REFERENCE, removedResult.outcome());
        assertEquals(ReviewMediaStatus.REMOVED, removedResult.referenceStatus());
        assertEquals(0, rowsFor(active.getObjectKey()));
        assertEquals(0, rowsFor(removed.getObjectKey()));
    }

    @Test
    @DisplayName("Referência criada entre execuções: observada sem referência, rechecada com referência")
    void referenceCreatedBetweenRunsIsDetected() {
        User author = createUser();
        UUID reviewId = createReview(author).id();
        UUID mediaId = UUID.randomUUID();
        String key = "reviews/" + reviewId + "/" + mediaId + "/image.jpg";
        observe(key);

        saveMedia(reviewId, author.getId(), mediaId, key);

        assertEquals(QuarantineRecheckOutcome.WITH_REFERENCE, recheck(key, AFTER_GRACE).outcome());
        assertEquals(QuarantineRecheckOutcome.NOT_QUARANTINED, recheck(key, AFTER_GRACE).outcome());
    }

    @Test
    @DisplayName("Rechecagem espera o upload em andamento (lock da review) e então enxerga a referência")
    void recheckWaitsForInFlightUploadHoldingCreationLock() throws Exception {
        User author = createUser();
        UUID reviewId = createReview(author).id();
        UUID mediaId = UUID.randomUUID();
        String key = "reviews/" + reviewId + "/" + mediaId + "/image.jpg";
        observe(key);

        CountDownLatch uploadHoldsLock = new CountDownLatch(1);
        CountDownLatch finishUpload = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Mesma sequência de ReviewMediaService.uploadMedia: lock da review, objeto já no storage, INSERT, commit
            Future<?> upload = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                reviewRepository.findByIdForUpdate(reviewId).orElseThrow();
                uploadHoldsLock.countDown();
                saveMedia(reviewId, author.getId(), mediaId, key);
                await(finishUpload);
            }));
            assertTrue(uploadHoldsLock.await(10, TimeUnit.SECONDS));

            Future<QuarantineRecheckResult> recheck = executor.submit(() -> recheck(key, AFTER_GRACE));

            // Enquanto o upload não commita, a rechecagem fica bloqueada no lock da review
            assertThrows(java.util.concurrent.TimeoutException.class, () -> recheck.get(700, TimeUnit.MILLISECONDS));
            finishUpload.countDown();
            upload.get(10, TimeUnit.SECONDS);

            QuarantineRecheckResult result = recheck.get(10, TimeUnit.SECONDS);
            assertEquals(QuarantineRecheckOutcome.WITH_REFERENCE, result.outcome());
            assertEquals(ReviewMediaStatus.ACTIVE, result.referenceStatus());
            assertEquals(0, rowsFor(key));
        } finally {
            finishUpload.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Rechecagens concorrentes da mesma chave são serializadas e convergem para uma confirmação")
    void concurrentRechecksConverge() throws Exception {
        UUID reviewId = createReview(createUser()).id();
        String key = randomKey(reviewId);
        observe(key);

        int workers = 4;
        CyclicBarrier barrier = new CyclicBarrier(workers);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<QuarantineRecheckResult> results = new ArrayList<>();
        try {
            List<Future<QuarantineRecheckResult>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                Instant now = AFTER_GRACE.plusSeconds(i);
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return recheck(key, now);
                }));
            }
            for (Future<QuarantineRecheckResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertTrue(results.stream().allMatch(result -> result.outcome() == QuarantineRecheckOutcome.CONFIRMED_ORPHAN));
        QuarantinedStorageObject entry = quarantineRepository.findByObjectKey(key).orElseThrow();
        assertEquals(1, rowsFor(key));
        assertTrue(results.stream().anyMatch(result -> result.checkedAt().equals(entry.confirmedAt())),
                "confirmed_at registra a primeira confirmação efetivada");
    }

    @Test
    @DisplayName("A tabela impõe object_key único e estados válidos")
    void schemaConstraintsAreEnforced() {
        String key = randomKey(UUID.randomUUID());
        observe(key);

        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "INSERT INTO storage_object_quarantine (object_key, first_observed_at, last_observed_at) VALUES (?, now(), now())", key));
        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "UPDATE storage_object_quarantine SET status = 'CONFIRMED_ORPHAN' WHERE object_key = ?", key));
        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "UPDATE storage_object_quarantine SET status = 'DELETED' WHERE object_key = ?", key));
        assertEquals(1, rowsFor(key));
    }

    private QuarantineRecheckResult recheck(String key, Instant now) {
        return new RecheckQuarantinedStorageObjectUseCase(quarantineRepository, new FixedStorageQuarantineGracePolicy(GRACE))
                .recheck(key, now);
    }

    private void observe(String key) {
        quarantineRepository.recordObservations(List.of(new OrphanCandidate(key, 10, null, T0)));
    }

    private int rowsFor(String key) {
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM storage_object_quarantine WHERE object_key = ?", Integer.class, key);
        return rows == null ? 0 : rows;
    }

    private static String randomKey(UUID reviewId) {
        return "reviews/" + reviewId + "/" + UUID.randomUUID() + "/image.jpg";
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // Somente metadados: nenhum objeto é gravado ou removido no storage
    private ReviewMedia saveMedia(UUID reviewId, UUID userId) {
        UUID mediaId = UUID.randomUUID();
        return saveMedia(reviewId, userId, mediaId, "reviews/" + reviewId + "/" + mediaId + "/image.jpg");
    }

    private ReviewMedia saveMedia(UUID reviewId, UUID userId, UUID mediaId, String key) {
        return reviewMediaRepository.save(new ReviewMedia(
                mediaId, reviewId, userId, key, ReviewMediaType.IMAGE, "image/jpeg", 1024, 10, 10));
    }

    private User createUser() {
        String unique = "qrt_" + UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.save(new User(null, unique + "@rewit.test", "Password123!", AuthProvider.LOCAL, null));
        profileRepository.save(new Profile(UUID.randomUUID(), user.getId(), "u_" + unique, "User " + unique, "Bio", null));
        return user;
    }

    private ReviewDetailView createReview(User user) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Place place = placeRepository.save(new Place(
                null, "Restaurante Quarentena " + unique, "restaurante-qrt-" + unique,
                "RESTAURANTE", "Descrição", "Rua Teste, 100", "100", "Centro", "São Paulo", "SP", "BR",
                -23.5505, -46.6333, 50, "USER", false, null, "ACTIVE"
        ));
        RateableTarget target = targetRepository.save(new RateableTarget(UUID.randomUUID(), TargetType.PLACE));
        return reviewService.createReview(new CreateReviewCommand(
                user.getId(), place.getId(), "Review para teste de quarentena de storage", false, "PUBLIC",
                null, null, null,
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("4.0"), "Bom"))
        ));
    }
}
