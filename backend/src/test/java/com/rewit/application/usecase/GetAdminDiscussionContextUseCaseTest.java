package com.rewit.application.usecase;

import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.AdminDiscussionContextView;
import com.rewit.application.port.DiscussionModerationAuditLogRepository;
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
import java.util.Optional;
import java.util.UUID;

import static com.rewit.application.usecase.CurrentRoleStubs.currentRole;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetAdminDiscussionContextUseCase: revalidação do cargo atual antes do contexto")
class GetAdminDiscussionContextUseCaseTest {

    @Mock private DiscussionRepository discussionRepository;
    @Mock private DiscussionReportRepository discussionReportRepository;
    @Mock private DiscussionModerationAuditLogRepository auditLogRepository;
    @Mock private AccountStatusPolicy accountStatusPolicy;
    @Mock private UserRepository userRepository;

    private GetAdminDiscussionContextUseCase useCase;

    private final UUID actor = UUID.randomUUID();
    private final UUID parentId = UUID.randomUUID();
    private final UUID replyId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-10T12:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GetAdminDiscussionContextUseCase(discussionRepository, discussionReportRepository, auditLogRepository,
                new ModeratorRolePolicy(accountStatusPolicy, userRepository));
    }

    /** Resposta em análise com o comentário pai, uma denúncia pendente e outra rejeitada. */
    private void stubContext() {
        UUID reviewId = UUID.randomUUID();
        when(discussionRepository.findById(replyId)).thenReturn(Optional.of(new ReviewDiscussion(replyId, reviewId,
                UUID.randomUUID(), parentId, "Resposta", false, DiscussionStatus.UNDER_REVIEW, now, now)));
        when(discussionRepository.findById(parentId)).thenReturn(Optional.of(new ReviewDiscussion(parentId, reviewId,
                UUID.randomUUID(), null, "Raiz", false, DiscussionStatus.ACTIVE, now, now)));
        when(discussionReportRepository.findByDiscussionId(replyId)).thenReturn(List.of(
                new DiscussionReport(UUID.randomUUID(), replyId, UUID.randomUUID(), ReportReason.SPAM, null,
                        ReportStatus.PENDING, now, now),
                new DiscussionReport(UUID.randomUUID(), replyId, UUID.randomUUID(), ReportReason.SPAM, null,
                        ReportStatus.REJECTED, now, now)));
        when(auditLogRepository.findByDiscussionId(replyId)).thenReturn(List.of());
    }

    @Test
    @DisplayName("MODERATOR atual: contexto igual ao existente (comentário, pai, pendentes, denúncias)")
    void currentModeratorReadsContext() {
        currentRole(userRepository, actor, Role.MODERATOR);
        stubContext();

        AdminDiscussionContextView view = useCase.execute(actor, replyId);

        assertEquals(replyId, view.discussion().id());
        assertEquals(parentId, view.parent().id());
        assertEquals(1, view.pendingReportCount());
        assertEquals(2, view.reports().size());
    }

    @Test
    @DisplayName("ADMIN atual também lê o contexto")
    void currentAdminReadsContext() {
        currentRole(userRepository, actor, Role.ADMIN);
        stubContext();

        assertEquals(replyId, useCase.execute(actor, replyId).discussion().id());
    }

    @Test
    @DisplayName("Rebaixado a USER: 403 FORBIDDEN sem consultar comentário, denúncias nem auditoria")
    void demotedIsForbidden() {
        currentRole(userRepository, actor, Role.USER);

        BusinessException ex = assertThrows(BusinessException.class, () -> useCase.execute(actor, replyId));

        assertEquals("FORBIDDEN", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(discussionRepository, discussionReportRepository, auditLogRepository);
    }

    @Test
    @DisplayName("Conta inoperante: 401 ACCOUNT_DISABLED sem consultar cargo, comentário, denúncias nem auditoria")
    void disabledIsRejected() {
        doThrow(AccountStatusPolicy.accountDisabled()).when(accountStatusPolicy).requireOperational(actor);

        BusinessException ex = assertThrows(BusinessException.class, () -> useCase.execute(actor, replyId));

        assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
        verifyNoInteractions(userRepository, discussionRepository, discussionReportRepository, auditLogRepository);
    }

    @Test
    @DisplayName("Variante transitória sem ator: comportamento existente, sem revalidação")
    void legacyVariantUnchanged() {
        stubContext();

        assertEquals(parentId, useCase.execute(replyId).parent().id());
        verifyNoInteractions(accountStatusPolicy, userRepository);
    }
}
