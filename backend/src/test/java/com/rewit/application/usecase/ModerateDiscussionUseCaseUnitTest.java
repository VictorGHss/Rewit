package com.rewit.application.usecase;

import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.ModerateDiscussionCommand;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.ModerateDiscussionResult;
import com.rewit.application.port.DiscussionModerationAuditLogRepository;
import com.rewit.application.port.DiscussionReportRepository;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.AccountStatusPolicy;
import com.rewit.application.service.ModeratorRolePolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AccountStatus;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.DiscussionModerationAction;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.enums.Role;
import com.rewit.domain.model.ReviewDiscussion;
import com.rewit.domain.model.User;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ModerateDiscussionUseCase: revalidação do cargo atual do moderador (ModeratorRolePolicy real; só a conta e o
 * usuário são simulados) antes de qualquer lock, leitura ou escrita.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModerateDiscussionUseCase: cargo atual do moderador")
class ModerateDiscussionUseCaseUnitTest {

    @Mock private DiscussionRepository discussionRepository;
    @Mock private DiscussionReportRepository discussionReportRepository;
    @Mock private DiscussionModerationAuditLogRepository auditLogRepository;
    @Mock private AccountStatusPolicy accountStatusPolicy;
    @Mock private UserRepository userRepository;

    private ModerateDiscussionUseCase useCase;

    private final UUID moderatorId = UUID.randomUUID();
    private final UUID discussionId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-10T12:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new ModerateDiscussionUseCase(discussionRepository, discussionReportRepository, auditLogRepository,
                new ModeratorRolePolicy(accountStatusPolicy, userRepository));
    }

    private ModerateDiscussionCommand command(DiscussionModerationAction action) {
        return new ModerateDiscussionCommand(discussionId, moderatorId, action, "SPAM_CONFIRMED",
                "Justificativa administrativa suficiente.", now);
    }

    private void currentRole(Role role) {
        when(userRepository.findById(moderatorId)).thenReturn(Optional.of(User.rehydrate(moderatorId, "mod@rewit.test",
                "hash", AuthProvider.LOCAL, null, AccountStatus.ACTIVE, true, role, null, now, now)));
    }

    private ReviewDiscussion underReview() {
        ReviewDiscussion discussion = new ReviewDiscussion(discussionId, UUID.randomUUID(), UUID.randomUUID(), null,
                "Comentário denunciado", false, DiscussionStatus.UNDER_REVIEW, now.minusSeconds(60), now.minusSeconds(60));
        when(discussionRepository.findByIdForUpdate(discussionId)).thenReturn(Optional.of(discussion));
        when(discussionReportRepository.findPendingByDiscussionId(discussionId)).thenReturn(List.of());
        when(discussionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(auditLogRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return discussion;
    }

    @Test
    @DisplayName("Cargo atual MODERATOR remove o comentário em análise")
    void currentModeratorCanModerate() {
        currentRole(Role.MODERATOR);
        underReview();

        ModerateDiscussionResult result = useCase.execute(command(DiscussionModerationAction.REMOVE_DISCUSSION));

        assertEquals(DiscussionStatus.REMOVED, result.discussion().getStatus());
        assertEquals(moderatorId, result.auditLog().getModeratorUserId());
    }

    @Test
    @DisplayName("Cargo atual ADMIN restaura o comentário em análise")
    void currentAdminCanModerate() {
        currentRole(Role.ADMIN);
        underReview();

        ModerateDiscussionResult result = useCase.execute(command(DiscussionModerationAction.RESTORE_DISCUSSION));

        assertEquals(DiscussionStatus.ACTIVE, result.discussion().getStatus());
        verify(auditLogRepository).save(any());
    }

    @Test
    @DisplayName("Rebaixado a USER no banco: 403 FORBIDDEN antes de qualquer lock, leitura ou escrita")
    void demotedModeratorIsRejectedBeforeAnySideEffect() {
        currentRole(Role.USER);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> useCase.execute(command(DiscussionModerationAction.REMOVE_DISCUSSION)));

        assertEquals("FORBIDDEN", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(discussionRepository, discussionReportRepository, auditLogRepository);
    }

    @Test
    @DisplayName("Conta inoperante: 401 ACCOUNT_DISABLED antes da role e de qualquer efeito")
    void disabledAccountIsRejectedBeforeRoleCheck() {
        doThrow(AccountStatusPolicy.accountDisabled()).when(accountStatusPolicy).requireOperational(moderatorId);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> useCase.execute(command(DiscussionModerationAction.REMOVE_DISCUSSION)));

        assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verifyNoInteractions(userRepository, discussionRepository, discussionReportRepository, auditLogRepository);
    }
}
