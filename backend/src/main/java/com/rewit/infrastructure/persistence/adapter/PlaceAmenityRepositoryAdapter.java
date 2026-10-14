package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.PlaceAmenityRepository;
import com.rewit.domain.enums.PlaceAmenity;
import com.rewit.domain.model.PlaceAmenityConfiguration;
import com.rewit.infrastructure.persistence.entity.PlaceAmenityConfigurationJpaEntity;
import com.rewit.infrastructure.persistence.repository.PlaceAmenityConfigurationJpaRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistência das comodidades (V27). A seleção é lida e substituída por SQL nativo, sem entidades gerenciadas,
 * para que nenhum estado em cache no contexto de persistência divirja do banco na substituição integral.
 */
@Component
public class PlaceAmenityRepositoryAdapter implements PlaceAmenityRepository {

    private final PlaceAmenityConfigurationJpaRepository configurationRepository;
    private final EntityManager entityManager;

    public PlaceAmenityRepositoryAdapter(PlaceAmenityConfigurationJpaRepository configurationRepository,
                                         EntityManager entityManager) {
        this.configurationRepository = Objects.requireNonNull(configurationRepository,
                "PlaceAmenityConfigurationJpaRepository must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "EntityManager must not be null");
    }

    @Override
    public Optional<PlaceAmenityConfiguration> findByPlaceId(UUID placeId) {
        if (placeId == null) {
            return Optional.empty();
        }
        return configurationRepository.findById(placeId)
                .map(entity -> toDomain(entity, findSelectedAmenities(placeId)));
    }

    @Override
    public PlaceAmenityConfiguration save(PlaceAmenityConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        UUID placeId = configuration.getPlaceId();

        PlaceAmenityConfigurationJpaEntity entity = configurationRepository.findById(placeId)
                .orElseGet(() -> new PlaceAmenityConfigurationJpaEntity(placeId,
                        configuration.getBusinessAccountId(), configuration.getUpdatedByUserId(),
                        configuration.getCreatedAt(), configuration.getUpdatedAt()));
        entity.setBusinessAccountId(configuration.getBusinessAccountId());
        entity.setUpdatedByUserId(configuration.getUpdatedByUserId());
        entity.setUpdatedAt(configuration.getUpdatedAt());
        // A linha mestre precisa existir antes das seleções (FK); o flush também envia o UPDATE de auditoria.
        PlaceAmenityConfigurationJpaEntity saved = configurationRepository.saveAndFlush(entity);

        entityManager.createNativeQuery("DELETE FROM place_amenity_selections WHERE place_id = :placeId")
                .setParameter("placeId", placeId)
                .executeUpdate();
        for (PlaceAmenity amenity : configuration.getAmenities()) {
            entityManager.createNativeQuery(
                            "INSERT INTO place_amenity_selections (place_id, amenity_code) VALUES (:placeId, :code)")
                    .setParameter("placeId", placeId)
                    .setParameter("code", amenity.getCode())
                    .executeUpdate();
        }

        return toDomain(saved, configuration.getAmenities());
    }

    private List<PlaceAmenity> findSelectedAmenities(UUID placeId) {
        List<?> codes = entityManager.createNativeQuery(
                        "SELECT amenity_code FROM place_amenity_selections WHERE place_id = :placeId")
                .setParameter("placeId", placeId)
                .getResultList();
        List<PlaceAmenity> amenities = new ArrayList<>(codes.size());
        for (Object code : codes) {
            amenities.add(PlaceAmenity.valueOf((String) code));
        }
        return amenities;
    }

    private static PlaceAmenityConfiguration toDomain(PlaceAmenityConfigurationJpaEntity entity,
                                                      Collection<PlaceAmenity> amenities) {
        return new PlaceAmenityConfiguration(entity.getPlaceId(), entity.getBusinessAccountId(),
                entity.getUpdatedByUserId(), entity.getCreatedAt(), entity.getUpdatedAt(), amenities);
    }
}
