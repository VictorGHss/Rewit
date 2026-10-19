package com.rewit.presentation.dto.admin;

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
                    "/api/v1/places/" + media.getPlaceId() + "/media/" + media.getId() + "/download",
                    media.getMimeType(),
                    media.getSizeBytes(),
                    media.isPrimary(),
                    media.getStatus() != null ? media.getStatus().name() : "ACTIVE",
                    media.getCreatedAt()
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
    ) {}
}

