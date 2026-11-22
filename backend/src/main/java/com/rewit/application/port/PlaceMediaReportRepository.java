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

    default PageResult<PlaceMediaReport> findByStatus(String status, int page, int size) {
        return findByStatusAndDecisionOrigin(status, null, page, size);
    }

    /**
     * Lista denúncias filtrando opcionalmente por status e pela proveniência da decisão
     * ({@link PlaceMediaReport#DECISION_ORIGIN_MODERATOR}, {@link PlaceMediaReport#DECISION_ORIGIN_SYSTEM}
     * ou {@link PlaceMediaReport#DECISION_ORIGIN_LEGACY}); {@code null} em qualquer filtro não restringe.
     */
    PageResult<PlaceMediaReport> findByStatusAndDecisionOrigin(String status, String decisionOrigin, int page, int size);

    record AutoResolvedReport(UUID reportId, String reason) {
    }

    /**
     * Indica se o repositório suporta auto-resolução controlada via função SECURITY DEFINER
     * {@code public.rewit_auto_resolve_removed_place_media_reports} (S3-A13-F2).
     */
    default boolean supportsDatabaseAutoResolve() {
        return false;
    }

    /**
     * Auto-resolve denúncias {@code PENDING} de uma fotografia já {@code REMOVED} por origem não administrativa,
     * via função {@code SECURITY DEFINER} no banco (evitando privilégio de {@code UPDATE} direto para {@code rewit_app}).
     */
    default List<AutoResolvedReport> autoResolvePendingForRemovedMedia(UUID placeMediaId, String decisionNotes) {
        return List.of();
    }
}
