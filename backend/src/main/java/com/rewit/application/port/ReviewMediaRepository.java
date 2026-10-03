package com.rewit.application.port;

import com.rewit.application.dto.storage.StorageReconciliationDtos.ReviewMediaReference;
import com.rewit.domain.model.ReviewMedia;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída para operações de persistência e recuperação de mídias de avaliação.
 */
public interface ReviewMediaRepository {

    ReviewMedia save(ReviewMedia media);

    Optional<ReviewMedia> findById(UUID id);

    List<ReviewMedia> findActiveByReviewId(UUID reviewId);

    long countActiveByReviewId(UUID reviewId);

    /**
     * Busca as referências persistidas (qualquer status) das chaves informadas.
     * Chaves sem linha correspondente simplesmente não aparecem no resultado.
     */
    List<ReviewMediaReference> findReferencesByObjectKeys(Collection<String> objectKeys);
}
