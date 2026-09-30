package com.rewit.application.dto.media;

import com.rewit.domain.model.ReviewMedia;

import java.time.Instant;
import java.util.UUID;

/**
 * Contratos de transferência de dados (DTOs) da camada de aplicação para gerenciamento de mídias de avaliação.
 */
public final class MediaDtos {

    private MediaDtos() {}

    public record UploadMediaCommand(
            UUID reviewId,
            UUID authenticatedUserId,
            byte[] fileBytes,
            String originalFilename
    ) {}

    public record ReviewMediaView(
            UUID id,
            UUID reviewId,
            String url,
            String mediaType,
            String mimeType,
            long sizeBytes,
            Integer width,
            Integer height,
            String status,
            Instant createdAt
    ) {
        public static ReviewMediaView fromDomain(ReviewMedia media) {
            String publicUrl = "/api/v1/reviews/" + media.getReviewId() + "/media/" + media.getId();
            return new ReviewMediaView(
                    media.getId(),
                    media.getReviewId(),
                    publicUrl,
                    media.getMediaType().name(),
                    media.getMimeType(),
                    media.getSizeBytes(),
                    media.getWidth(),
                    media.getHeight(),
                    media.getStatus().name(),
                    media.getCreatedAt()
            );
        }
    }

    public record MediaDownloadResult(
            byte[] bytes,
            String mimeType
    ) {}
}
