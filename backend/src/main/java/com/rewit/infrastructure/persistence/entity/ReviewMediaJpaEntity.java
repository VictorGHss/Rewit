package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.model.ReviewMedia;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela review_media (Step 21.0).
 */
@Entity
@Table(name = "review_media")
public class ReviewMediaJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "review_id", nullable = false, updatable = false)
    private UUID reviewId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "object_key", nullable = false, unique = true)
    private String objectKey;

    @Column(name = "media_type", nullable = false, length = 32)
    private String mediaType;

    @Column(name = "mime_type", nullable = false, length = 64)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ReviewMediaJpaEntity() {}

    public ReviewMediaJpaEntity(UUID id, UUID reviewId, UUID userId, String objectKey,
                                String mediaType, String mimeType, Long sizeBytes,
                                Integer width, Integer height, String status,
                                Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.reviewId = reviewId;
        this.userId = userId;
        this.objectKey = objectKey;
        this.mediaType = mediaType;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static ReviewMediaJpaEntity fromDomain(ReviewMedia domain) {
        return new ReviewMediaJpaEntity(
                domain.getId(),
                domain.getReviewId(),
                domain.getUserId(),
                domain.getObjectKey(),
                domain.getMediaType().name(),
                domain.getMimeType(),
                domain.getSizeBytes(),
                domain.getWidth(),
                domain.getHeight(),
                domain.getStatus().name(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    public ReviewMedia toDomain() {
        return new ReviewMedia(
                this.id,
                this.reviewId,
                this.userId,
                this.objectKey,
                ReviewMediaType.valueOf(this.mediaType),
                this.mimeType,
                this.sizeBytes != null ? this.sizeBytes : 0L,
                this.width,
                this.height,
                ReviewMediaStatus.valueOf(this.status),
                this.createdAt,
                this.updatedAt
        );
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public void setReviewId(UUID reviewId) {
        this.reviewId = reviewId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReviewMediaJpaEntity that = (ReviewMediaJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
