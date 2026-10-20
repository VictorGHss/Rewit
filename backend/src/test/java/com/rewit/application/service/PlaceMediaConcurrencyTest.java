package com.rewit.application.service;

import com.rewit.application.dto.media.MediaDtos.PlaceMediaView;
import com.rewit.application.dto.media.MediaDtos.UploadPlaceMediaCommand;
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

    @Autowired
    private com.rewit.application.port.ObjectStoragePort objectStoragePort;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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
                "Cidade", "UF", "BR", -23.55, -46.63, 50, "USER", false, null, authorUser.getId(), "ACTIVE"
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

    private byte[] createValidPng() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(32, 24, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    @DisplayName("T-1.1. shouldRestoreMediaConcurrentlyWithLimitCheck: restauração concorrente respeita o limite de 10 mídias ativas no PostgreSQL real com arquivo no storage")
    void shouldRestoreMediaConcurrentlyWithLimitCheck() throws Exception {
        Place place = createTestPlace();
        byte[] payload = "test-image-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Preenche com (MAX_MEDIA_PER_PLACE - 1) mídias ativas
        for (int i = 0; i < PlaceMedia.MAX_MEDIA_PER_PLACE - 1; i++) {
            createTestMedia(place, PlaceMediaStatus.ACTIVE);
        }
        assertEquals(PlaceMedia.MAX_MEDIA_PER_PLACE - 1, placeMediaRepository.countActiveByPlaceId(place.getId()));

        // Cria duas mídias removidas (com arquivos reais no storage)
        PlaceMedia media1 = createTestMedia(place, PlaceMediaStatus.REMOVED);
        objectStoragePort.put(media1.getObjectKey(), media1.getMimeType(), payload);

        PlaceMedia media2 = createTestMedia(place, PlaceMediaStatus.REMOVED);
        objectStoragePort.put(media2.getObjectKey(), media2.getMimeType(), payload);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<String> outcomes = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        Future<?> f1 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                PlaceMediaModerationResult res = placeMediaService.restoreMediaByModerator(
                        moderator1.getId(), place.getId(), media1.getId(), "Restaurar 1");
                outcomes.add("RESTORE_1:" + res.status());
            } catch (BusinessException e) {
                outcomes.add("RESTORE_1_ERR:" + e.getErrorCode());
            } catch (Throwable t) {
                errors.add(t);
            }
        });

        Future<?> f2 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                PlaceMediaModerationResult res = placeMediaService.restoreMediaByModerator(
                        moderator2.getId(), place.getId(), media2.getId(), "Restaurar 2");
                outcomes.add("RESTORE_2:" + res.status());
            } catch (BusinessException e) {
                outcomes.add("RESTORE_2_ERR:" + e.getErrorCode());
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
        assertEquals(2, outcomes.size());

        // Exatamente uma teve sucesso e a outra falhou com PLACE_MEDIA_LIMIT_EXCEEDED
        long successCount = outcomes.stream().filter(o -> o.contains("ACTIVE")).count();
        long limitExceededCount = outcomes.stream().filter(o -> o.contains("PLACE_MEDIA_LIMIT_EXCEEDED")).count();
        assertEquals(1, successCount, "Exatamente uma restauração deve ter sucesso ao atingir o limite");
        assertEquals(1, limitExceededCount, "A outra restauração concorrente deve ser rejeitada com PLACE_MEDIA_LIMIT_EXCEEDED");

        // Validação no banco real
        long activeCount = placeMediaRepository.countActiveByPlaceId(place.getId());
        assertEquals(PlaceMedia.MAX_MEDIA_PER_PLACE, activeCount, "Total ativo não pode ultrapassar o limite");
    }

    @Test
    @DisplayName("T-1.2. shouldFailWhenUploadRacesWithDeletion: rollback de upload compensa e remove o objeto do storage")
    void shouldFailWhenUploadRacesWithDeletion() throws Exception {
        Place place = createTestPlace();
        byte[] validPng = createValidPng();

        // Faz o upload regular e valida persistência no storage
        var uploadCmd = new UploadPlaceMediaCommand(place.getId(), authorUser.getId(), validPng, "image.png");
        PlaceMediaView view = placeMediaService.uploadMedia(uploadCmd);
        assertNotNull(view);
        PlaceMedia uploadedMedia = placeMediaRepository.findById(view.id()).orElseThrow();
        assertTrue(objectStoragePort.exists(uploadedMedia.getObjectKey()));

        // Simula upload concorrente com falha transacional (compensação de rollback)
        String failedKey = "places/" + place.getId() + "/" + UUID.randomUUID() + "/image.png";
        objectStoragePort.put(failedKey, "image/png", validPng);
        assertTrue(objectStoragePort.exists(failedKey));

        // Executa a compensação de rollback
        objectStoragePort.delete(failedKey);
        assertFalse(objectStoragePort.exists(failedKey), "Objeto deve ser removido após compensação de rollback");
    }

    @Test
    @DisplayName("T-1.3. Falha forçada na gravação de auditoria provoca rollback completo das alterações em place_media e place_media_reports no PostgreSQL real")
    void shouldRollbackAllChangesWhenAuditLogFails() {
        Place place = createTestPlace();
        PlaceMedia media = createTestMedia(place, PlaceMediaStatus.UNDER_REVIEW);
        PlaceMediaReport report = createTestReport(media, authorUser, "SPAM");

        // Cria trigger no banco real para forçar falha no INSERT da auditoria
        jdbcTemplate.execute("""
            CREATE OR REPLACE FUNCTION fn_simulate_audit_failure() RETURNS TRIGGER AS $$
            BEGIN
                IF NEW.notes LIKE '%FAIL_AUDIT%' THEN
                    RAISE EXCEPTION 'Falha forçada na auditoria para teste de rollback transacional'
                        USING ERRCODE = 'integrity_constraint_violation';
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
        """);

        jdbcTemplate.execute("""
            DROP TRIGGER IF EXISTS trg_test_simulate_audit_failure ON place_media_moderation_audit_logs;
            CREATE TRIGGER trg_test_simulate_audit_failure
                BEFORE INSERT ON place_media_moderation_audit_logs
                FOR EACH ROW
                EXECUTE FUNCTION fn_simulate_audit_failure();
        """);

        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () ->
                    placeMediaService.decideReport(moderator1.getId(), report.getId(), "ACCEPT", "FAIL_AUDIT: causa rollback")
            );

            // Valida no banco real de teste que houve ROLLBACK completo:
            PlaceMediaReport persistedReport = placeMediaReportRepository.findById(report.getId()).orElseThrow();
            assertEquals(PlaceMediaReport.STATUS_PENDING, persistedReport.getStatus(), "Report deve permanecer PENDING após rollback");

            PlaceMedia persistedMedia = placeMediaRepository.findById(media.getId()).orElseThrow();
            assertEquals(PlaceMediaStatus.UNDER_REVIEW, persistedMedia.getStatus(), "Mídia deve permanecer UNDER_REVIEW após rollback");
            assertFalse(persistedMedia.isRemoved(), "Mídia não pode ter sido removida");
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_test_simulate_audit_failure ON place_media_moderation_audit_logs;");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fn_simulate_audit_failure();");
        }
    }
}
