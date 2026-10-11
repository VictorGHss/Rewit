package com.rewit.infrastructure.persistence.adapter;

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
