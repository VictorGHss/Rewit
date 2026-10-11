package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.BusinessResponseRemovalSource;
import com.rewit.domain.enums.BusinessResponseStatus;
import com.rewit.domain.model.BusinessResponse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA da tabela business_responses (V24). Alvo, empresa, local, autor e conteúdo são imutáveis
 * ({@code updatable = false}); só o estado de remoção muda.
 */
@Entity
@Table(name = "business_responses")
public class BusinessResponseJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "review_id", nullable = false, updatable = false)
    private UUID reviewId;

    @Column(name = "discussion_id", updatable = false)
    private UUID discussionId;

    @Column(name = "business_account_id", nullable = false, updatable = false)
    private UUID businessAccountId;

    @Column(name = "place_id", nullable = false, updatable = false)
    private UUID placeId;

    @Column(name = "author_user_id", nullable = false, updatable = false)
    private UUID authorUserId;

    @Column(name = "content", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BusinessResponseStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "removed_by", length = 16)
    private BusinessResponseRemovalSource removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BusinessResponseJpaEntity() {
    }

    public static BusinessResponseJpaEntity fromDomain(BusinessResponse response) {
        BusinessResponseJpaEntity entity = new BusinessResponseJpaEntity();
        entity.id = response.getId();
        entity.reviewId = response.getReviewId();
        entity.discussionId = response.getDiscussionId();
        entity.businessAccountId = response.getBusinessAccountId();
        entity.placeId = response.getPlaceId();
        entity.authorUserId = response.getAuthorUserId();
        entity.content = response.getContent();
        entity.createdAt = response.getCreatedAt();
        entity.updateFromDomain(response);
        return entity;
    }

    /** Só o estado de remoção é mutável. */
    public void updateFromDomain(BusinessResponse response) {
        this.status = response.getStatus();
        this.removedBy = response.getRemovedBy();
        this.removedAt = response.getRemovedAt();
        this.updatedAt = response.getUpdatedAt();
    }

    public BusinessResponse toDomain() {
        return new BusinessResponse(id, reviewId, discussionId, businessAccountId, placeId, authorUserId, content,
                status, removedBy, removedAt, createdAt, updatedAt);
    }
}
