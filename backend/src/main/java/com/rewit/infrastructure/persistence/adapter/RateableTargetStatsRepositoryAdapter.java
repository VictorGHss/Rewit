package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.RateableTargetStatsRepository;
import com.rewit.domain.model.RateableTargetStats;
import com.rewit.infrastructure.persistence.entity.RateableTargetStatsJpaEntity;
import com.rewit.infrastructure.persistence.repository.RateableTargetStatsJpaRepository;
import jakarta.persistence.Tuple;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para RateableTargetStatsRepository (Step 13.0).
 * Garante serialização atômica (ensure exists -> SELECT FOR UPDATE -> recalculate AVG/COUNT -> UPDATE).
 */
@Component
public class RateableTargetStatsRepositoryAdapter implements RateableTargetStatsRepository {

    private final RateableTargetStatsJpaRepository rateableTargetStatsJpaRepository;

    public RateableTargetStatsRepositoryAdapter(RateableTargetStatsJpaRepository rateableTargetStatsJpaRepository) {
        this.rateableTargetStatsJpaRepository = Objects.requireNonNull(rateableTargetStatsJpaRepository, "rateableTargetStatsJpaRepository must not be null");
    }

    @Override
    @Transactional
    public RateableTargetStats save(RateableTargetStats stats) {
        Objects.requireNonNull(stats, "stats must not be null");
        RateableTargetStatsJpaEntity entity = RateableTargetStatsJpaEntity.fromDomain(stats);
        RateableTargetStatsJpaEntity saved = rateableTargetStatsJpaRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RateableTargetStats> findByTargetId(UUID targetId) {
        if (targetId == null) {
            return Optional.empty();
        }
        return rateableTargetStatsJpaRepository.findById(targetId)
                .map(e -> toDomain(e));
    }

    @Override
    @Transactional
    public RateableTargetStats recalculateAndSave(UUID targetId) {
        Objects.requireNonNull(targetId, "targetId must not be null");

        // 1. Garantir que a linha de estatística exista previamente
        rateableTargetStatsJpaRepository.insertInitialRowIfNotExists(targetId);

        // 2. Adquirir lock pessimista da linha (SELECT ... FOR UPDATE)
        RateableTargetStatsJpaEntity entity = rateableTargetStatsJpaRepository.findByTargetIdForUpdate(targetId)
                .orElseThrow(() -> new IllegalStateException("Falha ao adquirir lock para o alvo: " + targetId));

        // 3. Recalcular agregação atômica no PostgreSQL
        Tuple tuple = rateableTargetStatsJpaRepository.calculateAggregateStats(targetId);

        BigDecimal averageRating = extractBigDecimal(tuple.get("average_rating"));
        int reviewsCount = extractInt(tuple.get("reviews_count"));

        // 4. Atualizar o registro sob lock
        entity.setAverageRating(averageRating);
        entity.setReviewsCount(reviewsCount);
        entity.setLastCalculatedAt(Instant.now());

        RateableTargetStatsJpaEntity saved = rateableTargetStatsJpaRepository.save(entity);
        return saved.toDomain();
    }

    private static BigDecimal extractBigDecimal(Object obj) {
        if (obj == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (obj instanceof BigDecimal bd) {
            return bd.setScale(2, RoundingMode.HALF_UP);
        }
        if (obj instanceof Number num) {
            return BigDecimal.valueOf(num.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        }
        return new BigDecimal(obj.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    private static int extractInt(Object obj) {
        if (obj == null) {
            return 0;
        }
        if (obj instanceof Number num) {
            return num.intValue();
        }
        return Integer.parseInt(obj.toString());
    }

    private static RateableTargetStats toDomain(RateableTargetStatsJpaEntity entity) {
        return entity != null ? entity.toDomain() : null;
    }
}
