package com.rewit.application.port;

import com.rewit.application.dto.common.PageResult;
import com.rewit.domain.model.PlaceMediaReport;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída para persistência e recuperação de denúncias de mídias de locais (PlaceMediaReport).
 */
public interface PlaceMediaReportRepository {

    PlaceMediaReport save(PlaceMediaReport report);

    Optional<PlaceMediaReport> findById(UUID id);

    default Optional<PlaceMediaReport> findByIdForUpdate(UUID id) {
        return findById(id);
    }

    boolean existsByPlaceMediaIdAndReporterUserId(UUID placeMediaId, UUID reporterUserId);

    long countByPlaceMediaIdAndStatus(UUID placeMediaId, String status);

    List<PlaceMediaReport> findByPlaceMediaId(UUID placeMediaId);

    default List<PlaceMediaReport> findByPlaceMediaIdForUpdate(UUID placeMediaId) {
        return findByPlaceMediaId(placeMediaId);
    }

    PageResult<PlaceMediaReport> findByStatus(String status, int page, int size);
}
