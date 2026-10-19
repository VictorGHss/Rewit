package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.enums.ReviewMediaType;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade de Domínio representando uma fotografia vinculada diretamente a um local (Place).
 */
public class PlaceMedia {

    public static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB
    public static final int MAX_DIMENSION = 10000; // 10.000 pixels
    public static final int MAX_MEDIA_PER_PLACE = 5;

    private final UUID id;
    private final UUID placeId;
    private final UUID userId;
    private final String objectKey;
    private final ReviewMediaType mediaType;
    private final String mimeType;
    private final long sizeBytes;
    private final Integer width;
    private final Integer height;
    private ReviewMediaStatus status;
    private boolean isPrimary;
    private final String idempotencyKey;
    private final String contentSha256;
    private final Instant createdAt;
    private Instant updatedAt;

    public PlaceMedia(UUID id, UUID placeId, UUID userId, String objectKey,
                      ReviewMediaType mediaType, String mimeType, long sizeBytes,
                      Integer width, Integer height) {
        this(id, placeId, userId, objectKey, mediaType, mimeType, sizeBytes, width, height,
                ReviewMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now());
    }

    public PlaceMedia(UUID id, UUID placeId, UUID userId, String objectKey,
                      ReviewMediaType mediaType, String mimeType, long sizeBytes,
                      Integer width, Integer height, ReviewMediaStatus status,
                      Instant createdAt, Instant updatedAt) {
        this(id, placeId, userId, objectKey, mediaType, mimeType, sizeBytes, width, height,
                status, false, null, null, createdAt, updatedAt);
    }

    public PlaceMedia(UUID id, UUID placeId, UUID userId, String objectKey,
                      ReviewMediaType mediaType, String mimeType, long sizeBytes,
                      Integer width, Integer height, ReviewMediaStatus status,
                      boolean isPrimary, String idempotencyKey, String contentSha256,
                      Instant createdAt, Instant updatedAt) {
        if (placeId == null) {
            throw new BusinessException("O local é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_PLACE_ID");
        }
        if (userId == null) {
            throw new BusinessException("O usuário autor da mídia é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_USER_ID");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException("A chave do objeto de armazenamento é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_OBJECT_KEY");
        }
        if (mediaType == null) {
            throw new BusinessException("O tipo de mídia é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_MEDIA_TYPE");
        }
        if (mimeType == null || mimeType.isBlank()) {
            throw new BusinessException("O MIME type é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_MIME_TYPE");
        }
        if (sizeBytes <= 0) {
            throw new BusinessException("O arquivo de mídia não pode ser vazio", HttpStatus.BAD_REQUEST, "EMPTY_MEDIA_FILE");
        }
        if (sizeBytes > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(
                    "O tamanho do arquivo excede o limite máximo permitido de 10 MB",
                    HttpStatus.CONTENT_TOO_LARGE,
                    "MEDIA_SIZE_EXCEEDED"
            );
        }
        if (width == null || width <= 0 || width > MAX_DIMENSION) {
            throw new BusinessException(
                    "A largura da imagem deve estar entre 1 e " + MAX_DIMENSION + " pixels",
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IMAGE_DIMENSIONS"
            );
        }
        if (height == null || height <= 0 || height > MAX_DIMENSION) {
            throw new BusinessException(
                    "A altura da imagem deve estar entre 1 e " + MAX_DIMENSION + " pixels",
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IMAGE_DIMENSIONS"
            );
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.placeId = placeId;
        this.userId = userId;
        this.objectKey = objectKey.trim();
        this.mediaType = mediaType;
        this.mimeType = mimeType.trim().toLowerCase();
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
        this.status = status != null ? status : ReviewMediaStatus.ACTIVE;
        this.isPrimary = isPrimary;
        this.idempotencyKey = idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : null;
        this.contentSha256 = contentSha256 != null && !contentSha256.isBlank() ? contentSha256.trim().toLowerCase() : null;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    public static String buildObjectKey(UUID placeId, UUID mediaId, String mimeType) {
        Objects.requireNonNull(placeId, "placeId must not be null");
        Objects.requireNonNull(mediaId, "mediaId must not be null");
        String extension = "image/png".equalsIgnoreCase(mimeType) ? "png" : "jpg";
        return String.format("places/%s/%s/image.%s", placeId, mediaId, extension);
    }

    public void markRemoved() {
        this.status = ReviewMediaStatus.REMOVED;
        this.isPrimary = false;
        this.updatedAt = Instant.now();
    }

    public void markUnderReview() {
        this.status = ReviewMediaStatus.UNDER_REVIEW;
        this.isPrimary = false;
        this.updatedAt = Instant.now();
    }

    public void markFlagged() {
        this.status = ReviewMediaStatus.FLAGGED;
        this.updatedAt = Instant.now();
    }

    public boolean isActive() {
        return this.status == ReviewMediaStatus.ACTIVE;
    }

    public boolean isPrimary() {
        return this.isPrimary;
    }

    public void setPrimary(boolean isPrimary) {
        this.isPrimary = isPrimary;
        this.updatedAt = Instant.now();
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public ReviewMediaType getMediaType() {
        return mediaType;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public ReviewMediaStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlaceMedia that = (PlaceMedia) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}

