package com.rewit.application.usecase;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.ReviewDetailView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.report.ReportDtos.AdminReportView;
import com.rewit.application.dto.report.ReportDtos.CreateReportCommand;
import com.rewit.application.dto.report.ReportDtos.ModerateReviewCommand;
import com.rewit.application.dto.report.ReportDtos.ModerateReviewResult;
import com.rewit.application.dto.report.ReportDtos.QueryAdminReportsFilter;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.ReportService;
import com.rewit.application.service.ReviewService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.ModerationAction;
import com.rewit.domain.enums.ModerationDecision;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.Role;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.persistence.entity.ModerationAuditLogJpaEntity;
import com.rewit.infrastructure.persistence.entity.ReportJpaEntity;
import com.rewit.infrastructure.persistence.entity.ReviewJpaEntity;
import com.rewit.infrastructure.persistence.repository.ModerationAuditLogJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReportJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração: Moderação Administrativa no PostgreSQL Real (Step 26.2)")
class ModerateReviewIntegrationTest {

    @Autowired
    private ModerateReviewUseCase moderateReviewUseCase;

    @Autowired
    private QueryAdminReportsUseCase queryAdminReportsUseCase;

    @Autowired
    private DeleteReviewUseCase deleteReviewUseCase;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    @Autowired
    private ReviewJpaRepository reviewJpaRepository;

    @Autowired
    private ReportJpaRepository reportJpaRepository;

    @Autowired
    private ModerationAuditLogJpaRepository moderationAuditLogJpaRepository;

    private User createActiveUser(Role role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User(null, "user-" + suffix + "@rewit.com", "hash123", AuthProvider.LOCAL, null);
        if (role != null) {
            user.changeRole(role);
        }
        return userRepository.save(user);
    }

    private RateableTarget createRateableTarget(TargetType type) {
        RateableTarget target = new RateableTarget(UUID.randomUUID(), type);
        return rateableTargetRepository.save(target);
    }

    @Test
    @DisplayName("Cenário 1: REMOVE_REVIEW ponta a ponta com resolução de reports em lote, audit log e recálculo de stats no PostgreSQL")
    void shouldExecuteRemoveReviewEndToEndInPostgres() {
        User author = createActiveUser(Role.USER);
        User reporter1 = createActiveUser(Role.USER);
        User reporter2 = createActiveUser(Role.USER);
        User moderator = createActiveUser(Role.MODERATOR);

        RateableTarget target = createRateableTarget(TargetType.PLACE);

        ReviewDetailView createdReview = reviewService.createReview(new CreateReviewCommand(
                author.getId(),
                null,
                "Avaliação com conteúdo ofensivo a ser removida",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("5.0"), "Ótimo"))
        ));

        // 2 denúncias de usuários distintos
        reportService.createReport(new CreateReportCommand(reporter1.getId(), createdReview.id(), ReportReason.INAPPROPRIATE_CONTENT, "Texto ofensivo"));
        reportService.createReport(new CreateReportCommand(reporter2.getId(), createdReview.id(), ReportReason.SPAM, "Conteúdo spam"));

        // Sanity check de reports PENDING no banco
        assertEquals(2, reportJpaRepository.countPendingByReviewId(createdReview.id()));
        assertEquals(1, reviewService.getTargetStats(target.getId()).reviewsCount());

        // Execução da moderação: REMOVE_REVIEW
        ModerateReviewCommand command = new ModerateReviewCommand(
                createdReview.id(),
                moderator.getId(),
                ModerationAction.REMOVE_REVIEW,
                "HATE_SPEECH",
                "Violação flagrante dos termos de serviço da comunidade.",
                Instant.now()
        );

        ModerateReviewResult result = moderateReviewUseCase.execute(command);

        assertNotNull(result);
        assertEquals(ModerationDecision.ACCEPTED, result.auditLog().getDecision());
        assertEquals(2, result.auditLog().getReportsAffectedCount());

        // Verificação no PostgreSQL: Review deve estar REMOVED
        ReviewJpaEntity dbReview = reviewJpaRepository.findById(createdReview.id()).orElseThrow();
        assertEquals("REMOVED", dbReview.getStatus());

        // Verificação no PostgreSQL: Todos os reports pendentes agora são ACCEPTED
        List<ReportJpaEntity> dbReports = reportJpaRepository.findPendingByReviewId(createdReview.id());
        assertEquals(0, dbReports.size(), "Não devem sobrar denúncias pendentes");

        ReportJpaEntity report1 = reportJpaRepository.findByReviewIdAndReporterUserId(createdReview.id(), reporter1.getId()).orElseThrow();
        ReportJpaEntity report2 = reportJpaRepository.findByReviewIdAndReporterUserId(createdReview.id(), reporter2.getId()).orElseThrow();
        assertEquals("ACCEPTED", report1.getStatus());
        assertEquals("ACCEPTED", report2.getStatus());

        // Verificação no PostgreSQL: ModerationAuditLog persistido
        List<ModerationAuditLogJpaEntity> auditLogs = moderationAuditLogJpaRepository.findByReviewIdOrderByCreatedAtDesc(createdReview.id());
        assertEquals(1, auditLogs.size());
        assertEquals("REMOVE_REVIEW", auditLogs.get(0).getAction());
        assertEquals("ACCEPTED", auditLogs.get(0).getDecision());
        assertEquals("HATE_SPEECH", auditLogs.get(0).getReasonCode());
        assertEquals(2, auditLogs.get(0).getReportsAffectedCount());

        // Verificação no PostgreSQL: Stats do target recalculadas para 0 reviews ativas
        assertEquals(0, reviewService.getTargetStats(target.getId()).reviewsCount());
        assertEquals(new BigDecimal("0.00"), reviewService.getTargetStats(target.getId()).averageRating());
    }

    @Test
    @DisplayName("Cenário 2: RESTORE_REVIEW ponta a ponta com rejeição de reports em lote e retorno a ACTIVE no PostgreSQL")
    void shouldExecuteRestoreReviewEndToEndInPostgres() {
        User author = createActiveUser(Role.USER);
        User reporter1 = createActiveUser(Role.USER);
        User reporter2 = createActiveUser(Role.USER);
        User reporter3 = createActiveUser(Role.USER);
        User moderator = createActiveUser(Role.ADMIN);

        RateableTarget target = createRateableTarget(TargetType.PRODUCT);

        ReviewDetailView createdReview = reviewService.createReview(new CreateReviewCommand(
                author.getId(),
                null,
                "Avaliação legítima que sofreu quarentena indevida",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("4.0"), "Bom"))
        ));

        // 3 denúncias acionam quarentena automática (UNDER_REVIEW)
        reportService.createReport(new CreateReportCommand(reporter1.getId(), createdReview.id(), ReportReason.SPAM, "1"));
        reportService.createReport(new CreateReportCommand(reporter2.getId(), createdReview.id(), ReportReason.SPAM, "2"));
        reportService.createReport(new CreateReportCommand(reporter3.getId(), createdReview.id(), ReportReason.SPAM, "3"));

        ReviewJpaEntity underReviewDb = reviewJpaRepository.findById(createdReview.id()).orElseThrow();
        assertEquals("UNDER_REVIEW", underReviewDb.getStatus());

        // Execução da moderação: RESTORE_REVIEW
        ModerateReviewCommand command = new ModerateReviewCommand(
                createdReview.id(),
                moderator.getId(),
                ModerationAction.RESTORE_REVIEW,
                "FALSE_POSITIVE",
                "Denúncias improcedentes coordenadas; avaliação restabelecida.",
                Instant.now()
        );

        ModerateReviewResult result = moderateReviewUseCase.execute(command);

        assertNotNull(result);
        assertEquals(ModerationDecision.REJECTED, result.auditLog().getDecision());
        assertEquals(3, result.auditLog().getReportsAffectedCount());

        // Verificação no PostgreSQL: Review volta para ACTIVE
        ReviewJpaEntity restoredDb = reviewJpaRepository.findById(createdReview.id()).orElseThrow();
        assertEquals("ACTIVE", restoredDb.getStatus());

        // Verificação no PostgreSQL: Todos os reports pendentes agora são REJECTED
        ReportJpaEntity r1 = reportJpaRepository.findByReviewIdAndReporterUserId(createdReview.id(), reporter1.getId()).orElseThrow();
        ReportJpaEntity r2 = reportJpaRepository.findByReviewIdAndReporterUserId(createdReview.id(), reporter2.getId()).orElseThrow();
        ReportJpaEntity r3 = reportJpaRepository.findByReviewIdAndReporterUserId(createdReview.id(), reporter3.getId()).orElseThrow();
        assertEquals("REJECTED", r1.getStatus());
        assertEquals("REJECTED", r2.getStatus());
        assertEquals("REJECTED", r3.getStatus());

        // Verificação no PostgreSQL: Stats do target voltam a computar a avaliação ativa
        assertEquals(1, reviewService.getTargetStats(target.getId()).reviewsCount());
        assertEquals(new BigDecimal("4.00"), reviewService.getTargetStats(target.getId()).averageRating());
    }

    @Test
    @DisplayName("Cenário 3: Consulta administrativa QueryAdminReportsUseCase com filtros reais no PostgreSQL")
    void shouldQueryAdminReportsWithFiltersInPostgres() {
        User author = createActiveUser(Role.USER);
        User reporter = createActiveUser(Role.USER);
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        ReviewDetailView review = reviewService.createReview(new CreateReviewCommand(
                author.getId(),
                null,
                "Avaliação anônima para teste de consulta administrativa",
                true, // anônima publicamente
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("3.0"), "Regular"))
        ));

        reportService.createReport(new CreateReportCommand(reporter.getId(), review.id(), ReportReason.INAPPROPRIATE_CONTENT, "Conteúdo impróprio"));

        QueryAdminReportsFilter filter = new QueryAdminReportsFilter(
                0,
                20,
                ReportStatus.PENDING,
                ReportReason.INAPPROPRIATE_CONTENT,
                review.id(),
                reporter.getId(),
                "asc"
        );

        PageResult<AdminReportView> result = queryAdminReportsUseCase.execute(filter);

        assertFalse(result.content().isEmpty());
        AdminReportView item = result.content().get(0);
        assertEquals(review.id(), item.reviewId());
        assertEquals(reporter.getId(), item.reporterUserId());
        // O autor interno da review deve ser identificado mesmo sendo anônima publicamente
        assertEquals(author.getId(), item.reviewAuthorUserId());
        assertEquals(ReviewStatus.ACTIVE, item.reviewStatus());
    }

    @Test
    @DisplayName("Cenário 4: Concorrência - Moderação administrativa concorrente com exclusão do autor é serializada pelo lock pessimista")
    void shouldSerializeConcurrentModerationAndDeleteUnderPessimisticLock() throws Exception {
        User author = createActiveUser(Role.USER);
        User moderator = createActiveUser(Role.MODERATOR);
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        ReviewDetailView review = reviewService.createReview(new CreateReviewCommand(
                author.getId(),
                null,
                "Review sob disputa concorrente entre moderador e autor",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("5.0"), "Exclusivo"))
        ));

        assertEquals(1, reviewService.getTargetStats(target.getId()).reviewsCount());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);

        // Tarefa 1: Moderador removendo a review
        Callable<String> moderateTask = () -> {
            startGate.await();
            try {
                moderateReviewUseCase.execute(new ModerateReviewCommand(
                        review.id(),
                        moderator.getId(),
                        ModerationAction.REMOVE_REVIEW,
                        "COMMUNITY_SAFETY",
                        "Remoção preventiva por moderação concorrente.",
                        Instant.now()
                ));
                return "MODERATE_SUCCESS";
            } catch (BusinessException ex) {
                return ex.getErrorCode();
            } finally {
                doneGate.countDown();
            }
        };

        // Tarefa 2: Próprio autor tentando deletar a review ao mesmo tempo
        Callable<String> deleteTask = () -> {
            startGate.await();
            try {
                deleteReviewUseCase.execute(review.id(), author.getId(), Instant.now());
                return "DELETE_SUCCESS";
            } catch (BusinessException ex) {
                return ex.getErrorCode();
            } finally {
                doneGate.countDown();
            }
        };

        Future<String> future1 = executor.submit(moderateTask);
        Future<String> future2 = executor.submit(deleteTask);

        startGate.countDown();
        boolean completed = doneGate.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "As operações concorrentes devem concluir dentro do tempo limite");

        String res1 = future1.get();
        String res2 = future2.get();

        // Uma das chamadas adquire o lock primeiro e tem sucesso; a outra encontra a review já REMOVED
        boolean moderateWon = "MODERATE_SUCCESS".equals(res1) && "REVIEW_ALREADY_REMOVED".equals(res2);
        boolean deleteWon = "DELETE_SUCCESS".equals(res2) && "REVIEW_ALREADY_REMOVED".equals(res1);

        assertTrue(moderateWon || deleteWon,
                "Exatamente uma das operações concorrentes deve vencer e a outra ser rejeitada com REVIEW_ALREADY_REMOVED. Obtido: res1=" + res1 + ", res2=" + res2);

        // Estado final no PostgreSQL deve ser consistente: review REMOVED e stats recomputadas para 0
        ReviewJpaEntity finalReview = reviewJpaRepository.findById(review.id()).orElseThrow();
        assertEquals("REMOVED", finalReview.getStatus());
        assertEquals(0, reviewService.getTargetStats(target.getId()).reviewsCount());

        executor.shutdown();
    }
}
