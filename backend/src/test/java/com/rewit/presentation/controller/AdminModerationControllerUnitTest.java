package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.report.ReportDtos.AdminReportView;
import com.rewit.application.dto.report.ReportDtos.AdminReviewContextView;
import com.rewit.application.dto.report.ReportDtos.AdminReviewView;
import com.rewit.application.dto.report.ReportDtos.ModerateReviewCommand;
import com.rewit.application.dto.report.ReportDtos.ModerateReviewResult;
import com.rewit.application.usecase.GetAdminReviewContextUseCase;
import com.rewit.application.usecase.ModerateReviewUseCase;
import com.rewit.application.usecase.QueryAdminReportsUseCase;
import com.rewit.common.exception.GlobalExceptionHandler;
import com.rewit.domain.enums.ModerationAction;
import com.rewit.domain.enums.ModerationDecision;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.ModerationAuditLog;
import com.rewit.domain.model.Review;
import com.rewit.presentation.dto.admin.AdminModerationDtos.ModerateReviewRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: AdminModerationController (C10 - propagação de actorUserId)")
class AdminModerationControllerUnitTest {

    @Mock
    private ModerateReviewUseCase moderateReviewUseCase;

    @Mock
    private QueryAdminReportsUseCase queryAdminReportsUseCase;

    @Mock
    private GetAdminReviewContextUseCase getAdminReviewContextUseCase;

    @InjectMocks
    private AdminModerationController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private UUID actorUserId;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        actorUserId = UUID.randomUUID();
        authentication = new UsernamePasswordAuthenticationToken(actorUserId.toString(), "password");
    }

    @Test
    @DisplayName("GET /api/v1/admin/reports - Deve encaminhar actorUserId e filtros ao caso de uso")
    void shouldListReportsForwardingActorUserIdAndFilters() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID reporterUserId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID authorUserId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-11T12:00:00Z");

        AdminReportView reportView = new AdminReportView(
                reportId, reviewId, authorUserId, ReviewStatus.UNDER_REVIEW,
                reporterUserId, ReportReason.SPAM, "Spam flag", ReportStatus.PENDING,
                now, now
        );
        PageResult<AdminReportView> pageResult = new PageResult<>(List.of(reportView), 1, 10, 1, 1, true);

        when(queryAdminReportsUseCase.execute(
                eq(actorUserId), eq(1), eq(10), eq(ReportStatus.PENDING),
                eq(ReportReason.SPAM), eq(reviewId), eq(reporterUserId), eq("desc")
        )).thenReturn(pageResult);

        mockMvc.perform(get("/api/v1/admin/reports")
                        .principal(authentication)
                        .param("page", "1")
                        .param("size", "10")
                        .param("status", "PENDING")
                        .param("reason", "SPAM")
                        .param("reviewId", reviewId.toString())
                        .param("reporterUserId", reporterUserId.toString())
                        .param("sort", "desc")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(reportId.toString()))
                .andExpect(jsonPath("$.content[0].reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.content[0].reviewAuthorUserId").value(authorUserId.toString()))
                .andExpect(jsonPath("$.content[0].reporterUserId").value(reporterUserId.toString()))
                .andExpect(jsonPath("$.content[0].reason").value("SPAM"))
                .andExpect(jsonPath("$.pageNumber").value(1))
                .andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(queryAdminReportsUseCase).execute(
                actorUserId, 1, 10, ReportStatus.PENDING,
                ReportReason.SPAM, reviewId, reporterUserId, "desc"
        );
    }

    @Test
    @DisplayName("GET /api/v1/admin/reports - Deve encaminhar actorUserId com parâmetros default")
    void shouldListReportsWithDefaultsForwardingActorUserId() throws Exception {
        PageResult<AdminReportView> emptyResult = new PageResult<>(List.of(), 0, 20, 0, 0, true);

        when(queryAdminReportsUseCase.execute(
                eq(actorUserId), eq(0), eq(20), eq(null), eq(null), eq(null), eq(null), eq("asc")
        )).thenReturn(emptyResult);

        mockMvc.perform(get("/api/v1/admin/reports")
                        .principal(authentication)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(20));

        verify(queryAdminReportsUseCase).execute(
                actorUserId, 0, 20, null, null, null, null, "asc"
        );
    }

    @Test
    @DisplayName("GET /api/v1/admin/reports - Deve rejeitar com 401 quando não autenticado")
    void shouldRejectListReportsWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/admin/reviews/{reviewId}/context - Deve encaminhar actorUserId e reviewId ao caso de uso")
    void shouldGetReviewContextForwardingActorUserId() throws Exception {
        UUID reviewId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-11T12:00:00Z");

        AdminReviewView reviewView = new AdminReviewView(
                reviewId, "Texto da avaliação", ReviewStatus.UNDER_REVIEW, "PUBLIC",
                false, true, now, now, List.of()
        );
        AdminReviewContextView contextView = new AdminReviewContextView(
                reviewView, 2L, List.of(), List.of()
        );

        when(getAdminReviewContextUseCase.execute(eq(actorUserId), eq(reviewId))).thenReturn(contextView);

        mockMvc.perform(get("/api/v1/admin/reviews/{reviewId}/context", reviewId)
                        .principal(authentication)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.id").value(reviewId.toString()))
                .andExpect(jsonPath("$.review.experienceText").value("Texto da avaliação"))
                .andExpect(jsonPath("$.review.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.pendingReportCount").value(2));

        verify(getAdminReviewContextUseCase).execute(actorUserId, reviewId);
    }

    @Test
    @DisplayName("GET /api/v1/admin/reviews/{reviewId}/context - Deve rejeitar com 401 quando não autenticado")
    void shouldRejectGetReviewContextWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reviews/{reviewId}/context", UUID.randomUUID())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/admin/reviews/{reviewId}/moderate - Deve encaminhar identidade do moderador ao comando")
    void shouldModerateReviewForwardingModeratorUserId() throws Exception {
        UUID reviewId = UUID.randomUUID();
        ModerateReviewRequest request = new ModerateReviewRequest(
                ModerationAction.REMOVE_REVIEW, "SPAM", "Conteúdo publicitário indevido"
        );

        ModerationAuditLog auditLog = new ModerationAuditLog(
                UUID.randomUUID(), reviewId, actorUserId, ModerationAction.REMOVE_REVIEW,
                ModerationDecision.ACCEPTED, "SPAM", "Conteúdo publicitário indevido", ReviewStatus.ACTIVE,
                ReviewStatus.REMOVED, 3, Instant.now()
        );
        Review review = new Review(
                reviewId, UUID.randomUUID(), UUID.randomUUID(), "Texto", false, false,
                ReviewStatus.REMOVED, "PUBLIC", null, null, null, Instant.now(), Instant.now()
        );
        ModerateReviewResult result = new ModerateReviewResult(auditLog, review);

        when(moderateReviewUseCase.execute(any(ModerateReviewCommand.class))).thenReturn(result);

        mockMvc.perform(post("/api/v1/admin/reviews/{reviewId}/moderate", reviewId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.action").value("REMOVE_REVIEW"))
                .andExpect(jsonPath("$.previousStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.newStatus").value("REMOVED"))
                .andExpect(jsonPath("$.resolvedReportsCount").value(3));

        ArgumentCaptor<ModerateReviewCommand> captor = ArgumentCaptor.forClass(ModerateReviewCommand.class);
        verify(moderateReviewUseCase).execute(captor.capture());
        assertEquals(actorUserId, captor.getValue().moderatorUserId());
        assertEquals(reviewId, captor.getValue().reviewId());
        assertEquals(ModerationAction.REMOVE_REVIEW, captor.getValue().action());
    }
}
