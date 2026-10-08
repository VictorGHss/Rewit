package com.rewit.application.port;

import com.rewit.domain.model.ProductIdentifier;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída da aplicação para persistência e consulta de identificadores estruturados de produtos (ProductIdentifier).
 */
public interface ProductIdentifierRepository {

    ProductIdentifier save(ProductIdentifier identifier);

    Optional<ProductIdentifier> findByTypeAndValue(String identifierType, String identifierValue);

    List<ProductIdentifier> findByProductId(UUID productId);

    /** Identificadores do produto restritos aos tipos informados, ordenados por tipo e valor. */
    List<ProductIdentifier> findByProductIdAndTypes(UUID productId, Collection<String> identifierTypes);

    boolean existsByTypeAndValue(String identifierType, String identifierValue);
}
