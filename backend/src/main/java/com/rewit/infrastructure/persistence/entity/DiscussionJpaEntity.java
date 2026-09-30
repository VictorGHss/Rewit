package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.model.ReviewDiscussion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela review_discussions no PostgreSQL (Step 20.0).
 */
@Entity
@Table(name = "review_discussions")
public class DiscussionJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "review_id", nullable = false, updatable = false)
    private UUID reviewId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "is_from_owner", nullable = false)
    private boolean isFromOwner;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public DiscussionJpaEntity() {}

    public DiscussionJpaEntity(UUID id, UUID reviewId, UUID userId, UUID parentId,
                               String content, boolean isFromOwner, String status,
                               Instant createdAt, Instant updatedAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.reviewId = reviewId;
        this.userId = userId;
        this.parentId = parentId;
        this.content = content;
        this.isFromOwner = isFromOwner;
        this.status = status != null ? status : "ACTIVE";
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    public static DiscussionJpaEntity fromDomain(ReviewDiscussion domain) {
        if (domain == null) {
            return null;
        }
        return new DiscussionJpaEntity(
                domain.getId(),
                domain.getReviewId(),
                domain.getUserId(),
                domain.getParentId(),
                domain.getContent(),
                domain.isFromOwner(),
                domain.getStatus(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    public ReviewDiscussion toDomain() {
        return new ReviewDiscussion(
                this.id,
                this.reviewId,
                this.userId,
                this.parentId,
                this.content,
                this.isFromOwner,
                this.status,
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

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isFromOwner() {
        return isFromOwner;
    }

    public void setFromOwner(boolean fromOwner) {
        isFromOwner = fromOwner;
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
        DiscussionJpaEntity that = (DiscussionJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
