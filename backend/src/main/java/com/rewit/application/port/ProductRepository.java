package com.rewit.application.port;

import com.rewit.application.dto.common.PageResult;
import com.rewit.domain.model.Product;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída da aplicação para operações de persistência e recuperação de produtos globais (Product).
 */
public interface ProductRepository {

    Product save(Product product);

    Optional<Product> findById(UUID id);

    List<Product> searchByName(String name);

    /**
     * Produtos {@code ACTIVE} com presença registrada no local, paginados por nome e id. Só dados do produto: nada da
     * presença (nem quem a relatou).
     */
    PageResult<Product> findActiveByPlace(UUID placeId, int page, int size);
}
