package com.rewit.application.dto.discussion;

import com.rewit.domain.enums.ReportReason;

import java.util.UUID;

/**
 * Comandos e resultados das denúncias de discussões.
 */
public final class DiscussionReportDtos {

    private DiscussionReportDtos() {
    }

    public record ReportDiscussionCommand(
            UUID reporterUserId,
            UUID discussionId,
            ReportReason reason,
            String detail
    ) {}

    /**
     * Resultado interno. A camada HTTP devolve sempre a mesma confirmação genérica: o denunciante não descobre
     * se a denúncia era repetida nem se o comentário entrou em análise.
     */
    public record ReportDiscussionResult(UUID reportId, boolean newlyCreated) {}
}
