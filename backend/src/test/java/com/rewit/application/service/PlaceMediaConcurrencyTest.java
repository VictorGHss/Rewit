package com.rewit.application.service;

import com.rewit.application.dto.media.PlaceMediaModerationDtos.PlaceMediaModerationResult;
import com.rewit.application.dto.media.PlaceMediaModerationDtos.PlaceMediaReportView;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste de concorrência transacional real contra PostgreSQL isolado (H-2).
 * Verifica que locks pessimistas hierárquicos e sincronização de persistência (refresh L1)
 * garantem que decisões concorrentes sobre denúncias e mídias nunca gerem lost updates ou estados contraditórios.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Concorrência Transacional Real em PostgreSQL: PlaceMediaService (H-2)")
class PlaceMediaConcurrencyTest {

    @Autowired
    private PlaceMediaService placeMediaService;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private PlaceMediaRepository placeMediaRepository;

    @Autowired
    private PlaceMediaReportRepository placeMediaReportRepository;

    @Autowired
    private PlaceMediaModerationAuditLogRepository auditLogRepository;

    @Autowired
    private UserRepository userRepository;

    private User moderator1;
    private User moderator2;
    private User authorUser;

    @BeforeEach
    void setUp() {
        moderator1 = createTestUser("mod1." + UUID.randomUUID() + "@rewit.com", Role.MODERATOR);
        moderator2 = createTestUser("mod2." + UUID.randomUUID() + "@rewit.com", Role.MODERATOR);
        authorUser = createTestUser("author." + UUID.randomUUID() + "@rewit.com", Role.USER);
    }

    private User createTestUser(String email, Role role) {
        User user = User.rehydrate(
                UUID.randomUUID(), email, "hash123", AuthProvider.LOCAL, null,
                com.rewit.domain.enums.AccountStatus.ACTIVE, true, role, null, Instant.now(), Instant.now()
        );
        return userRepository.save(user);
    }

    private Place createTestPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return placeRepository.save(new Place(
                null, "Local Concorrência " + suffix, "local-conc-" + suffix,
                "RESTAURANTE", "Descrição concorrência", "Rua Concorrência, 100", "100", "Bairro",
                "Cidade", "UF", "BR", -23.55, -46.63, 50, "USER", false, null, "ACTIVE"
        ));
    }

    private PlaceMedia createTestMedia(Place place, PlaceMediaStatus status) {
        PlaceMedia media = new PlaceMedia(
                UUID.randomUUID(), place.getId(), authorUser.getId(),
                "places/" + place.getId() + "/" + UUID.randomUUID() + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 1024L, 800, 600,
                status, false, null, null, Instant.now(), Instant.now()
        );
        return placeMediaRepository.save(media);
    }

    private PlaceMediaReport createTestReport(PlaceMedia media, User reporter, String reason) {
        PlaceMediaReport report = new PlaceMediaReport(
                UUID.randomUUID(), media.getId(), reporter.getId(), reason, "Detalhes da denúncia"
        );
        return placeMediaReportRepository.save(report);
    }

    @Test
    @DisplayName("H-2.1. Execução repetida de ACCEPT vs REJECT concorrentes na mesma denúncia: exatamente 1 vence e o outro recebe REPORT_ALREADY_DECIDED")
    void shouldHandleConcurrentAcceptVsRejectDeterministicallyAcrossMultipleRuns() throws Exception {
        int totalIterations = 5;

        for (int i = 0; i < totalIterations; i++) {
            Place place = createTestPlace();
            PlaceMedia media = createTestMedia(place, PlaceMediaStatus.UNDER_REVIEW);
            PlaceMediaReport report = createTestReport(media, authorUser, "SPAM");

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);

            List<String> successes = Collections.synchronizedList(new ArrayList<>());
            List<String> conflicts = Collections.synchronizedList(new ArrayList<>());
            List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

            Future<?> f1 = executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    PlaceMediaReportView res = placeMediaService.decideReport(moderator1.getId(), report.getId(), "ACCEPT", "Aprovado mod 1");
                    successes.add("ACCEPT:" + res.status());
                } catch (BusinessException e) {
                    if ("REPORT_ALREADY_DECIDED".equals(e.getErrorCode())) {
                        conflicts.add(e.getErrorCode());
                    } else {
                        unexpectedErrors.add(e);
                    }
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                }
            });

            Future<?> f2 = executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    PlaceMediaReportView res = placeMediaService.decideReport(moderator2.getId(), report.getId(), "REJECT", "Rejeitado mod 2");
                    successes.add("REJECT:" + res.status());
                } catch (BusinessException e) {
                    if ("REPORT_ALREADY_DECIDED".equals(e.getErrorCode())) {
                        conflicts.add(e.getErrorCode());
                    } else {
                        unexpectedErrors.add(e);
                    }
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                }
            });

            assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "Threads deveriam estar prontas");
            startLatch.countDown();

            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(unexpectedErrors.isEmpty(), "Não deve haver erros inesperados: " + unexpectedErrors);
            assertEquals(1, successes.size(), "Iteração " + i + ": Exatamente uma decisão deve ter sucesso");
            assertEquals(1, conflicts.size(), "Iteração " + i + ": A segunda decisão deve receber conflito");
            assertEquals("REPORT_ALREADY_DECIDED", conflicts.get(0));

            // Valida o estado final real no banco de dados
            PlaceMediaReport finalReport = placeMediaReportRepository.findById(report.getId()).orElseThrow();
            assertFalse(finalReport.isPending(), "Denúncia não pode permanecer PENDING");

            PlaceMedia finalMedia = placeMediaRepository.findById(media.getId()).orElseThrow();
            if (successes.get(0).startsWith("ACCEPT")) {
                assertEquals(PlaceMediaReport.STATUS_ACCEPTED, finalReport.getStatus());
                assertEquals(PlaceMediaStatus.REMOVED, finalMedia.getStatus());
                assertEquals(com.rewit.domain.enums.RemovalSource.MODERATOR, finalMedia.getRemovalSource());
            } else {
                assertEquals(PlaceMediaReport.STATUS_REJECTED, finalReport.getStatus());
                assertEquals(PlaceMediaStatus.UNDER_REVIEW, finalMedia.getStatus());
            }
        }
    }

    @Test
    @DisplayName("H-2.2. Decisões concorrentes sobre duas denúncias diferentes da mesma fotografia")
    void shouldHandleConcurrentDecisionsOnDifferentReportsOfSameMedia() throws Exception {
        Place place = createTestPlace();
        PlaceMedia media = createTestMedia(place, PlaceMediaStatus.UNDER_REVIEW);

        User reporter2 = createTestUser("rep2." + UUID.randomUUID() + "@rewit.com", Role.USER);
        PlaceMediaReport report1 = createTestReport(media, authorUser, "SPAM");
        PlaceMediaReport report2 = createTestReport(media, reporter2, "OFENSIVO");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<String> outcomes = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        Future<?> f1 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                PlaceMediaReportView view = placeMediaService.decideReport(moderator1.getId(), report1.getId(), "ACCEPT", "Aceito 1");
                outcomes.add("F1_ACCEPT:" + view.status());
            } catch (BusinessException e) {
                outcomes.add("F1_ERROR:" + e.getErrorCode());
            } catch (Throwable t) {
                errors.add(t);
            }
        });

        Future<?> f2 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                PlaceMediaReportView view = placeMediaService.decideReport(moderator2.getId(), report2.getId(), "REJECT", "Rejeito 2");
                outcomes.add("F2_REJECT:" + view.status());
            } catch (BusinessException e) {
                outcomes.add("F2_ERROR:" + e.getErrorCode());
            } catch (Throwable t) {
                errors.add(t);
            }
        });

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
        startLatch.countDown();

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(errors.isEmpty(), "Nenhum erro inesperado deve ocorrer: " + errors);
        assertEquals(2, outcomes.size(), "Ambas as operações devem ser processadas");

        // Estado final no banco: a mídia não pode ficar em estado inconsistente
        PlaceMedia finalMedia = placeMediaRepository.findById(media.getId()).orElseThrow();
        PlaceMediaReport r1 = placeMediaReportRepository.findById(report1.getId()).orElseThrow();
        PlaceMediaReport r2 = placeMediaReportRepository.findById(report2.getId()).orElseThrow();

        // Se ACCEPT venceu primeiro, ambas as denúncias ficam ACCEPTED e a mídia removida
        if (finalMedia.getStatus() == PlaceMediaStatus.REMOVED) {
            assertEquals(PlaceMediaReport.STATUS_ACCEPTED, r1.getStatus());
        }
    }

    @Test
    @DisplayName("H-2.3. Remoção concorrente contra restauração da mesma fotografia")
    void shouldHandleConcurrentRemovalAgainstRestoration() throws Exception {
        Place place = createTestPlace();
        // Mídia inicialmente ativa
        PlaceMedia media = createTestMedia(place, PlaceMediaStatus.ACTIVE);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<String> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        Future<?> f1 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                PlaceMediaModerationResult res = placeMediaService.removeMediaByModerator(
                        moderator1.getId(), place.getId(), media.getId(), "SPAM", "Remover concorrente");
                results.add("REMOVE:" + res.status());
            } catch (BusinessException e) {
                results.add("REMOVE_ERR:" + e.getErrorCode());
            } catch (Throwable t) {
                errors.add(t);
            }
        });

        Future<?> f2 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                // Tenta restaurar a mesma mídia
                PlaceMediaModerationResult res = placeMediaService.restoreMediaByModerator(
                        moderator2.getId(), place.getId(), media.getId(), "Restaurar concorrente");
                results.add("RESTORE:" + res.status());
            } catch (BusinessException e) {
                results.add("RESTORE_ERR:" + e.getErrorCode());
            } catch (Throwable t) {
                errors.add(t);
            }
        });

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
        startLatch.countDown();

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(errors.isEmpty(), "Sem erros inesperados: " + errors);
        assertEquals(2, results.size());

        // O estado final do banco deve ser determinístico: ou ACTIVE ou REMOVED_BY_MODERATOR, nunca corrompido
        PlaceMedia finalMedia = placeMediaRepository.findById(media.getId()).orElseThrow();
        assertTrue(finalMedia.getStatus() == PlaceMediaStatus.ACTIVE || finalMedia.getStatus() == PlaceMediaStatus.REMOVED,
                "Estado final deve ser válido e coerente");
    }

    @Test
    @DisplayName("H-2.4. Denúncia concorrente de usuário contra deferimento de denúncia (ACCEPT) pelo moderador")
    void shouldHandleConcurrentReportAgainstModeratorDecision() throws Exception {
        Place place = createTestPlace();
        PlaceMedia media = createTestMedia(place, PlaceMediaStatus.ACTIVE);

        User reporter1 = createTestUser("rep1." + UUID.randomUUID() + "@rewit.com", Role.USER);
        User reporter2 = createTestUser("rep2." + UUID.randomUUID() + "@rewit.com", Role.USER);
        PlaceMediaReport report1 = createTestReport(media, reporter1, "CONTEUDO_INADEQUADO");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<String> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        Future<?> f1 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                PlaceMediaReportView view = placeMediaService.decideReport(moderator1.getId(), report1.getId(), "ACCEPT", "Deferido");
                results.add("DECIDE_ACCEPT:" + view.status());
            } catch (BusinessException e) {
                results.add("DECIDE_ERR:" + e.getErrorCode());
            } catch (Throwable t) {
                errors.add(t);
            }
        });

        Future<?> f2 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                placeMediaService.reportMedia(reporter2.getId(), place.getId(), media.getId(), "SPAM", "Denúncia simultânea");
                results.add("REPORT_SUCCESS");
            } catch (BusinessException e) {
                results.add("REPORT_ERR:" + e.getErrorCode());
            } catch (Throwable t) {
                errors.add(t);
            }
        });

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
        startLatch.countDown();

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(errors.isEmpty(), "Sem erros inesperados: " + errors);
        assertEquals(2, results.size());

        // Se o moderador aceitou a denúncia, a mídia deve ter sido removida
        PlaceMedia finalMedia = placeMediaRepository.findById(media.getId()).orElseThrow();
        assertEquals(PlaceMediaStatus.REMOVED, finalMedia.getStatus());
    }
}
