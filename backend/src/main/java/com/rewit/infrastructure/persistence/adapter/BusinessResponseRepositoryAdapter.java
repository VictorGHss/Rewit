package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.BusinessResponseRepository;
import com.rewit.domain.enums.BusinessResponseStatus;
import com.rewit.domain.model.BusinessResponse;
import com.rewit.infrastructure.persistence.entity.BusinessResponseJpaEntity;
import com.rewit.infrastructure.persistence.repository.BusinessResponseJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class BusinessResponseRepositoryAdapter implements BusinessResponseRepository {

    private final BusinessResponseJpaRepository repository;
    private final EntityManager entityManager;

    public BusinessResponseRepositoryAdapter(BusinessResponseJpaRepository repository, EntityManager entityManager) {
        this.repository = Objects.requireNonNull(repository, "BusinessResponseJpaRepository must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "EntityManager must not be null");
    }

    @Override
    @Transactional
    public BusinessResponse save(BusinessResponse response) {
        Objects.requireNonNull(response, "BusinessResponse must not be null");
        BusinessResponseJpaEntity entity = repository.findById(response.getId())
                .map(existing -> {
                    existing.updateFromDomain(response);
                    return existing;
                })
                .orElseGet(() -> BusinessResponseJpaEntity.fromDomain(response));
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BusinessResponse> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id).map(entity -> entity.toDomain());
    }

    @Override
    public Optional<BusinessResponse> findByIdForUpdate(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        BusinessResponseJpaEntity entity = entityManager.find(BusinessResponseJpaEntity.class, id);
        if (entity == null) {
            return Optional.empty();
        }
        // refresh com lock: devolve o estado confirmado mesmo que a instância já estivesse gerenciada na transação
        try {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        } catch (EntityNotFoundException removedMeanwhile) {
            return Optional.empty();
        }
        return Optional.of(entity.toDomain());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BusinessResponse> findActive(UUID reviewId, UUID discussionId, UUID businessAccountId) {
        if (reviewId == null || businessAccountId == null) {
            return Optional.empty();
        }
        Optional<BusinessResponseJpaEntity> found = discussionId == null
                ? repository.findByReviewIdAndDiscussionIdIsNullAndBusinessAccountIdAndStatus(
                        reviewId, businessAccountId, BusinessResponseStatus.ACTIVE)
                : repository.findByReviewIdAndDiscussionIdAndBusinessAccountIdAndStatus(
                        reviewId, discussionId, businessAccountId, BusinessResponseStatus.ACTIVE);
        return found.map(entity -> entity.toDomain());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<BusinessResponse> findActiveReviewResponses(UUID reviewId, int page, int size) {
        if (reviewId == null) {
            return PageResult.of(List.of(), page, size, 0);
        }
        Page<BusinessResponseJpaEntity> result = repository
                .findByReviewIdAndDiscussionIdIsNullAndStatusOrderByCreatedAtAscIdAsc(
                        reviewId, BusinessResponseStatus.ACTIVE, PageRequest.of(page, size));
        List<BusinessResponse> content = result.getContent().stream()
                .map(entity -> entity.toDomain())
                .toList();
        return PageResult.of(content, page, size, result.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<BusinessResponseView> findActiveReviewResponseViews(UUID reviewId, int page, int size) {
        // Atendida por idx_business_responses_review (review_id, created_at, id)
        return findActiveViews("r.review_id = :targetId AND r.discussion_id IS NULL", reviewId, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<BusinessResponseView> findActiveDiscussionResponseViews(UUID discussionId, int page, int size) {
        // Filtro atendido por uq_business_responses_discussion_active (discussion_id, ...) WHERE status = 'ACTIVE'; no
        // máximo uma resposta ativa por empresa no comentário, então a ordenação é sobre poucas linhas
        return findActiveViews("r.discussion_id = :targetId", discussionId, page, size);
    }

    /** Página pública das respostas ativas do alvo, com empresa e local numa única consulta. */
    private PageResult<BusinessResponseView> findActiveViews(String targetFilter, UUID targetId, int page, int size) {
        if (targetId == null) {
            return PageResult.of(List.of(), page, size, 0);
        }
        String where = " FROM business_responses r"
                + " JOIN business_accounts b ON b.id = r.business_account_id"
                + " JOIN places p ON p.id = r.place_id"
                + " WHERE " + targetFilter + " AND r.status = 'ACTIVE'";
        List<?> rows = entityManager.createNativeQuery("SELECT r.id, r.review_id, r.discussion_id,"
                        + " b.id AS business_account_id, b.corporate_name, p.id AS place_id, p.name, r.content,"
                        + " r.created_at" + where
                        + " ORDER BY r.created_at ASC, r.id ASC")
                .setParameter("targetId", targetId)
                .setFirstResult(Math.multiplyExact(page, size))
                .setMaxResults(size)
                .getResultList();
        Number total = (Number) entityManager.createNativeQuery("SELECT count(*)" + where)
                .setParameter("targetId", targetId)
                .getSingleResult();
        List<BusinessResponseView> content = rows.stream()
                .map(row -> toView((Object[]) row))
                .toList();
        return PageResult.of(content, page, size, total.longValue());
    }

    private static BusinessResponseView toView(Object[] row) {
        return new BusinessResponseView((UUID) row[0], (UUID) row[1], (UUID) row[2], (UUID) row[3], (String) row[4],
                (UUID) row[5], (String) row[6], (String) row[7], toInstant(row[8]));
    }

    private static Instant toInstant(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case OffsetDateTime offsetDateTime -> offsetDateTime.toInstant();
            case Timestamp timestamp -> timestamp.toInstant();
            default -> throw new IllegalStateException("Tipo de data inesperado: " + value.getClass());
        };
    }

    @Override
    @Transactional(readOnly = true)
    public List<BusinessResponse> findActiveDiscussionResponses(Collection<UUID> discussionIds) {
        if (discussionIds == null || discussionIds.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = discussionIds.stream()
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return repository.findByDiscussionIdInAndStatusOrderByCreatedAtAscIdAsc(ids, BusinessResponseStatus.ACTIVE)
                .stream()
                .map(entity -> entity.toDomain())
                .toList();
    }
}
