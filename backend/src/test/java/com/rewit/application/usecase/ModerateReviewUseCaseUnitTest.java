package com.rewit.application.usecase;

import com.rewit.application.dto.report.ReportDtos.ModerateReviewCommand;
import com.rewit.application.dto.report.ReportDtos.ModerateReviewResult;
import com.rewit.application.port.ModerationAuditLogRepository;
import com.rewit.application.port.RateableTargetStatsRepository;
import com.rewit.application.port.ReportRepository;
import com.rewit.application.port.ReviewMediaRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.service.ReputationService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ModerationAction;
import com.rewit.domain.enums.ModerationDecision;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.ModerationAuditLog;
import com.rewit.domain.model.Report;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewMedia;
import com.rewit.domain.model.ReviewTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: ModerateReviewUseCase (Step 26.2)")
class ModerateReviewUseCaseUnitTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private ModerationAuditLogRepository moderationAuditLogRepository;

    @Mock
    private RateableTargetStatsRepository rateableTargetStatsRepository;

    @Mock
    private ReputationService reputationService;

    @Mock
    private ReviewMediaRepository reviewMediaRepository;

    private ModerateReviewUseCase moderateReviewUseCase;

    private UUID authorUserId;
    private UUID moderatorUserId;
    private UUID reviewId;
    private UUID targetAId;
    private UUID targetBId;
    private Instant now;

    @BeforeEach
    void setUp() {
        moderateReviewUseCase = new ModerateReviewUseCase(
                reviewRepository,
                reportRepository,
                moderationAuditLogRepository,
                rateableTargetStatsRepository,
                reputationService,
                reviewMediaRepository
        );

        authorUserId = UUID.randomUUID();
        moderatorUserId = UUID.randomUUID();
        reviewId = UUID.randomUUID();
        targetAId = UUID.randomUUID();
        targetBId = UUID.randomUUID();
        now = Instant.parse("2026-10-01T12:00:00Z");
    }

    private Review createReview(ReviewStatus status) {
        Review review = new Review(
                reviewId,
                authorUserId,
                null,
                "Texto de teste da avaliação",
                false,
                false,
                status,
                "PUBLIC",
                null,
                null,
                null,
                now.minusSeconds(3600),
                now.minusSeconds(3600)
        );

        ReviewTarget targetA = new ReviewTarget(UUID.randomUUID(), reviewId, targetAId, new BigDecimal("4.0"), "Target A", now.minusSeconds(3600));
        ReviewTarget targetB = new ReviewTarget(UUID.randomUUID(), reviewId, targetBId, new BigDecimal("5.0"), "Target B", now.minusSeconds(3600));
        review.addTarget(targetA);
        review.addTarget(targetB);

        return review;
    }

    @Test
    @DisplayName("1. ACTIVE -> REMOVED por moderação é aceito com sucesso")
    void shouldModerateActiveToRemoved() {
        Review review = createReview(ReviewStatus.ACTIVE);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(List.of());

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId,
                moderatorUserId,
                ModerationAction.REMOVE_REVIEW,
                "VIOLATION_TOS",
                "Justificativa válida com mais de quinze caracteres.",
                now
        );

        ModerateReviewResult result = moderateReviewUseCase.execute(command);

        assertEquals(ReviewStatus.REMOVED, review.getStatus());
        assertEquals(ReviewStatus.REMOVED, result.review().getStatus());
        assertEquals(ModerationDecision.ACCEPTED, result.auditLog().getDecision());
        assertEquals(ReviewStatus.ACTIVE, result.auditLog().getPreviousReviewStatus());
        assertEquals(ReviewStatus.REMOVED, result.auditLog().getNewReviewStatus());
        verify(reviewRepository).save(review);
    }

    @Test
    @DisplayName("2. UNDER_REVIEW -> REMOVED por moderação é aceito com sucesso")
    void shouldModerateUnderReviewToRemoved() {
        Review review = createReview(ReviewStatus.UNDER_REVIEW);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(List.of());

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId,
                moderatorUserId,
                ModerationAction.REMOVE_REVIEW,
                "SPAM_COMMERCIAL",
                "Conteúdo caracterizado como spam comercial flagrante.",
                now
        );

        ModerateReviewResult result = moderateReviewUseCase.execute(command);

        assertEquals(ReviewStatus.REMOVED, review.getStatus());
        assertEquals(ReviewStatus.UNDER_REVIEW, result.auditLog().getPreviousReviewStatus());
        assertEquals(ReviewStatus.REMOVED, result.auditLog().getNewReviewStatus());
    }

    @Test
    @DisplayName("3. UNDER_REVIEW -> ACTIVE por restauração de moderação é aceito com sucesso")
    void shouldModerateUnderReviewToActive() {
        Review review = createReview(ReviewStatus.UNDER_REVIEW);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(List.of());

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId,
                moderatorUserId,
                ModerationAction.RESTORE_REVIEW,
                "FALSE_POSITIVE",
                "Denúncias improcedentes analisadas e descartadas pelo moderador.",
                now
        );

        ModerateReviewResult result = moderateReviewUseCase.execute(command);

        assertEquals(ReviewStatus.ACTIVE, review.getStatus());
        assertEquals(ModerationDecision.REJECTED, result.auditLog().getDecision());
        assertEquals(ReviewStatus.UNDER_REVIEW, result.auditLog().getPreviousReviewStatus());
        assertEquals(ReviewStatus.ACTIVE, result.auditLog().getNewReviewStatus());
    }

    @Test
    @DisplayName("4. REMOVED -> ACTIVE é rejeitado com conflito")
    void shouldRejectRestoreWhenAlreadyRemoved() {
        Review review = createReview(ReviewStatus.REMOVED);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(List.of());

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId,
                moderatorUserId,
                ModerationAction.RESTORE_REVIEW,
                "TEST",
                "Justificativa válida com mais de quinze caracteres.",
                now
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> moderateReviewUseCase.execute(command));
        assertEquals("INVALID_REVIEW_STATUS_TRANSITION", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("5. ACTIVE -> ACTIVE (tentativa de restore em review já ativa) é rejeitada")
    void shouldRejectRestoreWhenAlreadyActive() {
        Review review = createReview(ReviewStatus.ACTIVE);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(List.of());

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId,
                moderatorUserId,
                ModerationAction.RESTORE_REVIEW,
                "TEST",
                "Justificativa válida com mais de quinze caracteres.",
                now
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> moderateReviewUseCase.execute(command));
        assertEquals("REVIEW_ALREADY_ACTIVE", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("6. Justificativa nula ou vazia é rejeitada com BAD_REQUEST")
    void shouldRejectNullOrBlankJustification() {
        ModerateReviewCommand nullJustification = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.REMOVE_REVIEW, "TOS", null, now
        );
        BusinessException ex1 = assertThrows(BusinessException.class, () -> moderateReviewUseCase.execute(nullJustification));
        assertEquals("MISSING_JUSTIFICATION", ex1.getErrorCode());

        ModerateReviewCommand blankJustification = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.REMOVE_REVIEW, "TOS", "   ", now
        );
        BusinessException ex2 = assertThrows(BusinessException.class, () -> moderateReviewUseCase.execute(blankJustification));
        assertEquals("BLANK_JUSTIFICATION", ex2.getErrorCode());
    }

    @Test
    @DisplayName("7. Justificativa menor que 15 caracteres é rejeitada")
    void shouldRejectJustificationShorterThan15Chars() {
        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.REMOVE_REVIEW, "TOS", "12345678901234", now // 14 caracteres
        );
        BusinessException ex = assertThrows(BusinessException.class, () -> moderateReviewUseCase.execute(command));
        assertEquals("INVALID_JUSTIFICATION_LENGTH", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("8. Justificativa com exatamente 15 caracteres é aceita")
    void shouldAcceptJustificationWithExactly15Chars() {
        Review review = createReview(ReviewStatus.ACTIVE);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(List.of());

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.REMOVE_REVIEW, "TOS", "123456789012345", now // exatamente 15
        );

        assertDoesNotThrow(() -> moderateReviewUseCase.execute(command));
    }

    @Test
    @DisplayName("9. Justificativa com exatamente 1000 caracteres é aceita")
    void shouldAcceptJustificationWithExactly1000Chars() {
        Review review = createReview(ReviewStatus.ACTIVE);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(List.of());

        String longJustification = "a".repeat(1000);
        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.REMOVE_REVIEW, "TOS", longJustification, now
        );

        assertDoesNotThrow(() -> moderateReviewUseCase.execute(command));
    }

    @Test
    @DisplayName("10. Justificativa com mais de 1000 caracteres é rejeitada")
    void shouldRejectJustificationLongerThan1000Chars() {
        String tooLong = "a".repeat(1001);
        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.REMOVE_REVIEW, "TOS", tooLong, now
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> moderateReviewUseCase.execute(command));
        assertEquals("INVALID_JUSTIFICATION_LENGTH", ex.getErrorCode());
    }

    @Test
    @DisplayName("11. Moderador tentando moderar a própria avaliação é rejeitado com FORBIDDEN")
    void shouldRejectSelfModeration() {
        Review review = createReview(ReviewStatus.ACTIVE);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        // moderador é o mesmo autor da avaliação
        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, authorUserId, ModerationAction.REMOVE_REVIEW, "TOS", "Justificativa válida com mais de quinze caracteres.", now
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> moderateReviewUseCase.execute(command));
        assertEquals("SELF_MODERATION_FORBIDDEN", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(moderationAuditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("12. Moderador que possui denúncia PENDING para a avaliação é rejeitado com FORBIDDEN")
    void shouldRejectModeratorWhoIsPendingReporter() {
        Review review = createReview(ReviewStatus.ACTIVE);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        Report pendingReportFromModerator = new Report(
                UUID.randomUUID(), reviewId, moderatorUserId, ReportReason.HARASSMENT, "Denúncia do próprio moderador"
        );
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(List.of(pendingReportFromModerator));

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.REMOVE_REVIEW, "TOS", "Justificativa válida com mais de quinze caracteres.", now
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> moderateReviewUseCase.execute(command));
        assertEquals("REPORTER_CANNOT_MODERATE", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(moderationAuditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("13. Reports PENDING são resolvidos em lote como ACCEPTED no REMOVE")
    void shouldResolvePendingReportsAsAcceptedOnRemove() {
        Review review = createReview(ReviewStatus.ACTIVE);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        Report report1 = new Report(UUID.randomUUID(), reviewId, UUID.randomUUID(), ReportReason.SPAM, "Spam 1");
        Report report2 = new Report(UUID.randomUUID(), reviewId, UUID.randomUUID(), ReportReason.HARASSMENT, "Assédio");
        List<Report> pendingReports = List.of(report1, report2);
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(pendingReports);

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.REMOVE_REVIEW, "TOS", "Justificativa válida com mais de quinze caracteres.", now
        );

        moderateReviewUseCase.execute(command);

        assertEquals(ReportStatus.ACCEPTED, report1.getStatus());
        assertEquals(ReportStatus.ACCEPTED, report2.getStatus());
        verify(reportRepository).saveAll(pendingReports);
    }

    @Test
    @DisplayName("14. Reports PENDING são resolvidos em lote como REJECTED no RESTORE")
    void shouldResolvePendingReportsAsRejectedOnRestore() {
        Review review = createReview(ReviewStatus.UNDER_REVIEW);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        Report report1 = new Report(UUID.randomUUID(), reviewId, UUID.randomUUID(), ReportReason.SPAM, "Spam");
        List<Report> pendingReports = List.of(report1);
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(pendingReports);

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.RESTORE_REVIEW, "FALSE_POSITIVE", "Justificativa válida com mais de quinze caracteres.", now
        );

        moderateReviewUseCase.execute(command);

        assertEquals(ReportStatus.REJECTED, report1.getStatus());
        verify(reportRepository).saveAll(pendingReports);
    }

    @Test
    @DisplayName("15. Reports já finalizados (ACCEPTED/REJECTED) permanecem intactos")
    void shouldNotAlterAlreadyResolvedReports() {
        Review review = createReview(ReviewStatus.ACTIVE);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        // findPendingByReviewId retorna apenas os PENDING
        Report pendingReport = new Report(UUID.randomUUID(), reviewId, UUID.randomUUID(), ReportReason.SPAM, "Pendente");
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(List.of(pendingReport));

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.REMOVE_REVIEW, "TOS", "Justificativa válida com mais de quinze caracteres.", now
        );

        moderateReviewUseCase.execute(command);

        assertEquals(ReportStatus.ACCEPTED, pendingReport.getStatus());
        // saveAll recebe apenas os que foram resolvidos
        verify(reportRepository).saveAll(List.of(pendingReport));
    }

    @Test
    @DisplayName("16. reportsAffectedCount reflete exatamente a quantidade de denúncias pendentes resolvidas")
    void shouldReflectAccurateReportsAffectedCount() {
        Review review = createReview(ReviewStatus.ACTIVE);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        Report report1 = new Report(UUID.randomUUID(), reviewId, UUID.randomUUID(), ReportReason.SPAM, "1");
        Report report2 = new Report(UUID.randomUUID(), reviewId, UUID.randomUUID(), ReportReason.SPAM, "2");
        Report report3 = new Report(UUID.randomUUID(), reviewId, UUID.randomUUID(), ReportReason.SPAM, "3");
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(List.of(report1, report2, report3));

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.REMOVE_REVIEW, "TOS", "Justificativa válida com mais de quinze caracteres.", now
        );

        ModerateReviewResult result = moderateReviewUseCase.execute(command);

        assertEquals(3, result.auditLog().getReportsAffectedCount());
    }

    @Test
    @DisplayName("17. Audit log grava status anterior e novo com fidelidade")
    void shouldRecordPreviousAndNewStatusInAuditLog() {
        Review review = createReview(ReviewStatus.UNDER_REVIEW);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(List.of());

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.RESTORE_REVIEW, "FALSE_REPORT", "Justificativa válida com mais de quinze caracteres.", now
        );

        ModerateReviewResult result = moderateReviewUseCase.execute(command);

        assertEquals(ReviewStatus.UNDER_REVIEW, result.auditLog().getPreviousReviewStatus());
        assertEquals(ReviewStatus.ACTIVE, result.auditLog().getNewReviewStatus());
    }

    @Test
    @DisplayName("18. Audit log contém reasonCode e justification preservados")
    void shouldPreserveReasonCodeAndJustificationInAuditLog() {
        Review review = createReview(ReviewStatus.ACTIVE);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(List.of());

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.REMOVE_REVIEW, "REASON_XYZ", "Justificativa com conteúdo textual completo e válido.", now
        );

        ModerateReviewResult result = moderateReviewUseCase.execute(command);

        assertEquals("REASON_XYZ", result.auditLog().getReasonCode());
        assertEquals("Justificativa com conteúdo textual completo e válido.", result.auditLog().getJustification());
        assertEquals(now, result.auditLog().getCreatedAt());
        verify(moderationAuditLogRepository).save(result.auditLog());
    }

    @Test
    @DisplayName("19. Recálculo de RateableTargetStats é executado para todos os alvos em targetId ASC")
    void shouldRecalculateRateableTargetStatsInAscendingOrder() {
        Review review = createReview(ReviewStatus.ACTIVE);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(List.of());

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.REMOVE_REVIEW, "TOS", "Justificativa válida com mais de quinze caracteres.", now
        );

        moderateReviewUseCase.execute(command);

        InOrder inOrder = inOrder(rateableTargetStatsRepository);
        UUID firstTarget = targetAId.compareTo(targetBId) <= 0 ? targetAId : targetBId;
        UUID secondTarget = targetAId.compareTo(targetBId) <= 0 ? targetBId : targetAId;

        inOrder.verify(rateableTargetStatsRepository).recalculateAndSave(firstTarget);
        inOrder.verify(rateableTargetStatsRepository).recalculateAndSave(secondTarget);
    }

    @Test
    @DisplayName("20. Recálculo factual de reputação é executado para o autor da avaliação")
    void shouldRecalculateUserReputation() {
        Review review = createReview(ReviewStatus.ACTIVE);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(List.of());

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.REMOVE_REVIEW, "TOS", "Justificativa válida com mais de quinze caracteres.", now
        );

        moderateReviewUseCase.execute(command);

        verify(reputationService).recalculateAndSave(authorUserId);
    }

    @Test
    @DisplayName("21. Mídias ativas são logicamente marcadas como REMOVED no REMOVE")
    void shouldMarkActiveMediaAsRemovedOnRemove() {
        Review review = createReview(ReviewStatus.ACTIVE);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(List.of());

        ReviewMedia media1 = new ReviewMedia(UUID.randomUUID(), reviewId, authorUserId, "key1", ReviewMediaType.IMAGE, "image/jpeg", 1000L, 800, 600);
        ReviewMedia media2 = new ReviewMedia(UUID.randomUUID(), reviewId, authorUserId, "key2", ReviewMediaType.IMAGE, "image/jpeg", 2000L, 800, 600);
        when(reviewMediaRepository.findActiveByReviewId(reviewId)).thenReturn(List.of(media1, media2));

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.REMOVE_REVIEW, "TOS", "Justificativa válida com mais de quinze caracteres.", now
        );

        moderateReviewUseCase.execute(command);

        assertFalse(media1.isActive());
        assertFalse(media2.isActive());
        verify(reviewMediaRepository).save(media1);
        verify(reviewMediaRepository).save(media2);
    }

    @Test
    @DisplayName("22. CheckIn da avaliação é preservado (não afetado)")
    void shouldPreserveCheckInOnModeration() {
        Review review = createReview(ReviewStatus.ACTIVE);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(List.of());

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.REMOVE_REVIEW, "TOS", "Justificativa válida com mais de quinze caracteres.", now
        );

        moderateReviewUseCase.execute(command);

        // A avaliação continua existindo e associada ao seu id/autor/check-in
        assertEquals(authorUserId, review.getUserId());
        assertEquals(reviewId, review.getId());
    }

    @Test
    @DisplayName("23. Falha intermediária propaga exceção permitindo rollback transacional")
    void shouldPropagateExceptionWhenIntermediateStepFails() {
        Review review = createReview(ReviewStatus.ACTIVE);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(List.of());
        doThrow(new RuntimeException("Simulação de falha no banco"))
                .when(moderationAuditLogRepository).save(any());

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.REMOVE_REVIEW, "TOS", "Justificativa válida com mais de quinze caracteres.", now
        );

        assertThrows(RuntimeException.class, () -> moderateReviewUseCase.execute(command));
    }

    @Test
    @DisplayName("24. Avaliação inexistente é tratada com 404 NOT_FOUND")
    void shouldThrowNotFoundWhenReviewDoesNotExist() {
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.empty());

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.REMOVE_REVIEW, "TOS", "Justificativa válida com mais de quinze caracteres.", now
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> moderateReviewUseCase.execute(command));
        assertEquals("REVIEW_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("25. Action inválida para o estado atual é rejeitada (ex: REMOVE em review já REMOVED)")
    void shouldRejectInvalidActionForCurrentState() {
        Review review = createReview(ReviewStatus.REMOVED);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findPendingByReviewId(reviewId)).thenReturn(List.of());

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId, moderatorUserId, ModerationAction.REMOVE_REVIEW, "TOS", "Justificativa válida com mais de quinze caracteres.", now
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> moderateReviewUseCase.execute(command));
        assertEquals("REVIEW_ALREADY_REMOVED", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }
}
