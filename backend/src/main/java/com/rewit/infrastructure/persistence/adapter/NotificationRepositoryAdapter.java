package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.NotificationRepository;
import com.rewit.domain.model.Notification;
import com.rewit.infrastructure.persistence.entity.NotificationJpaEntity;
import com.rewit.infrastructure.persistence.repository.NotificationJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para notificações (Step 22.0).
 */
@Component
public class NotificationRepositoryAdapter implements NotificationRepository {

    private final NotificationJpaRepository jpaRepository;

    public NotificationRepositoryAdapter(NotificationJpaRepository jpaRepository) {
        this.jpaRepository = Objects.requireNonNull(jpaRepository, "NotificationJpaRepository must not be null");
    }

    @Override
    @Transactional
    public Notification save(Notification notification) {
        Objects.requireNonNull(notification, "notification must not be null");
        NotificationJpaEntity entity = NotificationJpaEntity.fromDomain(notification);
        NotificationJpaEntity saved = jpaRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Notification> findByIdAndUserId(UUID id, UUID userId) {
        if (id == null || userId == null) {
            return Optional.empty();
        }
        return jpaRepository.findByIdAndUserId(id, userId)
                .map(entity -> entity.toDomain());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Notification> findByUserId(UUID userId, int page, int size) {
        Objects.requireNonNull(userId, "userId must not be null");
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<NotificationJpaEntity> pagedEntities = jpaRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageRequest);

        List<Notification> domainList = pagedEntities.getContent().stream()
                .map(entity -> entity.toDomain())
                .toList();

        return new PageResult<>(
                domainList,
                pagedEntities.getNumber(),
                pagedEntities.getSize(),
                pagedEntities.getTotalElements(),
                pagedEntities.getTotalPages(),
                pagedEntities.isLast()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnreadByUserId(UUID userId) {
        if (userId == null) {
            return 0;
        }
        return jpaRepository.countUnreadByUserId(userId);
    }

    @Override
    @Transactional
    public int markAllAsReadByUserId(UUID userId) {
        if (userId == null) {
            return 0;
        }
        return jpaRepository.markAllAsReadByUserId(userId);
    }
}
