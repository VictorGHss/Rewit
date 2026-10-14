package com.rewit.infrastructure.persistence.repository;

import com.rewit.domain.enums.BusinessResponseStatus;
import com.rewit.infrastructure.persistence.entity.BusinessResponseJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Listas de respostas ativas não ficam aqui: o adaptador as lê em SQL nativo com o filtro de elegibilidade da
 * representação (decisão B), que uma consulta derivada não aplicaria.
 */
public interface BusinessResponseJpaRepository extends JpaRepository<BusinessResponseJpaEntity, UUID> {

    /** Atendida por uq_business_responses_review_active. */
    Optional<BusinessResponseJpaEntity> findByReviewIdAndDiscussionIdIsNullAndBusinessAccountIdAndStatus(
            UUID reviewId, UUID businessAccountId, BusinessResponseStatus status);

    /** Atendida por uq_business_responses_discussion_active. */
    Optional<BusinessResponseJpaEntity> findByReviewIdAndDiscussionIdAndBusinessAccountIdAndStatus(
            UUID reviewId, UUID discussionId, UUID businessAccountId, BusinessResponseStatus status);
}
