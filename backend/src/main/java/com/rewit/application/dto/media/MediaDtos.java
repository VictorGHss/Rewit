package com.rewit.application.dto.media;

import com.rewit.domain.model.PlaceMedia;
import com.rewit.domain.model.ReviewMedia;

import java.time.Instant;
import java.util.UUID;

/**
 * Contratos de transferência de dados (DTOs) da camada de aplicação para gerenciamento de mídias de avaliação e locais.
 */
public final class MediaDtos {

    private MediaDtos() {}

    public record UploadMediaCommand(
            UUID reviewId,
            UUID authenticatedUserId,
            byte[] fileBytes,
            String originalFilename
    ) {}

    public record UploadPlaceMediaCommand(
            UUID placeId,
            UUID authenticatedUserId,
            byte[] fileBytes,
            String originalFilename,
            String idempotencyKey
    ) {
        public UploadPlaceMediaCommand(UUID placeId, UUID authenticatedUserId, byte[] fileBytes, String originalFilename) {
            this(placeId, authenticatedUserId, fileBytes, originalFilename, null);
        }
    }

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

    public record PlaceMediaView(
            UUID id,
            UUID placeId,
            String url,
            String mediaType,
            String mimeType,
            long sizeBytes,
            Integer width,
            Integer height,
            String status,
            boolean isPrimary,
            Instant createdAt
    ) {
        public static PlaceMediaView fromDomain(PlaceMedia media) {
            String publicUrl = "/api/v1/places/" + media.getPlaceId() + "/media/" + media.getId();
            return new PlaceMediaView(
                    media.getId(),
                    media.getPlaceId(),
                    publicUrl,
                    media.getMediaType().name(),
                    media.getMimeType(),
                    media.getSizeBytes(),
                    media.getWidth(),
                    media.getHeight(),
                    media.getStatus().name(),
                    media.isPrimary(),
                    media.getCreatedAt()
            );
        }
    }

    /**
     * @param publiclyCacheable a avaliação é {@code PUBLIC}: a mídia pode ser guardada por caches compartilhados.
     *                          {@code PRIVATE}/{@code FOLLOWERS} dependem do leitor e nunca podem ser
     */
    public record MediaDownloadResult(
            byte[] bytes,
            String mimeType,
            boolean publiclyCacheable
    ) {}
}
