package com.rewit.presentation.dto.admin;

import com.rewit.application.dto.media.MediaDtos.PlaceMediaView;
import com.rewit.application.dto.media.PlaceMediaModerationDtos.PlaceMediaModerationResult;
import com.rewit.application.dto.media.PlaceMediaModerationDtos.PlaceMediaReportView;
import com.rewit.domain.model.PlaceMedia;
import com.rewit.domain.model.PlaceMediaReport;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * DTOs HTTP da moderação administrativa de fotografias de locais (MODERATOR/ADMIN).
 */
public final class AdminPlaceMediaModerationDtos {

    private AdminPlaceMediaModerationDtos() {
    }

    public record PlaceMediaSummaryResponse(
            UUID id,
            UUID placeId,
            UUID userId,
            String url,
            String mimeType,
            long sizeBytes,
            boolean isPrimary,
            String status,
            Instant createdAt
    ) {
        public static PlaceMediaSummaryResponse fromDomain(PlaceMedia media) {
            if (media == null) return null;
            return new PlaceMediaSummaryResponse(
                    media.getId(),
                    media.getPlaceId(),
                    media.getUserId(),
                    "/api/v1/admin/place-media/" + media.getPlaceId() + "/" + media.getId() + "/content",
                    media.getMimeType(),
                    media.getSizeBytes(),
                    media.isPrimary(),
                    media.getStatus() != null ? media.getStatus().name() : "ACTIVE",
                    media.getCreatedAt()
            );
        }

        public static PlaceMediaSummaryResponse fromView(PlaceMediaView view) {
            if (view == null) return null;
            return new PlaceMediaSummaryResponse(
                    view.id(),
                    view.placeId(),
                    null,
                    "/api/v1/admin/place-media/" + view.placeId() + "/" + view.id() + "/content",
                    view.mimeType(),
                    view.sizeBytes(),
                    view.isPrimary(),
                    view.status(),
                    view.createdAt()
            );
        }
    }

    public record AdminPlaceMediaReportResponse(
            UUID id,
            UUID placeMediaId,
            UUID reporterUserId,
            String reason,
            String detail,
            String status,
            Instant createdAt,
            Instant updatedAt,
            UUID decidedByUserId,
            Instant decidedAt,
            String decisionNotes,
            PlaceMediaSummaryResponse media
    ) {
        public static AdminPlaceMediaReportResponse of(PlaceMediaReport report, PlaceMedia media) {
            return new AdminPlaceMediaReportResponse(
                    report.getId(),
                    report.getPlaceMediaId(),
                    report.getReporterUserId(),
                    report.getReason(),
                    report.getDetail(),
                    report.getStatus(),
                    report.getCreatedAt(),
                    report.getUpdatedAt(),
                    report.getDecidedByUserId(),
                    report.getDecidedAt(),
                    report.getDecisionNotes(),
                    PlaceMediaSummaryResponse.fromDomain(media)
            );
        }

        public static AdminPlaceMediaReportResponse fromView(PlaceMediaReportView view) {
            if (view == null) return null;
            return new AdminPlaceMediaReportResponse(
                    view.id(),
                    view.placeMediaId(),
                    view.reporterUserId(),
                    view.reason(),
                    view.detail(),
                    view.status(),
                    view.createdAt(),
                    view.updatedAt(),
                    view.decidedByUserId(),
                    view.decidedAt(),
                    view.decisionNotes(),
                    PlaceMediaSummaryResponse.fromView(view.media())
            );
        }
    }

    public record AdminPlaceMediaUnderReviewResponse(
            PlaceMediaSummaryResponse media,
            long pendingReportsCount,
            java.util.List<AdminPlaceMediaReportResponse> reports
    ) {
        public static AdminPlaceMediaUnderReviewResponse fromView(com.rewit.application.dto.media.PlaceMediaModerationDtos.PlaceMediaUnderReviewView view) {
            if (view == null) return null;
            java.util.List<AdminPlaceMediaReportResponse> reportResponses = view.reports() != null
                    ? view.reports().stream().map(r -> AdminPlaceMediaReportResponse.of(r, view.media())).toList()
                    : java.util.List.of();
            return new AdminPlaceMediaUnderReviewResponse(
                    PlaceMediaSummaryResponse.fromDomain(view.media()),
                    view.pendingReportsCount(),
                    reportResponses
            );
        }
    }

    public record DecidePlaceMediaReportRequest(
            @NotBlank(message = "A decisão é obrigatória (ACCEPT ou REJECT)")
            String decision,

            @Size(max = 500, message = "As notas da decisão não podem exceder 500 caracteres")
            String notes
    ) {}

    public record RemovePlaceMediaRequest(
            @Size(max = 64, message = "O motivo não pode exceder 64 caracteres")
            String reason,

            @Size(max = 500, message = "As notas de moderação não podem exceder 500 caracteres")
            String notes
    ) {}

    public record RestorePlaceMediaRequest(
            @Size(max = 500, message = "As notas de moderação não podem exceder 500 caracteres")
            String notes
    ) {}

    public record PlaceMediaModerationActionResponse(
            UUID mediaId,
            UUID placeId,
            String status,
            boolean isPrimary,
            String message
    ) {
        public static PlaceMediaModerationActionResponse fromResult(PlaceMediaModerationResult result) {
            if (result == null) return null;
            return new PlaceMediaModerationActionResponse(
                    result.mediaId(),
                    result.placeId(),
                    result.status(),
                    result.isPrimary(),
                    result.message()
            );
        }
    }
}
