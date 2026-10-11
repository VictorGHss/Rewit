package com.rewit.application.usecase;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.AdminDiscussionReportView;
import com.rewit.application.port.DiscussionReportRepository;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.AccountStatusPolicy;
import com.rewit.application.service.ModeratorRolePolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.enums.Role;
import com.rewit.domain.model.DiscussionReport;
import com.rewit.domain.model.ReviewDiscussion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.rewit.application.usecase.CurrentRoleStubs.currentRole;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueryAdminDiscussionReportsUseCase: revalidação do cargo atual antes da fila")
class QueryAdminDiscussionReportsUseCaseTest {

    @Mock private DiscussionReportRepository discussionReportRepository;
    @Mock private DiscussionRepository discussionRepository;
    @Mock private AccountStatusPolicy accountStatusPolicy;
    @Mock private UserRepository userRepository;

    private QueryAdminDiscussionReportsUseCase useCase;

    private final UUID actor = UUID.randomUUID();
    private final UUID discussionId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-10T12:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new QueryAdminDiscussionReportsUseCase(discussionReportRepository, discussionRepository,
                new ModeratorRolePolicy(accountStatusPolicy, userRepository));
    }

    /** Uma denúncia pendente do comentário, com filtros repassados ao repositório. */
    private DiscussionReport stubPage() {
        DiscussionReport report = new DiscussionReport(UUID.randomUUID(), discussionId, UUID.randomUUID(),
                ReportReason.HARASSMENT, "detalhe", ReportStatus.PENDING, now, now);
        ReviewDiscussion discussion = new ReviewDiscussion(discussionId, UUID.randomUUID(), UUID.randomUUID(), null,
                "Comentário", false, DiscussionStatus.UNDER_REVIEW, now, now);
        when(discussionReportRepository.findAdminPage(ReportStatus.PENDING, ReportReason.HARASSMENT, discussionId, 1, 5, "desc"))
                .thenReturn(PageResult.of(List.of(report), 1, 5, 6));
        when(discussionRepository.findAllByIds(List.of(discussionId))).thenReturn(List.of(discussion));
        return report;
    }

    @Test
    @DisplayName("MODERATOR atual: filtros, paginação e visão iguais à consulta existente")
    void currentModeratorQueries() {
        currentRole(userRepository, actor, Role.MODERATOR);
        DiscussionReport report = stubPage();

        PageResult<AdminDiscussionReportView> page = useCase.execute(actor, 1, 5, ReportStatus.PENDING,
                ReportReason.HARASSMENT, discussionId, "DESC");

        assertEquals(6, page.totalElements());
        assertEquals(1, page.pageNumber());
        assertEquals(5, page.pageSize());
        AdminDiscussionReportView view = page.content().get(0);
        assertEquals(report.getId(), view.id());
        assertEquals(DiscussionStatus.UNDER_REVIEW, view.discussionStatus());
        assertEquals(report.getReporterUserId(), view.reporterUserId());
        verify(discussionReportRepository).findAdminPage(ReportStatus.PENDING, ReportReason.HARASSMENT, discussionId, 1, 5, "desc");
    }

    @Test
    @DisplayName("ADMIN atual também consulta")
    void currentAdminQueries() {
        currentRole(userRepository, actor, Role.ADMIN);
        stubPage();

        assertEquals(1, useCase.execute(actor, 1, 5, ReportStatus.PENDING, ReportReason.HARASSMENT, discussionId, "desc")
                .content().size());
    }

    @Test
    @DisplayName("Rebaixado a USER: 403 FORBIDDEN sem consultar denúncias nem comentários")
    void demotedIsForbidden() {
        currentRole(userRepository, actor, Role.USER);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> useCase.execute(actor, 0, 20, null, null, null, "asc"));

        assertEquals("FORBIDDEN", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(discussionReportRepository, discussionRepository);
    }

    @Test
    @DisplayName("Conta inoperante: 401 ACCOUNT_DISABLED sem consultar cargo, denúncias nem comentários")
    void disabledIsRejected() {
        doThrow(AccountStatusPolicy.accountDisabled()).when(accountStatusPolicy).requireOperational(actor);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> useCase.execute(actor, 0, 20, null, null, null, "asc"));

        assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
        verifyNoInteractions(userRepository, discussionReportRepository, discussionRepository);
    }
}
