package com.rewit.application.dto.report;

import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.model.Report;

import java.time.Instant;
import java.util.UUID;

/**
 * DTOs da camada de aplicação para o subsistema de Denúncias e Moderação Preventiva.
 */
public final class ReportDtos {

    private ReportDtos() {}

    public record CreateReportCommand(
            UUID reporterUserId,
            UUID reviewId,
            ReportReason reason,
            String detail
    ) {}

    public record ReportView(
            UUID id,
            UUID reviewId,
            ReportReason reason,
            ReportStatus status,
            Instant createdAt
    ) {
        public static ReportView fromDomain(Report report) {
            return new ReportView(
                    report.getId(),
                    report.getReviewId(),
                    report.getReason(),
                    report.getStatus(),
                    report.getCreatedAt()
            );
        }
    }
}
