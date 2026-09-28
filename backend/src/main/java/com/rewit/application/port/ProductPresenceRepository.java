package com.rewit.application.port;

import com.rewit.domain.model.ProductPresence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída da aplicação para persistência e consulta da presença de produtos em locais (ProductPresence).
 */
public interface ProductPresenceRepository {

    ProductPresence save(ProductPresence presence);

    Optional<ProductPresence> findByProductIdAndPlaceId(UUID productId, UUID placeId);

    List<ProductPresence> findByPlaceId(UUID placeId);

    List<ProductPresence> findByProductId(UUID productId);

    boolean existsByProductIdAndPlaceId(UUID productId, UUID placeId);
}
