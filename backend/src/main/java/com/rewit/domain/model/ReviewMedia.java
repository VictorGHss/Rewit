package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.enums.ReviewMediaStatus;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade de Domínio representando um anexo de mídia a uma avaliação.
 * Atende ao Step 21.0 — Media de Reviews.
 */
public class ReviewMedia {

    public static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB
    public static final int MAX_DIMENSION = 10000; // 10.000 pixels
    public static final int MAX_MEDIA_PER_REVIEW = 5;

    private final UUID id;
    private final UUID reviewId;
    private final UUID userId;
    private final String objectKey;
    private final ReviewMediaType mediaType;
    private final String mimeType;
    private final long sizeBytes;
    private final Integer width;
    private final Integer height;
    private ReviewMediaStatus status;
    private final Instant createdAt;
    private Instant updatedAt;

    public ReviewMedia(UUID id, UUID reviewId, UUID userId, String objectKey,
                       ReviewMediaType mediaType, String mimeType, long sizeBytes,
                       Integer width, Integer height) {
        this(id, reviewId, userId, objectKey, mediaType, mimeType, sizeBytes, width, height,
                ReviewMediaStatus.ACTIVE, Instant.now(), Instant.now());
    }

    public ReviewMedia(UUID id, UUID reviewId, UUID userId, String objectKey,
                       ReviewMediaType mediaType, String mimeType, long sizeBytes,
                       Integer width, Integer height, ReviewMediaStatus status,
                       Instant createdAt, Instant updatedAt) {
        if (reviewId == null) {
            throw new BusinessException("A avaliação é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_REVIEW_ID");
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
        if (width != null && (width <= 0 || width > MAX_DIMENSION)) {
            throw new BusinessException(
                    "A largura da imagem deve estar entre 1 e " + MAX_DIMENSION + " pixels",
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IMAGE_DIMENSIONS"
            );
        }
        if (height != null && (height <= 0 || height > MAX_DIMENSION)) {
            throw new BusinessException(
                    "A altura da imagem deve estar entre 1 e " + MAX_DIMENSION + " pixels",
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IMAGE_DIMENSIONS"
            );
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.reviewId = reviewId;
        this.userId = userId;
        this.objectKey = objectKey.trim();
        this.mediaType = mediaType;
        this.mimeType = mimeType.trim().toLowerCase();
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
        this.status = status != null ? status : ReviewMediaStatus.ACTIVE;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    public void markRemoved() {
        this.status = ReviewMediaStatus.REMOVED;
        this.updatedAt = Instant.now();
    }

    public boolean isActive() {
        return this.status == ReviewMediaStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReviewId() {
        return reviewId;
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
        ReviewMedia that = (ReviewMedia) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
