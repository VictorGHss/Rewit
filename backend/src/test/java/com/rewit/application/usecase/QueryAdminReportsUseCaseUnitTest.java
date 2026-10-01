package com.rewit.application.usecase;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.report.ReportDtos.AdminReportView;
import com.rewit.application.dto.report.ReportDtos.QueryAdminReportsFilter;
import com.rewit.application.port.ReportRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Report;
import com.rewit.domain.model.Review;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: QueryAdminReportsUseCase (Step 26.2)")
class QueryAdminReportsUseCaseUnitTest {

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private ReviewRepository reviewRepository;

    private QueryAdminReportsUseCase queryAdminReportsUseCase;

    private Instant now;

    @BeforeEach
    void setUp() {
        queryAdminReportsUseCase = new QueryAdminReportsUseCase(reportRepository, reviewRepository);
        now = Instant.parse("2026-10-01T12:00:00Z");
    }

    private Review createStubReview(UUID reviewId, UUID authorId, boolean anonymous) {
        return new Review(
                reviewId,
                authorId,
                null,
                "Texto de review",
                anonymous,
                false,
                ReviewStatus.ACTIVE,
                "PUBLIC",
                null,
                null,
                null,
                now.minusSeconds(1000),
                now.minusSeconds(1000)
        );
    }

    @Test
    @DisplayName("1. Paginação default (page = 0, size = 20, sortDirection = asc)")
    void shouldApplyDefaultPaginationParameters() {
        when(reportRepository.findAllPaged(isNull(), isNull(), isNull(), isNull(), eq(0), eq(20), eq("asc")))
                .thenReturn(PageResult.of(List.of(), 0, 20, 0));

        PageResult<AdminReportView> result = queryAdminReportsUseCase.execute(QueryAdminReportsFilter.ofDefault());

        assertNotNull(result);
        assertEquals(0, result.pageNumber());
        assertEquals(20, result.pageSize());
        verify(reportRepository).findAllPaged(isNull(), isNull(), isNull(), isNull(), eq(0), eq(20), eq("asc"));
    }

    @Test
    @DisplayName("2. Limite máximo size = 100 é aceito com sucesso")
    void shouldAcceptMaxSize100() {
        when(reportRepository.findAllPaged(any(), any(), any(), any(), eq(0), eq(100), any()))
                .thenReturn(PageResult.of(List.of(), 0, 100, 0));

        QueryAdminReportsFilter filter = new QueryAdminReportsFilter(0, 100, null, null, null, null, "asc");
        assertDoesNotThrow(() -> queryAdminReportsUseCase.execute(filter));
    }

    @Test
    @DisplayName("3. Rejeição de size > 100 e size <= 0 com BAD_REQUEST")
    void shouldRejectInvalidPageSize() {
        QueryAdminReportsFilter tooLarge = new QueryAdminReportsFilter(0, 101, null, null, null, null, "asc");
        BusinessException ex1 = assertThrows(BusinessException.class, () -> queryAdminReportsUseCase.execute(tooLarge));
        assertEquals("INVALID_PAGE_SIZE", ex1.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex1.getStatus());

        QueryAdminReportsFilter zeroSize = new QueryAdminReportsFilter(0, 0, null, null, null, null, "asc");
        BusinessException ex2 = assertThrows(BusinessException.class, () -> queryAdminReportsUseCase.execute(zeroSize));
        assertEquals("INVALID_PAGE_SIZE", ex2.getErrorCode());

        QueryAdminReportsFilter negativePage = new QueryAdminReportsFilter(-1, 20, null, null, null, null, "asc");
        BusinessException ex3 = assertThrows(BusinessException.class, () -> queryAdminReportsUseCase.execute(negativePage));
        assertEquals("INVALID_PAGE", ex3.getErrorCode());
    }

    @Test
    @DisplayName("4. Filtro por status (ex: PENDING)")
    void shouldFilterByStatus() {
        when(reportRepository.findAllPaged(eq(ReportStatus.PENDING), isNull(), isNull(), isNull(), eq(0), eq(20), eq("asc")))
                .thenReturn(PageResult.of(List.of(), 0, 20, 0));

        QueryAdminReportsFilter filter = new QueryAdminReportsFilter(0, 20, ReportStatus.PENDING, null, null, null, "asc");
        queryAdminReportsUseCase.execute(filter);

        verify(reportRepository).findAllPaged(eq(ReportStatus.PENDING), isNull(), isNull(), isNull(), eq(0), eq(20), eq("asc"));
    }

    @Test
    @DisplayName("5. Filtro por reason (ex: SPAM)")
    void shouldFilterByReason() {
        when(reportRepository.findAllPaged(isNull(), eq(ReportReason.SPAM), isNull(), isNull(), eq(0), eq(20), eq("asc")))
                .thenReturn(PageResult.of(List.of(), 0, 20, 0));

        QueryAdminReportsFilter filter = new QueryAdminReportsFilter(0, 20, null, ReportReason.SPAM, null, null, "asc");
        queryAdminReportsUseCase.execute(filter);

        verify(reportRepository).findAllPaged(isNull(), eq(ReportReason.SPAM), isNull(), isNull(), eq(0), eq(20), eq("asc"));
    }

    @Test
    @DisplayName("6. Filtro por reviewId")
    void shouldFilterByReviewId() {
        UUID reviewId = UUID.randomUUID();
        when(reportRepository.findAllPaged(isNull(), isNull(), eq(reviewId), isNull(), eq(0), eq(20), eq("asc")))
                .thenReturn(PageResult.of(List.of(), 0, 20, 0));

        QueryAdminReportsFilter filter = new QueryAdminReportsFilter(0, 20, null, null, reviewId, null, "asc");
        queryAdminReportsUseCase.execute(filter);

        verify(reportRepository).findAllPaged(isNull(), isNull(), eq(reviewId), isNull(), eq(0), eq(20), eq("asc"));
    }

    @Test
    @DisplayName("7. Filtro por reporterUserId")
    void shouldFilterByReporterUserId() {
        UUID reporterUserId = UUID.randomUUID();
        when(reportRepository.findAllPaged(isNull(), isNull(), isNull(), eq(reporterUserId), eq(0), eq(20), eq("asc")))
                .thenReturn(PageResult.of(List.of(), 0, 20, 0));

        QueryAdminReportsFilter filter = new QueryAdminReportsFilter(0, 20, null, null, null, reporterUserId, "asc");
        queryAdminReportsUseCase.execute(filter);

        verify(reportRepository).findAllPaged(isNull(), isNull(), isNull(), eq(reporterUserId), eq(0), eq(20), eq("asc"));
    }

    @Test
    @DisplayName("8. Combinação de múltiplos filtros simultaneamente")
    void shouldCombineMultipleFilters() {
        UUID reviewId = UUID.randomUUID();
        UUID reporterUserId = UUID.randomUUID();
        when(reportRepository.findAllPaged(eq(ReportStatus.PENDING), eq(ReportReason.INAPPROPRIATE_CONTENT), eq(reviewId), eq(reporterUserId), eq(1), eq(50), eq("desc")))
                .thenReturn(PageResult.of(List.of(), 1, 50, 0));

        QueryAdminReportsFilter filter = new QueryAdminReportsFilter(1, 50, ReportStatus.PENDING, ReportReason.INAPPROPRIATE_CONTENT, reviewId, reporterUserId, "desc");
        queryAdminReportsUseCase.execute(filter);

        verify(reportRepository).findAllPaged(eq(ReportStatus.PENDING), eq(ReportReason.INAPPROPRIATE_CONTENT), eq(reviewId), eq(reporterUserId), eq(1), eq(50), eq("desc"));
    }

    @Test
    @DisplayName("9. Ordenação determinística com suporte a ASC e DESC")
    void shouldSupportAscAndDescSortDirections() {
        when(reportRepository.findAllPaged(any(), any(), any(), any(), anyInt(), anyInt(), eq("asc")))
                .thenReturn(PageResult.of(List.of(), 0, 20, 0));
        when(reportRepository.findAllPaged(any(), any(), any(), any(), anyInt(), anyInt(), eq("desc")))
                .thenReturn(PageResult.of(List.of(), 0, 20, 0));

        queryAdminReportsUseCase.execute(new QueryAdminReportsFilter(0, 20, null, null, null, null, "asc"));
        queryAdminReportsUseCase.execute(new QueryAdminReportsFilter(0, 20, null, null, null, null, "desc"));

        verify(reportRepository).findAllPaged(any(), any(), any(), any(), anyInt(), anyInt(), eq("asc"));
        verify(reportRepository).findAllPaged(any(), any(), any(), any(), anyInt(), anyInt(), eq("desc"));
    }

    @Test
    @DisplayName("10. Paginação estável e hidratação sem N+1 identificando o verdadeiro autor interno mesmo de review anônima")
    void shouldHydrateInternalAuthorWithoutExposingSensitiveData() {
        UUID reviewId1 = UUID.randomUUID();
        UUID reviewId2 = UUID.randomUUID();
        UUID author1 = UUID.randomUUID();
        UUID author2 = UUID.randomUUID();

        Report report1 = new Report(UUID.randomUUID(), reviewId1, UUID.randomUUID(), ReportReason.SPAM, "Spam 1", ReportStatus.PENDING, now, now);
        Report report2 = new Report(UUID.randomUUID(), reviewId2, UUID.randomUUID(), ReportReason.HARASSMENT, "Ofensivo", ReportStatus.PENDING, now, now);

        when(reportRepository.findAllPaged(isNull(), isNull(), isNull(), isNull(), eq(0), eq(20), eq("asc")))
                .thenReturn(PageResult.of(List.of(report1, report2), 0, 20, 2));

        Review review1 = createStubReview(reviewId1, author1, true); // review pública é anônima
        Review review2 = createStubReview(reviewId2, author2, false);
        when(reviewRepository.findByIdIn(any())).thenReturn(List.of(review1, review2));

        PageResult<AdminReportView> result = queryAdminReportsUseCase.execute(QueryAdminReportsFilter.ofDefault());

        assertEquals(2, result.content().size());
        assertEquals(author1, result.content().get(0).reviewAuthorUserId());
        assertEquals(author2, result.content().get(1).reviewAuthorUserId());
        assertEquals(ReviewStatus.ACTIVE, result.content().get(0).reviewStatus());

        // Confirma que a hidratação de reviews foi feita em lote (findByIdIn chamado exatamente 1 vez)
        verify(reviewRepository, times(1)).findByIdIn(any());
    }
}
