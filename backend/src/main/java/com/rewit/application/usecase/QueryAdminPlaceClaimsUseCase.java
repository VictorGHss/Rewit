package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessDtos.PlaceClaimView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.PlaceClaimRequestRepository;
import com.rewit.domain.enums.PlaceClaimStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Fila administrativa de reivindicações (C9): mais antigas primeiro, filtro opcional por status. Como a fila de
 * denúncias (QueryAdminReportsUseCase), a autorização MODERATOR/ADMIN é aplicada na rota HTTP.
 */
@Service
public class QueryAdminPlaceClaimsUseCase {

    private final PlaceClaimRequestRepository placeClaimRequestRepository;

    public QueryAdminPlaceClaimsUseCase(PlaceClaimRequestRepository placeClaimRequestRepository) {
        this.placeClaimRequestRepository = Objects.requireNonNull(placeClaimRequestRepository,
                "PlaceClaimRequestRepository must not be null");
    }

    @Transactional(readOnly = true)
    public PageResult<PlaceClaimView> execute(int page, int size, PlaceClaimStatus status) {
        PlaceClaimPagination.validate(page, size);
        return placeClaimRequestRepository.findViews(null, status, page, size, true);
    }
}
