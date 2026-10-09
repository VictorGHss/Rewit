package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.infrastructure.persistence.entity.BusinessAccountJpaEntity;
import com.rewit.infrastructure.persistence.repository.BusinessAccountJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência das contas comerciais (C9).
 */
@Component
public class BusinessAccountRepositoryAdapter implements BusinessAccountRepository {

    private final BusinessAccountJpaRepository repository;
    private final EntityManager entityManager;

    public BusinessAccountRepositoryAdapter(BusinessAccountJpaRepository repository, EntityManager entityManager) {
        this.repository = Objects.requireNonNull(repository, "BusinessAccountJpaRepository must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "EntityManager must not be null");
    }

    @Override
    public BusinessAccount save(BusinessAccount account) {
        Objects.requireNonNull(account, "BusinessAccount must not be null");
        BusinessAccountJpaEntity entity = repository.findById(account.getId())
                .map(existing -> {
                    existing.updateFromDomain(account);
                    return existing;
                })
                .orElseGet(() -> BusinessAccountJpaEntity.fromDomain(account));
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<BusinessAccount> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id).map(entity -> entity.toDomain());
    }

    @Override
    public Optional<BusinessAccount> findByIdForUpdate(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        BusinessAccountJpaEntity entity = entityManager.find(BusinessAccountJpaEntity.class, id);
        if (entity == null) {
            return Optional.empty();
        }
        // refresh com lock, e não uma consulta com lock: se a conta já foi carregada nesta transação, a consulta
        // travaria a linha mas devolveria a instância gerenciada com o estado antigo (mesmo padrão de users)
        try {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        } catch (EntityNotFoundException removedMeanwhile) {
            return Optional.empty();
        }
        return Optional.of(entity.toDomain());
    }

    @Override
    public List<BusinessAccount> findByUserId(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return repository.findByUserIdOrdered(userId).stream().map(entity -> entity.toDomain()).toList();
    }

    @Override
    public boolean existsByTaxId(String taxId) {
        return taxId != null && repository.existsByTaxId(taxId);
    }
}
