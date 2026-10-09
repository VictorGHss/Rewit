package com.rewit.application.port;

import com.rewit.application.dto.business.BusinessDtos.PlaceClaimView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.domain.enums.PlaceClaimStatus;
import com.rewit.domain.model.PlaceClaimRequest;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência das solicitações de reivindicação de locais (place_claim_requests, C9).
 */
public interface PlaceClaimRequestRepository {

    /**
     * Grava e sincroniza com o banco, para que a violação do índice de pendência única (uq_place_claim_pending_place)
     * surja na chamada.
     */
    PlaceClaimRequest save(PlaceClaimRequest claim);

    Optional<PlaceClaimRequest> findById(UUID id);

    /** Trava a linha (FOR UPDATE) e recarrega o estado confirmado, mesmo se já carregada na transação. */
    Optional<PlaceClaimRequest> findByIdForUpdate(UUID id);

    boolean existsPendingByPlaceId(UUID placeId);

    /**
     * Página de visões com conta e local numa única consulta.
     *
     * @param businessAccountId restringe a uma conta; null para todas (fila administrativa)
     * @param status            null para todos os status
     * @param oldestFirst       true na fila administrativa (mais antigas primeiro); false no histórico da conta
     */
    PageResult<PlaceClaimView> findViews(UUID businessAccountId, PlaceClaimStatus status, int page, int size,
                                         boolean oldestFirst);
}
