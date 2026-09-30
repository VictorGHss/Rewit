package com.rewit.presentation.dto.report;

import com.rewit.domain.model.Report;

import java.time.Instant;
import java.util.UUID;

/**
 * Resposta da criação de denúncia comunitária.
 * Nunca expõe o ID, e-mail ou dados de identidade do denunciante para preservar sua privacidade.
 */
public record ReportResponse(
        UUID id,
        UUID reviewId,
        String reason,
        String status,
        Instant createdAt
) {
    public static ReportResponse fromDomain(Report report) {
        if (report == null) {
            return null;
        }
        return new ReportResponse(
                report.getId(),
                report.getReviewId(),
                report.getReason().name(),
                report.getStatus().name(),
                report.getCreatedAt()
        );
    }
}
