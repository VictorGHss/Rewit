package com.rewit.application.usecase;

import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.AdminDiscussionContextView;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.AdminDiscussionView;
import com.rewit.application.port.DiscussionModerationAuditLogRepository;
import com.rewit.application.port.DiscussionReportRepository;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.application.service.ModeratorRolePolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.DiscussionReport;
import com.rewit.domain.model.ReviewDiscussion;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Contexto administrativo de uma discussão: conteúdo e status interno (inclusive se removida), comentário pai,
 * todas as denúncias e o histórico de moderação.
 */
@Service
public class GetAdminDiscussionContextUseCase {

    private final DiscussionRepository discussionRepository;
    private final DiscussionReportRepository discussionReportRepository;
    private final DiscussionModerationAuditLogRepository auditLogRepository;
    private final ModeratorRolePolicy moderatorRolePolicy;

    public GetAdminDiscussionContextUseCase(DiscussionRepository discussionRepository,
                                            DiscussionReportRepository discussionReportRepository,
                                            DiscussionModerationAuditLogRepository auditLogRepository,
                                            ModeratorRolePolicy moderatorRolePolicy) {
        this.discussionRepository = Objects.requireNonNull(discussionRepository, "DiscussionRepository must not be null");
        this.discussionReportRepository = Objects.requireNonNull(discussionReportRepository, "DiscussionReportRepository must not be null");
        this.auditLogRepository = Objects.requireNonNull(auditLogRepository, "DiscussionModerationAuditLogRepository must not be null");
        this.moderatorRolePolicy = Objects.requireNonNull(moderatorRolePolicy, "ModeratorRolePolicy must not be null");
    }

    /**
     * Contexto do comentário com revalidação do cargo atual do ator (ModeratorRolePolicy) antes de qualquer consulta:
     * expõe conteúdo, autores, denunciantes e moderadores, e o JWT pode carregar uma role revogada.
     */
    @Transactional(readOnly = true)
    public AdminDiscussionContextView execute(UUID actorUserId, UUID discussionId) {
        moderatorRolePolicy.requireCurrentModerator(actorUserId);
        return load(discussionId);
    }

    /** Carga do contexto propriamente dita; só alcançável depois da revalidação do cargo do ator. */
    private AdminDiscussionContextView load(UUID discussionId) {
        if (discussionId == null) {
            throw new BusinessException("Identificador do comentário é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_DISCUSSION_ID");
        }
        ReviewDiscussion discussion = discussionRepository.findById(discussionId)
                .orElseThrow(() -> new BusinessException("Comentário não encontrado", HttpStatus.NOT_FOUND, "DISCUSSION_NOT_FOUND"));
        AdminDiscussionView parent = discussion.getParentId() == null ? null
                : discussionRepository.findById(discussion.getParentId()).map(AdminDiscussionView::fromDomain).orElse(null);

        List<DiscussionReport> reports = discussionReportRepository.findByDiscussionId(discussionId);
        long pending = reports.stream().filter(report -> report.isPending()).count();

        return new AdminDiscussionContextView(
                AdminDiscussionView.fromDomain(discussion),
                parent,
                pending,
                reports,
                auditLogRepository.findByDiscussionId(discussionId)
        );
    }
}
