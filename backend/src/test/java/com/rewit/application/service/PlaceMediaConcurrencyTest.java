package com.rewit.application.service;

import com.rewit.application.dto.media.PlaceMediaModerationDtos.PlaceMediaModerationResult;
import com.rewit.application.dto.media.PlaceMediaModerationDtos.PlaceMediaReportView;
import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.application.port.ObjectStoragePort;
import com.rewit.application.port.PlaceMediaModerationAuditLogRepository;
import com.rewit.application.port.PlaceMediaReportRepository;
import com.rewit.application.port.PlaceMediaRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.PlaceMediaStatus;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.enums.Role;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceMedia;
import com.rewit.domain.model.PlaceMediaReport;
import com.rewit.domain.model.User;
import com.rewit.application.port.RateLimiter;
import com.rewit.infrastructure.storage.ImageSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes de Concorrência: PlaceMediaService (A-5: Moderação Concorrente e Limites Ativos)")
class PlaceMediaConcurrencyTest {

    @Mock private PlaceRepository placeRepository;
    @Mock private PlaceMediaRepository placeMediaRepository;
    @Mock private AccountStatusPolicy accountStatusPolicy;
    @Mock private ObjectStoragePort objectStoragePort;
    @Mock private RateLimiter rateLimiter;
    @Mock private BusinessAccountRepository businessAccountRepository;
    @Mock private UserRepository userRepository;
    @Mock private PlaceMediaReportRepository placeMediaReportRepository;
    @Mock private PlaceMediaModerationAuditLogRepository auditLogRepository;

    private PlaceMediaService placeMediaService;

    private UUID placeId;
    private Place activePlace;
    private UUID moderatorId1;
    private UUID moderatorId2;
    private User moderatorUser1;
    private User moderatorUser2;

    @BeforeEach
    void setUp() {
        placeId = UUID.randomUUID();
        moderatorId1 = UUID.randomUUID();
        moderatorId2 = UUID.randomUUID();

        moderatorUser1 = User.rehydrate(
                moderatorId1, "mod1@rewit.com", "hash", AuthProvider.LOCAL, null,
                com.rewit.domain.enums.AccountStatus.ACTIVE, true, Role.MODERATOR, null, Instant.now(), Instant.now()
        );
        moderatorUser2 = User.rehydrate(
                moderatorId2, "mod2@rewit.com", "hash", AuthProvider.LOCAL, null,
                com.rewit.domain.enums.AccountStatus.ACTIVE, true, Role.MODERATOR, null, Instant.now(), Instant.now()
        );

        activePlace = new Place(
                placeId, "Local Concorrente", "local-concorrente", "GERAL", null,
                "Rua Teste, 100", null, null, null, null, "BR", null, null, 50,
                "USER", false, null, UUID.randomUUID(), "ACTIVE"
        );

        lenient().when(userRepository.findById(moderatorId1)).thenReturn(Optional.of(moderatorUser1));
        lenient().when(userRepository.findById(moderatorId2)).thenReturn(Optional.of(moderatorUser2));

        placeMediaService = new PlaceMediaService(
                placeRepository,
                placeMediaRepository,
                accountStatusPolicy,
                objectStoragePort,
                new ImageSanitizer(),
                rateLimiter,
                businessAccountRepository,
                userRepository,
                placeMediaReportRepository,
                auditLogRepository,
                null
        );
    }

    @Test
    @DisplayName("A-5. Decisões concorrentes (ACCEPT vs REJECT) na mesma denúncia: exatamente uma vence e a outra recebe 409 CONFLICT")
    void shouldHandleConcurrentModerationDecisionsDeterministically() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, UUID.randomUUID(), "places/" + placeId + "/" + mediaId + "/img.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.UNDER_REVIEW, false, null, null, Instant.now(), Instant.now()
        );

        // Estado thread-safe compartilhado que simula o lock pessimista e a transação de banco de dados
        ReentrantLock placeLock = new ReentrantLock();
        final PlaceMediaReport[] currentReport = new PlaceMediaReport[]{
                new PlaceMediaReport(reportId, mediaId, reporterId, "SPAM", "Propaganda")
        };

        lenient().when(placeMediaReportRepository.findById(reportId)).thenAnswer(inv -> {
            placeLock.lock();
            try {
                return Optional.of(currentReport[0]);
            } finally {
                placeLock.unlock();
            }
        });

        lenient().when(placeMediaRepository.findById(mediaId)).thenReturn(Optional.of(media));

        lenient().when(placeRepository.findByIdForUpdate(placeId)).thenAnswer(inv -> {
            placeLock.lock();
            return Optional.of(activePlace);
        });

        lenient().when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));

        lenient().when(placeMediaReportRepository.findByIdForUpdate(reportId)).thenAnswer(inv -> {
            return Optional.of(currentReport[0]);
        });

        lenient().when(placeMediaReportRepository.save(any(PlaceMediaReport.class))).thenAnswer(inv -> {
            PlaceMediaReport r = inv.getArgument(0);
            currentReport[0] = r;
            return r;
        });

        lenient().when(placeMediaRepository.save(any(PlaceMedia.class))).thenAnswer(inv -> inv.getArgument(0));

        // Executor com 2 threads concorrentes simulando dois moderadores decidindo simultaneamente
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<String> successes = Collections.synchronizedList(new ArrayList<>());
        List<String> conflicts = Collections.synchronizedList(new ArrayList<>());

        Future<?> f1 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                PlaceMediaReportView res = placeMediaService.decideReport(moderatorId1, reportId, "ACCEPT", "Aprovado");
                successes.add("Thread1_ACCEPT:" + res.status());
            } catch (BusinessException e) {
                conflicts.add(e.getErrorCode());
            } catch (Exception e) {
                fail("Exceção inesperada na Thread 1: " + e.getMessage());
            } finally {
                if (placeLock.isHeldByCurrentThread()) {
                    placeLock.unlock();
                }
            }
        });

        Future<?> f2 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                PlaceMediaReportView res = placeMediaService.decideReport(moderatorId2, reportId, "REJECT", "Infundado");
                successes.add("Thread2_REJECT:" + res.status());
            } catch (BusinessException e) {
                conflicts.add(e.getErrorCode());
            } catch (Exception e) {
                fail("Exceção inesperada na Thread 2: " + e.getMessage());
            } finally {
                if (placeLock.isHeldByCurrentThread()) {
                    placeLock.unlock();
                }
            }
        });

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
        startLatch.countDown();

        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Exatamente 1 moderador obteve sucesso, e o outro foi rejeitado com REPORT_ALREADY_DECIDED
        assertEquals(1, successes.size(), "Exatamente uma decisão deve ter sucesso");
        assertEquals(1, conflicts.size(), "A segunda decisão concorrente deve falhar com conflito");
        assertEquals("REPORT_ALREADY_DECIDED", conflicts.get(0));
    }

    @Test
    @DisplayName("A-2 e A-5. Restauração concorrente de fotos disputando a última vaga de 5 fotos ativas")
    void shouldPreventExceedingActiveMediaLimitUnderConcurrentRestores() throws Exception {
        UUID mediaId1 = UUID.randomUUID();
        UUID mediaId2 = UUID.randomUUID();

        PlaceMedia removedMedia1 = new PlaceMedia(
                mediaId1, placeId, UUID.randomUUID(), "places/" + placeId + "/" + mediaId1 + "/img1.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 1024L, 800, 600,
                PlaceMediaStatus.REMOVED, false, null, null, Instant.now(), Instant.now()
        );
        PlaceMedia removedMedia2 = new PlaceMedia(
                mediaId2, placeId, UUID.randomUUID(), "places/" + placeId + "/" + mediaId2 + "/img2.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 1024L, 800, 600,
                PlaceMediaStatus.REMOVED, false, null, null, Instant.now(), Instant.now()
        );

        ReentrantLock placeLock = new ReentrantLock();
        AtomicInteger activeCount = new AtomicInteger(4); // Começa com 4 fotos ativas (resta 1 vaga para o limite de 5)

        lenient().when(placeRepository.findByIdForUpdate(placeId)).thenAnswer(inv -> {
            placeLock.lock();
            return Optional.of(activePlace);
        });

        lenient().when(objectStoragePort.exists(any())).thenReturn(true);

        lenient().when(placeMediaRepository.findByIdForUpdate(mediaId1)).thenReturn(Optional.of(removedMedia1));
        lenient().when(placeMediaRepository.findByIdForUpdate(mediaId2)).thenReturn(Optional.of(removedMedia2));

        lenient().when(placeMediaRepository.countActiveByPlaceId(placeId)).thenAnswer(inv -> (long) activeCount.get());

        lenient().when(placeMediaRepository.save(any(PlaceMedia.class))).thenAnswer(inv -> {
            PlaceMedia m = inv.getArgument(0);
            if (m.isActive()) {
                activeCount.incrementAndGet();
            }
            return m;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<PlaceMediaModerationResult> successes = Collections.synchronizedList(new ArrayList<>());
        List<String> failures = Collections.synchronizedList(new ArrayList<>());

        Future<?> f1 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                PlaceMediaModerationResult res = placeMediaService.restoreMediaByModerator(moderatorId1, placeId, mediaId1, "Restaurar 1");
                successes.add(res);
            } catch (BusinessException e) {
                failures.add(e.getErrorCode());
            } catch (Exception e) {
                fail("Exceção inesperada na Thread 1: " + e.getMessage());
            } finally {
                if (placeLock.isHeldByCurrentThread()) {
                    placeLock.unlock();
                }
            }
        });

        Future<?> f2 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                PlaceMediaModerationResult res = placeMediaService.restoreMediaByModerator(moderatorId2, placeId, mediaId2, "Restaurar 2");
                successes.add(res);
            } catch (BusinessException e) {
                failures.add(e.getErrorCode());
            } catch (Exception e) {
                fail("Exceção inesperada na Thread 2: " + e.getMessage());
            } finally {
                if (placeLock.isHeldByCurrentThread()) {
                    placeLock.unlock();
                }
            }
        });

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
        startLatch.countDown();

        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Exatamente 1 restore venceu (atingindo o teto de 5) e o outro foi rejeitado com PLACE_MEDIA_LIMIT_EXCEEDED
        assertEquals(1, successes.size(), "Apenas uma foto pode ser restaurada para atingir o limite máximo de 5");
        assertEquals(1, failures.size(), "A segunda tentativa deve falhar com conflito de limite");
        assertEquals("PLACE_MEDIA_LIMIT_EXCEEDED", failures.get(0));
        assertEquals(5, activeCount.get(), "O número final de fotos ativas não pode exceder 5");
    }
}

