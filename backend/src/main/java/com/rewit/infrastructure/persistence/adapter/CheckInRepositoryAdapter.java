package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.checkin.CheckInDtos.MyCheckInView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.CheckInRepository;
import com.rewit.domain.enums.CheckInStatus;
import com.rewit.domain.model.CheckIn;
import com.rewit.infrastructure.persistence.entity.CheckInJpaEntity;
import com.rewit.infrastructure.persistence.repository.CheckInJpaRepository;
import jakarta.persistence.Tuple;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para a entidade CheckIn (Step 12.0).
 */
@Component
public class CheckInRepositoryAdapter implements CheckInRepository {

    private static final String PRESENCE_LOCK_PREFIX = "check-in-presence:";

    private final CheckInJpaRepository checkInJpaRepository;

    public CheckInRepositoryAdapter(CheckInJpaRepository checkInJpaRepository) {
        this.checkInJpaRepository = Objects.requireNonNull(checkInJpaRepository, "checkInJpaRepository must not be null");
    }

    @Override
    @Transactional
    public CheckIn save(CheckIn checkIn) {
        Objects.requireNonNull(checkIn, "CheckIn cannot be null");
        CheckInJpaEntity entity = CheckInJpaEntity.fromDomain(checkIn);
        CheckInJpaEntity saved = checkInJpaRepository.saveAndFlush(entity);
        return saved.toDomain();
    }

    @Override
    public Optional<CheckIn> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return checkInJpaRepository.findById(id).map(e -> toDomain(e));
    }

    @Override
    public Optional<CheckIn> findByReviewId(UUID reviewId) {
        if (reviewId == null) {
            return Optional.empty();
        }
        return checkInJpaRepository.findByReviewId(reviewId).map(e -> toDomain(e));
    }

    @Override
    public List<CheckIn> findByUserId(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return checkInJpaRepository.findByUserId(userId).stream()
                .map(e -> toDomain(e))
                .toList();
    }

    @Override
    public List<CheckIn> findByPlaceId(UUID placeId) {
        if (placeId == null) {
            return List.of();
        }
        return checkInJpaRepository.findByPlaceId(placeId).stream()
                .map(e -> toDomain(e))
                .toList();
    }

    @Override
    public boolean existsByReviewId(UUID reviewId) {
        if (reviewId == null) {
            return false;
        }
        return checkInJpaRepository.existsByReviewId(reviewId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPresenceVerification(UUID userId, UUID placeId) {
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(placeId, "placeId cannot be null");
        checkInJpaRepository.acquireTransactionLock(PRESENCE_LOCK_PREFIX + userId + ":" + placeId);
    }

    @Override
    public boolean existsVerifiedSince(UUID userId, UUID placeId, Instant since) {
        if (userId == null || placeId == null || since == null) {
            return false;
        }
        return checkInJpaRepository.existsByUserIdAndPlaceIdAndStatusAndVerifiedAtAfter(
                userId, placeId, CheckInStatus.VERIFIED.name(), since);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<MyCheckInView> findMyVerifiedCheckIns(UUID userId, int page, int size) {
        Objects.requireNonNull(userId, "userId cannot be null");
        long total = checkInJpaRepository.countMyVerifiedCheckIns(userId);
        if (total == 0) {
            return PageResult.of(List.of(), page, size, 0);
        }
        List<MyCheckInView> content = checkInJpaRepository
                .findMyVerifiedCheckIns(userId, size, (long) page * size).stream()
                .map(CheckInRepositoryAdapter::toMyCheckInView)
                .toList();
        return PageResult.of(content, page, size, total);
    }

    private static MyCheckInView toMyCheckInView(Tuple tuple) {
        return new MyCheckInView(
                tuple.get("check_in_id", UUID.class),
                tuple.get("review_id", UUID.class),
                tuple.get("place_id", UUID.class),
                tuple.get("place_name", String.class),
                tuple.get("place_category", String.class),
                tuple.get("place_city", String.class),
                tuple.get("place_state", String.class),
                toInstant(tuple.get("verified_at"))
        );
    }

    private static Instant toInstant(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case OffsetDateTime offsetDateTime -> offsetDateTime.toInstant();
            case Timestamp timestamp -> timestamp.toInstant();
            default -> throw new IllegalStateException("Tipo de data inesperado: " + value.getClass());
        };
    }

    private static CheckIn toDomain(CheckInJpaEntity entity) {
        return entity != null ? entity.toDomain() : null;
    }
}
