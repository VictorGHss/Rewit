package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.AdminDiscussionContextView;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.AdminDiscussionReportView;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.AdminDiscussionView;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.ModerateDiscussionCommand;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.ModerateDiscussionResult;
import com.rewit.application.usecase.GetAdminDiscussionContextUseCase;
import com.rewit.application.usecase.ModerateDiscussionUseCase;
import com.rewit.application.usecase.QueryAdminDiscussionReportsUseCase;
import com.rewit.common.exception.GlobalExceptionHandler;
import com.rewit.domain.enums.DiscussionModerationAction;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.enums.ModerationDecision;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.model.DiscussionModerationAuditLog;
import com.rewit.domain.model.ReviewDiscussion;
import com.rewit.presentation.dto.admin.AdminDiscussionModerationDtos.ModerateDiscussionRequest;
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
@DisplayName("Testes Unitários: AdminDiscussionModerationController (C10 - propagação de actorUserId)")
class AdminDiscussionModerationControllerUnitTest {

    @Mock
    private QueryAdminDiscussionReportsUseCase queryAdminDiscussionReportsUseCase;

    @Mock
    private GetAdminDiscussionContextUseCase getAdminDiscussionContextUseCase;

    @Mock
    private ModerateDiscussionUseCase moderateDiscussionUseCase;

    @InjectMocks
    private AdminDiscussionModerationController controller;

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
    @DisplayName("GET /api/v1/admin/discussion-reports - Deve encaminhar actorUserId e filtros ao caso de uso")
    void shouldListDiscussionReportsForwardingActorUserIdAndFilters() throws Exception {
        UUID discussionId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID authorUserId = UUID.randomUUID();
        UUID reporterUserId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-11T12:00:00Z");

        AdminDiscussionReportView reportView = new AdminDiscussionReportView(
                reportId, discussionId, reviewId, null, authorUserId,
                DiscussionStatus.UNDER_REVIEW, reporterUserId, ReportReason.HARASSMENT,
                "Ofensas reiteradas", ReportStatus.PENDING, now, now
        );
        PageResult<AdminDiscussionReportView> pageResult = new PageResult<>(List.of(reportView), 2, 15, 1, 1, true);

        when(queryAdminDiscussionReportsUseCase.execute(
                eq(actorUserId), eq(2), eq(15), eq(ReportStatus.PENDING),
                eq(ReportReason.HARASSMENT), eq(discussionId), eq("desc")
        )).thenReturn(pageResult);

        mockMvc.perform(get("/api/v1/admin/discussion-reports")
                        .principal(authentication)
                        .param("page", "2")
                        .param("size", "15")
                        .param("status", "PENDING")
                        .param("reason", "HARASSMENT")
                        .param("discussionId", discussionId.toString())
                        .param("sort", "desc")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(reportId.toString()))
                .andExpect(jsonPath("$.content[0].discussionId").value(discussionId.toString()))
                .andExpect(jsonPath("$.content[0].reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.content[0].reason").value("HARASSMENT"))
                .andExpect(jsonPath("$.pageNumber").value(2))
                .andExpect(jsonPath("$.pageSize").value(15))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(queryAdminDiscussionReportsUseCase).execute(
                actorUserId, 2, 15, ReportStatus.PENDING,
                ReportReason.HARASSMENT, discussionId, "desc"
        );
    }

    @Test
    @DisplayName("GET /api/v1/admin/discussion-reports - Deve encaminhar actorUserId com parâmetros default")
    void shouldListDiscussionReportsWithDefaultsForwardingActorUserId() throws Exception {
        PageResult<AdminDiscussionReportView> emptyResult = new PageResult<>(List.of(), 0, 20, 0, 0, true);

        when(queryAdminDiscussionReportsUseCase.execute(
                eq(actorUserId), eq(0), eq(20), eq(null), eq(null), eq(null), eq("asc")
        )).thenReturn(emptyResult);

        mockMvc.perform(get("/api/v1/admin/discussion-reports")
                        .principal(authentication)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(20));

        verify(queryAdminDiscussionReportsUseCase).execute(
                actorUserId, 0, 20, null, null, null, "asc"
        );
    }

    @Test
    @DisplayName("GET /api/v1/admin/discussion-reports - Deve rejeitar com 401 quando não autenticado")
    void shouldRejectListDiscussionReportsWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/admin/discussion-reports")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/admin/discussions/{discussionId} - Deve encaminhar actorUserId e discussionId ao caso de uso")
    void shouldGetDiscussionContextForwardingActorUserId() throws Exception {
        UUID discussionId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-11T12:00:00Z");

        AdminDiscussionView discussionView = new AdminDiscussionView(
                discussionId, reviewId, null, UUID.randomUUID(), "Comentário em análise",
                DiscussionStatus.UNDER_REVIEW, false, now, now
        );
        AdminDiscussionContextView contextView = new AdminDiscussionContextView(
                discussionView, null, 1L, List.of(), List.of()
        );

        when(getAdminDiscussionContextUseCase.execute(eq(actorUserId), eq(discussionId))).thenReturn(contextView);

        mockMvc.perform(get("/api/v1/admin/discussions/{discussionId}", discussionId)
                        .principal(authentication)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discussion.id").value(discussionId.toString()))
                .andExpect(jsonPath("$.discussion.content").value("Comentário em análise"))
                .andExpect(jsonPath("$.discussion.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.pendingReportCount").value(1));

        verify(getAdminDiscussionContextUseCase).execute(actorUserId, discussionId);
    }

    @Test
    @DisplayName("GET /api/v1/admin/discussions/{discussionId} - Deve rejeitar com 401 quando não autenticado")
    void shouldRejectGetDiscussionContextWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/admin/discussions/{discussionId}", UUID.randomUUID())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/admin/discussions/{discussionId}/moderate - Deve encaminhar identidade do moderador ao comando")
    void shouldModerateDiscussionForwardingModeratorUserId() throws Exception {
        UUID discussionId = UUID.randomUUID();
        ModerateDiscussionRequest request = new ModerateDiscussionRequest(
                DiscussionModerationAction.REMOVE_DISCUSSION, "HARASSMENT", "Comentário com ofensas"
        );

        DiscussionModerationAuditLog auditLog = new DiscussionModerationAuditLog(
                UUID.randomUUID(), discussionId, actorUserId,
                DiscussionModerationAction.REMOVE_DISCUSSION, ModerationDecision.ACCEPTED,
                "HARASSMENT", "Comentário com ofensas",
                DiscussionStatus.UNDER_REVIEW, DiscussionStatus.REMOVED, 2, Instant.now()
        );
        ReviewDiscussion discussion = new ReviewDiscussion(
                discussionId, UUID.randomUUID(), UUID.randomUUID(), null, "Texto", false
        );
        ModerateDiscussionResult result = new ModerateDiscussionResult(auditLog, discussion);

        when(moderateDiscussionUseCase.execute(any(ModerateDiscussionCommand.class))).thenReturn(result);

        mockMvc.perform(post("/api/v1/admin/discussions/{discussionId}/moderate", discussionId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discussionId").value(discussionId.toString()))
                .andExpect(jsonPath("$.action").value("REMOVE_DISCUSSION"))
                .andExpect(jsonPath("$.previousStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.newStatus").value("REMOVED"))
                .andExpect(jsonPath("$.resolvedReportsCount").value(2));

        ArgumentCaptor<ModerateDiscussionCommand> captor = ArgumentCaptor.forClass(ModerateDiscussionCommand.class);
        verify(moderateDiscussionUseCase).execute(captor.capture());
        assertEquals(actorUserId, captor.getValue().moderatorUserId());
        assertEquals(discussionId, captor.getValue().discussionId());
        assertEquals(DiscussionModerationAction.REMOVE_DISCUSSION, captor.getValue().action());
    }
}
