package com.rewit.application.dto.media;

import com.rewit.application.dto.media.MediaDtos.PlaceMediaView;
import com.rewit.domain.model.PlaceMedia;
import com.rewit.domain.model.PlaceMediaReport;

import java.time.Instant;
import java.util.UUID;

/**
 * DTOs da camada de aplicação para operações de moderação de mídias de locais.
 * Permite isolamento estrito contra dependências da camada presentation.
 */
public final class PlaceMediaModerationDtos {

    private PlaceMediaModerationDtos() {}

    public record PlaceMediaReportView(
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
            PlaceMediaView media
    ) {
        public static PlaceMediaReportView of(PlaceMediaReport report, PlaceMedia media) {
            return new PlaceMediaReportView(
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
                    media != null ? PlaceMediaView.fromDomain(media) : null
            );
        }
    }

    public record PlaceMediaModerationResult(
            UUID mediaId,
            UUID placeId,
            String status,
            boolean isPrimary,
            String message
    ) {}
}

