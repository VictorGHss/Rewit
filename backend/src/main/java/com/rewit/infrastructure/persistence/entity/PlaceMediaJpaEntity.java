package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.PlaceMediaStatus;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.model.PlaceMedia;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela place_media (V30 / V31).
 */
@Entity
@Table(name = "place_media")
public class PlaceMediaJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "place_id", nullable = false, updatable = false)
    private UUID placeId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "object_key", nullable = false, unique = true)
    private String objectKey;

    @Column(name = "media_type", nullable = false, length = 20)
    private String mediaType;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "width", nullable = false)
    private Integer width;

    @Column(name = "height", nullable = false)
    private Integer height;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "is_primary", nullable = false)
    private Boolean isPrimary = false;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "content_sha256", length = 64)
    private String contentSha256;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "purged_at")
    private Instant purgedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    public PlaceMediaJpaEntity() {
    }

    public PlaceMediaJpaEntity(UUID id, UUID placeId, UUID userId, String objectKey,
                               String mediaType, String mimeType, Long sizeBytes,
                               Integer width, Integer height, String status,
                               Boolean isPrimary, String idempotencyKey, String contentSha256,
                               Instant createdAt, Instant updatedAt) {
        this(id, placeId, userId, objectKey, mediaType, mimeType, sizeBytes, width, height,
                status, isPrimary, idempotencyKey, contentSha256, createdAt, updatedAt, null, null);
    }

    public PlaceMediaJpaEntity(UUID id, UUID placeId, UUID userId, String objectKey,
                               String mediaType, String mimeType, Long sizeBytes,
                               Integer width, Integer height, String status,
                               Boolean isPrimary, String idempotencyKey, String contentSha256,
                               Instant createdAt, Instant updatedAt, Instant purgedAt) {
        this(id, placeId, userId, objectKey, mediaType, mimeType, sizeBytes, width, height,
                status, isPrimary, idempotencyKey, contentSha256, createdAt, updatedAt, purgedAt, null);
    }

    public PlaceMediaJpaEntity(UUID id, UUID placeId, UUID userId, String objectKey,
                               String mediaType, String mimeType, Long sizeBytes,
                               Integer width, Integer height, String status,
                               Boolean isPrimary, String idempotencyKey, String contentSha256,
                               Instant createdAt, Instant updatedAt, Instant purgedAt, Instant removedAt) {
        this.id = id;
        this.placeId = placeId;
        this.userId = userId;
        this.objectKey = objectKey;
        this.mediaType = mediaType;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
        this.status = status;
        this.isPrimary = isPrimary != null ? isPrimary : false;
        this.idempotencyKey = idempotencyKey;
        this.contentSha256 = contentSha256;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.purgedAt = purgedAt;
        this.removedAt = removedAt;
    }

    public static PlaceMediaJpaEntity fromDomain(PlaceMedia domain) {
        return new PlaceMediaJpaEntity(
                domain.getId(),
                domain.getPlaceId(),
                domain.getUserId(),
                domain.getObjectKey(),
                domain.getMediaType().name(),
                domain.getMimeType(),
                domain.getSizeBytes(),
                domain.getWidth(),
                domain.getHeight(),
                domain.getStatus().name(),
                domain.isPrimary(),
                domain.getIdempotencyKey(),
                domain.getContentSha256(),
                domain.getCreatedAt(),
                domain.getUpdatedAt(),
                domain.getPurgedAt(),
                domain.getRemovedAt()
        );
    }

    public PlaceMedia toDomain() {
        Long persistedSizeBytes = this.sizeBytes;
        if (persistedSizeBytes == null) {
            throw new IllegalStateException("place_media.size_bytes nulo para a mídia " + this.id);
        }
        return new PlaceMedia(
                this.id,
                this.placeId,
                this.userId,
                this.objectKey,
                ReviewMediaType.valueOf(this.mediaType),
                this.mimeType,
                persistedSizeBytes,
                this.width,
                this.height,
                PlaceMediaStatus.valueOf(this.status),
                this.isPrimary != null && this.isPrimary,
                this.idempotencyKey,
                this.contentSha256,
                this.createdAt,
                this.updatedAt,
                this.purgedAt,
                this.removedAt
        );
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public void setPlaceId(UUID placeId) {
        this.placeId = placeId;
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

    public Boolean getIsPrimary() {
        return isPrimary;
    }

    public void setIsPrimary(Boolean primary) {
        isPrimary = primary != null ? primary : false;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public void setContentSha256(String contentSha256) {
        this.contentSha256 = contentSha256;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getPurgedAt() {
        return purgedAt;
    }

    public void setPurgedAt(Instant purgedAt) {
        this.purgedAt = purgedAt;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }

    public void setRemovedAt(Instant removedAt) {
        this.removedAt = removedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlaceMediaJpaEntity that = (PlaceMediaJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}

