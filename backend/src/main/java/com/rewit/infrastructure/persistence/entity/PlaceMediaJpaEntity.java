package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.ReviewMediaStatus;
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
 * Entidade JPA mapeando a tabela place_media (V30).
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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PlaceMediaJpaEntity() {
    }

    public PlaceMediaJpaEntity(UUID id, UUID placeId, UUID userId, String objectKey,
                               String mediaType, String mimeType, Long sizeBytes,
                               Integer width, Integer height, String status,
                               Instant createdAt, Instant updatedAt) {
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
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
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
                domain.getCreatedAt(),
                domain.getUpdatedAt()
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
        PlaceMediaJpaEntity that = (PlaceMediaJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}

