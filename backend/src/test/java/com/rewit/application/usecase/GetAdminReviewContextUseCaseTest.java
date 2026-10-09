package com.rewit.application.usecase;

import com.rewit.application.dto.report.ReportDtos.AdminReviewContextView;
import com.rewit.application.dto.report.ReportDtos.AdminReviewTargetView;
import com.rewit.application.port.ModerationAuditLogRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.RateableTargetRepository.TargetDisplay;
import com.rewit.application.port.ReportRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ModerationAction;
import com.rewit.domain.enums.ModerationDecision;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.ModerationAuditLog;
import com.rewit.domain.model.Report;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetAdminReviewContextUseCase (C8): contexto de moderação somente leitura e sem identidades")
class GetAdminReviewContextUseCaseTest {

    @Mock private ReviewRepository reviewRepository;
    @Mock private ReportRepository reportRepository;
    @Mock private ModerationAuditLogRepository auditLogRepository;
    @Mock private RateableTargetRepository rateableTargetRepository;

    private GetAdminReviewContextUseCase useCase;

    private final UUID reviewId = UUID.randomUUID();
    private final UUID authorId = UUID.randomUUID();
    private final Instant created = Instant.parse("2026-10-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GetAdminReviewContextUseCase(reviewRepository, reportRepository, auditLogRepository,
                rateableTargetRepository);
    }

    @Test
    @DisplayName("Identificador nulo: 400 MISSING_REVIEW_ID sem consultar nada")
    void nullId() {
        BusinessException ex = assertThrows(BusinessException.class, () -> useCase.execute(null));
        assertEquals("MISSING_REVIEW_ID", ex.getErrorCode());
        assertEquals(400, ex.getStatus().value());
        verifyNoInteractions(reviewRepository, reportRepository, auditLogRepository, rateableTargetRepository);
    }

    @Test
    @DisplayName("Avaliação inexistente: 404 REVIEW_NOT_FOUND")
    void missingReview() {
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> useCase.execute(reviewId));
        assertEquals("REVIEW_NOT_FOUND", ex.getErrorCode());
        assertEquals(404, ex.getStatus().value());
        verifyNoInteractions(reportRepository, auditLogRepository, rateableTargetRepository);
    }

    @Test
    @DisplayName("Projeta avaliação, alvos com nome (null sem especialização), denúncias, pendentes e histórico")
    void projectsContext() {
        UUID placeId = UUID.randomUUID();
        UUID orphanId = UUID.randomUUID();
        Review review = new Review(reviewId, authorId, null, "Texto", true, true, ReviewStatus.UNDER_REVIEW, "FOLLOWERS",
                -25.4, -49.2, 5.0, created, created.plusSeconds(60));
        review.addTarget(new ReviewTarget(UUID.randomUUID(), reviewId, placeId, new BigDecimal("4.5"), "Bom"));
        review.addTarget(new ReviewTarget(UUID.randomUUID(), reviewId, orphanId, new BigDecimal("2.0"), null));
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(rateableTargetRepository.findDisplaysByIds(any()))
                .thenReturn(List.of(new TargetDisplay(placeId, TargetType.PLACE, "Bar Central")));
        when(reportRepository.findByReviewId(reviewId)).thenReturn(List.of(
                new Report(UUID.randomUUID(), reviewId, UUID.randomUUID(), ReportReason.SPAM, "a", ReportStatus.PENDING,
                        created, created),
                new Report(UUID.randomUUID(), reviewId, UUID.randomUUID(), ReportReason.FRAUD, null, ReportStatus.REJECTED,
                        created, created),
                new Report(UUID.randomUUID(), reviewId, UUID.randomUUID(), ReportReason.HARASSMENT, "c", ReportStatus.PENDING,
                        created, created)));
        when(auditLogRepository.findByReviewId(reviewId)).thenReturn(List.of(
                new ModerationAuditLog(UUID.randomUUID(), reviewId, UUID.randomUUID(), ModerationAction.RESTORE_REVIEW,
                        ModerationDecision.REJECTED, "NO_VIOLATION", "Justificativa da restauração", ReviewStatus.UNDER_REVIEW,
                        ReviewStatus.ACTIVE, 1, created)));

        AdminReviewContextView view = useCase.execute(reviewId);

        assertEquals(reviewId, view.review().id());
        assertEquals("Texto", view.review().experienceText());
        assertEquals(ReviewStatus.UNDER_REVIEW, view.review().status());
        assertEquals("FOLLOWERS", view.review().visibility());
        assertTrue(view.review().isAnonymous());
        assertTrue(view.review().isVerifiedOnSite());
        assertEquals(created, view.review().createdAt());
        assertEquals(review.getUpdatedAt(), view.review().updatedAt());

        AdminReviewTargetView place = view.review().targets().get(0);
        assertEquals(placeId, place.targetId());
        assertEquals(TargetType.PLACE, place.type());
        assertEquals("Bar Central", place.displayName());
        assertEquals(new BigDecimal("4.5"), place.rating());
        assertEquals("Bom", place.specificComment());
        AdminReviewTargetView orphan = view.review().targets().get(1);
        assertEquals(orphanId, orphan.targetId());
        assertNull(orphan.type());
        assertNull(orphan.displayName());
        assertNull(orphan.specificComment());

        assertEquals(2, view.pendingReportCount());
        assertEquals(3, view.reports().size());
        assertEquals(ReportReason.FRAUD, view.reports().get(1).reason());
        assertEquals(ReportStatus.REJECTED, view.reports().get(1).status());
        assertEquals(1, view.auditHistory().size());
        assertEquals(ModerationAction.RESTORE_REVIEW, view.auditHistory().get(0).action());
        assertEquals("NO_VIOLATION", view.auditHistory().get(0).reasonCode());
        assertEquals(ReviewStatus.UNDER_REVIEW, view.auditHistory().get(0).previousStatus());
        assertEquals(ReviewStatus.ACTIVE, view.auditHistory().get(0).newStatus());

        // Somente leitura
        verify(reviewRepository, never()).save(any());
        verify(reportRepository, never()).save(any());
        verify(reportRepository, never()).saveAll(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("Avaliação sem alvos não consulta nomes")
    void noTargetsNoDisplayQuery() {
        Review review = new Review(reviewId, authorId, null, "Texto", false, false, ReviewStatus.ACTIVE, "PUBLIC",
                null, null, null, created, created);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findByReviewId(reviewId)).thenReturn(List.of());
        when(auditLogRepository.findByReviewId(reviewId)).thenReturn(List.of());

        AdminReviewContextView view = useCase.execute(reviewId);

        assertTrue(view.review().targets().isEmpty());
        assertEquals(0, view.pendingReportCount());
        verifyNoInteractions(rateableTargetRepository);
    }
}
