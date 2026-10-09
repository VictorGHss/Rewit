package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.business.BusinessDtos.PlaceClaimView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.PlaceClaimRequestRepository;
import com.rewit.domain.enums.PlaceClaimStatus;
import com.rewit.domain.model.PlaceClaimRequest;
import com.rewit.infrastructure.persistence.entity.PlaceClaimRequestJpaEntity;
import com.rewit.infrastructure.persistence.repository.PlaceClaimRequestJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência das solicitações de reivindicação de locais (C9).
 */
@Component
public class PlaceClaimRequestRepositoryAdapter implements PlaceClaimRequestRepository {

    private final PlaceClaimRequestJpaRepository repository;
    private final EntityManager entityManager;

    public PlaceClaimRequestRepositoryAdapter(PlaceClaimRequestJpaRepository repository, EntityManager entityManager) {
        this.repository = Objects.requireNonNull(repository, "PlaceClaimRequestJpaRepository must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "EntityManager must not be null");
    }

    @Override
    public PlaceClaimRequest save(PlaceClaimRequest claim) {
        Objects.requireNonNull(claim, "PlaceClaimRequest must not be null");
        PlaceClaimRequestJpaEntity entity = repository.findById(claim.getId())
                .map(existing -> {
                    existing.updateFromDomain(claim);
                    return existing;
                })
                .orElseGet(() -> PlaceClaimRequestJpaEntity.fromDomain(claim));
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<PlaceClaimRequest> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id).map(entity -> entity.toDomain());
    }

    @Override
    public Optional<PlaceClaimRequest> findByIdForUpdate(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        PlaceClaimRequestJpaEntity entity = entityManager.find(PlaceClaimRequestJpaEntity.class, id);
        if (entity == null) {
            return Optional.empty();
        }
        // refresh com lock: a decisão lê a solicitação antes de travar o local, então a instância já está gerenciada
        try {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        } catch (EntityNotFoundException removedMeanwhile) {
            return Optional.empty();
        }
        return Optional.of(entity.toDomain());
    }

    @Override
    public boolean existsPendingByPlaceId(UUID placeId) {
        return placeId != null && repository.existsPendingByPlaceId(placeId);
    }

    @Override
    public PageResult<PlaceClaimView> findViews(UUID businessAccountId, PlaceClaimStatus status, int page, int size,
                                                boolean oldestFirst) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (businessAccountId != null) {
            where.append(" AND c.business_account_id = :businessAccountId");
        }
        if (status != null) {
            where.append(" AND c.status = :status");
        }
        String order = oldestFirst ? " ORDER BY c.created_at ASC, c.id ASC" : " ORDER BY c.created_at DESC, c.id ASC";
        // Espaço explícito: o text block remove a indentação e colaria o FROM na coluna anterior
        String from = " " + """
                FROM place_claim_requests c
                JOIN business_accounts b ON b.id = c.business_account_id
                JOIN places p ON p.id = c.place_id""";

        Query query = entityManager.createNativeQuery("""
                SELECT c.id, c.business_account_id, b.corporate_name, b.tax_id, c.place_id, p.name, p.city, p.state,
                       c.status, c.evidence_description, c.created_at, c.decided_at, c.decision_reason"""
                + from + where + order + " LIMIT :limit OFFSET :offset");
        Query count = entityManager.createNativeQuery("SELECT COUNT(*)" + from + where);
        for (Query q : List.of(query, count)) {
            if (businessAccountId != null) {
                q.setParameter("businessAccountId", businessAccountId);
            }
            if (status != null) {
                q.setParameter("status", status.name());
            }
        }
        query.setParameter("limit", size);
        query.setParameter("offset", (long) page * size);

        List<?> rows = query.getResultList();
        List<PlaceClaimView> content = new ArrayList<>(rows.size());
        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            content.add(new PlaceClaimView(
                    UUID.fromString(columns[0].toString()),
                    UUID.fromString(columns[1].toString()),
                    (String) columns[2],
                    (String) columns[3],
                    UUID.fromString(columns[4].toString()),
                    (String) columns[5],
                    (String) columns[6],
                    (String) columns[7],
                    PlaceClaimStatus.valueOf((String) columns[8]),
                    (String) columns[9],
                    toInstant(columns[10]),
                    toInstant(columns[11]),
                    (String) columns[12]));
        }
        long total = ((Number) count.getSingleResult()).longValue();
        return PageResult.of(content, page, size, total);
    }

    private static Instant toInstant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> instant;
            case OffsetDateTime offset -> offset.toInstant();
            case Timestamp timestamp -> timestamp.toInstant();
            default -> throw new IllegalStateException("Tipo de data inesperado: " + value.getClass().getName());
        };
    }
}
