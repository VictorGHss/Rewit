package com.rewit.application.service;

import com.rewit.application.dto.report.ReportDtos.CreateReportCommand;
import com.rewit.application.port.RateLimiter;
import com.rewit.application.port.ReportRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.UserFollowRepository;
import com.rewit.application.service.ReportService.CreateReportResult;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Report;
import com.rewit.domain.model.Review;
import com.rewit.infrastructure.ratelimit.RateLimitTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: ReportService e Moderação Preventiva (Step 19.0 / Seção 22)")
class ReportServiceUnitTest {

    @Mock
    private AccountStatusPolicy accountStatusPolicy;

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private UserFollowRepository userFollowRepository;

    private RateLimiter rateLimiter;
    private ReportService reportService;

    private final UUID authorUserId = UUID.randomUUID();
    private final UUID reporterUserId = UUID.randomUUID();
    private final UUID reviewId = UUID.randomUUID();
    private final UUID placeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        rateLimiter = RateLimitTestSupport.inMemory();
        reportService = new ReportService(
                reportRepository,
                reviewRepository,
                accountStatusPolicy,
                userFollowRepository,
                rateLimiter
        );
    }

    private Review createReview(ReviewStatus status, String visibility) {
        return new Review(
                reviewId,
                authorUserId,
                placeId,
                "Texto avaliativo para testes de denúncia",
                false,
                false,
                status,
                visibility,
                null,
                null,
                null,
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("1. Report válido em Review ACTIVE e PUBLIC: salva com PENDING e newlyCreated true")
    void createReport_validPublicReview_shouldSaveReport() {
        Review review = createReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findByReviewIdAndReporterUserId(reviewId, reporterUserId)).thenReturn(Optional.empty());

        Report saved = new Report(UUID.randomUUID(), reviewId, reporterUserId, ReportReason.SPAM, "Spam detalhe");
        when(reportRepository.save(any(Report.class))).thenReturn(saved);
        when(reportRepository.countPendingByReviewId(reviewId)).thenReturn(1L);

        CreateReportCommand cmd = new CreateReportCommand(reporterUserId, reviewId, ReportReason.SPAM, "Spam detalhe");
        CreateReportResult result = reportService.createReport(cmd);

        assertNotNull(result);
        assertTrue(result.newlyCreated());
        assertEquals(ReportReason.SPAM, result.report().getReason());
        assertEquals(ReportStatus.PENDING, result.report().getStatus());
        verify(reportRepository, times(1)).save(any(Report.class));
        verify(reviewRepository, never()).save(any(Review.class));
    }

    @Test
    @DisplayName("2. Review inexistente: deve lançar 404 REVIEW_NOT_FOUND")
    void createReport_nonExistentReview_shouldThrowNotFound() {
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.empty());

        CreateReportCommand cmd = new CreateReportCommand(reporterUserId, reviewId, ReportReason.SPAM, null);
        BusinessException ex = assertThrows(BusinessException.class, () -> reportService.createReport(cmd));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("REVIEW_NOT_FOUND", ex.getErrorCode());
        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("3. Self-report: autor não pode denunciar a própria Review (400 SELF_REPORT_FORBIDDEN)")
    void createReport_authorSelfReport_shouldThrowBadRequest() {
        Review review = createReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        CreateReportCommand cmd = new CreateReportCommand(authorUserId, reviewId, ReportReason.HARASSMENT, "Meu texto");
        BusinessException ex = assertThrows(BusinessException.class, () -> reportService.createReport(cmd));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("SELF_REPORT_FORBIDDEN", ex.getErrorCode());
        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("4. Review PRIVATE inacessível: terceiro não pode denunciar (403 FORBIDDEN)")
    void createReport_privateReview_shouldThrowForbidden() {
        Review review = createReview(ReviewStatus.ACTIVE, "PRIVATE");
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        CreateReportCommand cmd = new CreateReportCommand(reporterUserId, reviewId, ReportReason.SPAM, null);
        BusinessException ex = assertThrows(BusinessException.class, () -> reportService.createReport(cmd));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("FORBIDDEN", ex.getErrorCode());
        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("5. Review FOLLOWERS com seguidor autorizado: denúncia aceita com sucesso")
    void createReport_followersReviewAuthorized_shouldSucceed() {
        Review review = createReview(ReviewStatus.ACTIVE, "FOLLOWERS");
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(userFollowRepository.isFollowing(reporterUserId, authorUserId)).thenReturn(true);
        when(reportRepository.findByReviewIdAndReporterUserId(reviewId, reporterUserId)).thenReturn(Optional.empty());

        Report saved = new Report(UUID.randomUUID(), reviewId, reporterUserId, ReportReason.INAPPROPRIATE_CONTENT, null);
        when(reportRepository.save(any(Report.class))).thenReturn(saved);
        when(reportRepository.countPendingByReviewId(reviewId)).thenReturn(1L);

        CreateReportCommand cmd = new CreateReportCommand(reporterUserId, reviewId, ReportReason.INAPPROPRIATE_CONTENT, null);
        CreateReportResult result = reportService.createReport(cmd);

        assertTrue(result.newlyCreated());
        assertEquals(ReportReason.INAPPROPRIATE_CONTENT, result.report().getReason());
    }

    @Test
    @DisplayName("6. Review FOLLOWERS com usuário não seguidor: deve rejeitar com 403 FORBIDDEN")
    void createReport_followersReviewUnauthorized_shouldThrowForbidden() {
        Review review = createReview(ReviewStatus.ACTIVE, "FOLLOWERS");
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(userFollowRepository.isFollowing(reporterUserId, authorUserId)).thenReturn(false);

        CreateReportCommand cmd = new CreateReportCommand(reporterUserId, reviewId, ReportReason.SPAM, null);
        BusinessException ex = assertThrows(BusinessException.class, () -> reportService.createReport(cmd));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("FORBIDDEN", ex.getErrorCode());
        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("7. Review UNDER_REVIEW: deve rejeitar novas denúncias com 404 REVIEW_NOT_FOUND")
    void createReport_reviewUnderReview_shouldThrowNotFound() {
        Review review = createReview(ReviewStatus.UNDER_REVIEW, "PUBLIC");
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        CreateReportCommand cmd = new CreateReportCommand(reporterUserId, reviewId, ReportReason.SPAM, null);
        BusinessException ex = assertThrows(BusinessException.class, () -> reportService.createReport(cmd));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("REVIEW_NOT_FOUND", ex.getErrorCode());
        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("8. Review REMOVED: deve rejeitar novas denúncias com 404 REVIEW_NOT_FOUND")
    void createReport_reviewRemoved_shouldThrowNotFound() {
        Review review = createReview(ReviewStatus.REMOVED, "PUBLIC");
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        CreateReportCommand cmd = new CreateReportCommand(reporterUserId, reviewId, ReportReason.SPAM, null);
        BusinessException ex = assertThrows(BusinessException.class, () -> reportService.createReport(cmd));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("REVIEW_NOT_FOUND", ex.getErrorCode());
        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("9. Motivo inválido no modelo Report: deve rejeitar com INVALID_REPORT_REASON")
    void createReport_nullReason_shouldThrowBusinessException() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                new Report(null, reviewId, reporterUserId, null, "detalhes")
        );
        assertEquals("INVALID_REPORT_REASON", ex.getErrorCode());
    }

    @Test
    @DisplayName("10. Detalhe com mais de 500 caracteres: deve rejeitar com INVALID_REPORT_DETAIL")
    void createReport_detailExceeding500Chars_shouldThrowBusinessException() {
        String longDetail = "A".repeat(501);
        BusinessException ex = assertThrows(BusinessException.class, () ->
                new Report(null, reviewId, reporterUserId, ReportReason.SPAM, longDetail)
        );
        assertEquals("INVALID_REPORT_DETAIL", ex.getErrorCode());
    }

    @Test
    @DisplayName("11. Idempotência: requisição repetida mesmo com motivo/detalhe divergentes retorna denúncia existente com newlyCreated false")
    void createReport_idempotentRepeat_shouldReturnExistingReport() {
        Review review = createReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        Report existing = new Report(UUID.randomUUID(), reviewId, reporterUserId, ReportReason.SPAM, "Primeiro envio");
        when(reportRepository.findByReviewIdAndReporterUserId(reviewId, reporterUserId)).thenReturn(Optional.of(existing));

        // Envia motivo e detalhe divergentes
        CreateReportCommand cmd = new CreateReportCommand(reporterUserId, reviewId, ReportReason.FRAUD, "Envio repetido diferente");
        CreateReportResult result = reportService.createReport(cmd);

        assertNotNull(result);
        assertFalse(result.newlyCreated());
        assertEquals(existing.getId(), result.report().getId());
        assertEquals(ReportReason.SPAM, result.report().getReason(), "Deve preservar o motivo original");
        assertEquals("Primeiro envio", result.report().getDetail(), "Deve preservar o detalhe original");
        verify(reportRepository, never()).save(any());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("12 e 14. Abaixo de 3 denúncias (contagem 1 ou 2): status da Review permanece ACTIVE")
    void createReport_belowThreshold_shouldKeepActive() {
        Review review = createReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findByReviewIdAndReporterUserId(reviewId, reporterUserId)).thenReturn(Optional.empty());

        Report saved = new Report(UUID.randomUUID(), reviewId, reporterUserId, ReportReason.FRAUD, null);
        when(reportRepository.save(any(Report.class))).thenReturn(saved);
        when(reportRepository.countPendingByReviewId(reviewId)).thenReturn(2L);

        CreateReportCommand cmd = new CreateReportCommand(reporterUserId, reviewId, ReportReason.FRAUD, null);
        CreateReportResult result = reportService.createReport(cmd);

        assertTrue(result.newlyCreated());
        assertEquals(ReviewStatus.ACTIVE, review.getStatus());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("13 e 15. Ao atingir o limiar de 3 denúncias pendentes: Review transiciona para UNDER_REVIEW")
    void createReport_reachingThresholdOf3_shouldTransitionToUnderReview() {
        Review review = createReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reportRepository.findByReviewIdAndReporterUserId(reviewId, reporterUserId)).thenReturn(Optional.empty());

        Report saved = new Report(UUID.randomUUID(), reviewId, reporterUserId, ReportReason.HATE_SPEECH, null);
        when(reportRepository.save(any(Report.class))).thenReturn(saved);
        when(reportRepository.countPendingByReviewId(reviewId)).thenReturn(3L);

        CreateReportCommand cmd = new CreateReportCommand(reporterUserId, reviewId, ReportReason.HATE_SPEECH, null);
        CreateReportResult result = reportService.createReport(cmd);

        assertTrue(result.newlyCreated());

        ArgumentCaptor<Review> reviewCaptor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository, times(1)).save(reviewCaptor.capture());
        assertEquals(ReviewStatus.UNDER_REVIEW, reviewCaptor.getValue().getStatus());
    }

    @Test
    @DisplayName("17. Rate Limiter: 10 denúncias aceitas, a 11ª requisição estritamente acima lança 429 RATE_LIMIT_EXCEEDED")
    void createReport_exceedingRateLimit_shouldThrowTooManyRequests() {
        UUID spammerId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.empty());
        CreateReportCommand cmd = new CreateReportCommand(spammerId, reviewId, ReportReason.SPAM, null);

        // 10 requisições passam pelo limite (no limite da janela de 60s) e seguem até a busca da review
        for (int i = 0; i < 10; i++) {
            BusinessException notFound = assertThrows(BusinessException.class, () -> reportService.createReport(cmd));
            assertEquals("REVIEW_NOT_FOUND", notFound.getErrorCode());
        }

        // 11ª requisição deve estourar imediatamente acima, antes de tocar o repositório
        BusinessException ex = assertThrows(BusinessException.class, () -> reportService.createReport(cmd));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        assertEquals("RATE_LIMIT_EXCEEDED", ex.getErrorCode());
        assertEquals("Limite de denúncias excedido. Tente novamente mais tarde.", ex.getMessage());
        verify(reviewRepository, times(10)).findByIdForUpdate(reviewId);
    }
}
