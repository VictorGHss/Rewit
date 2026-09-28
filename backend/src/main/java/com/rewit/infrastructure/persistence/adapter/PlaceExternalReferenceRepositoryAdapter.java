package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.PlaceExternalReferenceRepository;
import com.rewit.domain.model.PlaceExternalReference;
import com.rewit.infrastructure.persistence.entity.PlaceExternalReferenceJpaEntity;
import com.rewit.infrastructure.persistence.repository.PlaceExternalReferenceJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para PlaceExternalReference.
 * Conecta a porta de aplicação aos repositórios Spring Data JPA.
 */
@Component
public class PlaceExternalReferenceRepositoryAdapter implements PlaceExternalReferenceRepository {

    private final PlaceExternalReferenceJpaRepository repository;

    public PlaceExternalReferenceRepositoryAdapter(PlaceExternalReferenceJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "PlaceExternalReferenceJpaRepository must not be null");
    }

    @Override
    public PlaceExternalReference save(PlaceExternalReference externalReference) {
        Objects.requireNonNull(externalReference, "PlaceExternalReference cannot be null");
        PlaceExternalReferenceJpaEntity entity = PlaceExternalReferenceJpaEntity.fromDomain(externalReference);
        PlaceExternalReferenceJpaEntity saved = repository.saveAndFlush(entity);
        return Objects.requireNonNull(saved, "Saved PlaceExternalReferenceJpaEntity cannot be null").toDomain();
    }

    @Override
    public Optional<PlaceExternalReference> findByProviderAndExternalId(String provider, String externalId) {
        if (provider == null || externalId == null) {
            return Optional.empty();
        }
        return repository.findByProviderAndExternalId(provider.trim().toUpperCase(Locale.ROOT), externalId.trim())
                .map(PlaceExternalReferenceRepositoryAdapter::toDomain);
    }

    @Override
    public List<PlaceExternalReference> findByPlaceId(UUID placeId) {
        if (placeId == null) {
            return List.of();
        }
        return repository.findByPlaceId(placeId).stream()
                .map(PlaceExternalReferenceRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    public boolean existsByProviderAndExternalId(String provider, String externalId) {
        if (provider == null || externalId == null) {
            return false;
        }
        return repository.existsByProviderAndExternalId(provider.trim().toUpperCase(Locale.ROOT), externalId.trim());
    }

    @Override
    public void deleteById(UUID id) {
        if (id != null) {
            repository.deleteById(id);
        }
    }

    private static PlaceExternalReference toDomain(PlaceExternalReferenceJpaEntity entity) {
        return entity != null ? entity.toDomain() : null;
    }
}
