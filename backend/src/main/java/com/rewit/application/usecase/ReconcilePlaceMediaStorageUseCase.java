package com.rewit.application.usecase;

import com.rewit.application.dto.storage.StorageReconciliationDtos.OrphanCandidate;
import com.rewit.application.dto.storage.StorageReconciliationDtos.ReviewMediaReference;
import com.rewit.application.dto.storage.StorageReconciliationDtos.StorageReconciliationReport;
import com.rewit.application.dto.storage.StorageReconciliationDtos.StoredObject;
import com.rewit.application.dto.storage.StorageReconciliationDtos.StoredObjectPage;
import com.rewit.application.port.ObjectStorageListingPort;
import com.rewit.application.port.PlaceMediaRepository;
import com.rewit.application.port.StorageQuarantineRepository;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.model.PlaceMedia;
import com.rewit.domain.model.PlaceMediaObjectKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compara os objetos do namespace de fotografias de locais no storage (places/) com as referências
 * em place_media e produz um diagnóstico seguro. <b>Nunca remove nada fisicamente.</b>
 *
 * <p>Regras:
 * <ul>
 *   <li>Somente {@link PlaceMediaObjectKey#MANAGED_PREFIX} é varrido; objetos fora dele são ignorados.</li>
 *   <li>Chaves no prefixo fora do formato da aplicação são contadas como não reconhecidas.</li>
 *   <li>Qualquer linha em place_media (ACTIVE, REMOVED, FLAGGED, UNDER_REVIEW) protege o objeto de ser órfão.</li>
 *   <li>Candidatos sem referência são registrados na quarentena persistente para grace period e rechecagem.</li>
 * </ul>
 */
public class ReconcilePlaceMediaStorageUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReconcilePlaceMediaStorageUseCase.class);

    private final ObjectStorageListingPort storageListingPort;
    private final PlaceMediaRepository placeMediaRepository;
    private final StorageQuarantineRepository quarantineRepository;
    private final int pageSize;
    private final int maxPages;

    public ReconcilePlaceMediaStorageUseCase(ObjectStorageListingPort storageListingPort,
                                             PlaceMediaRepository placeMediaRepository,
                                             StorageQuarantineRepository quarantineRepository,
                                             int pageSize,
                                             int maxPages) {
        this.storageListingPort = Objects.requireNonNull(storageListingPort, "storageListingPort must not be null");
        this.placeMediaRepository = Objects.requireNonNull(placeMediaRepository, "placeMediaRepository must not be null");
        this.quarantineRepository = Objects.requireNonNull(quarantineRepository, "quarantineRepository must not be null");
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        if (maxPages <= 0) {
            throw new IllegalArgumentException("maxPages must be positive");
        }
        this.pageSize = pageSize;
        this.maxPages = maxPages;
    }

    public StorageReconciliationReport reconcile(String startAfter, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (startAfter != null && !PlaceMediaObjectKey.isInManagedNamespace(startAfter)) {
            throw new IllegalArgumentException("startAfter must belong to the managed prefix");
        }

        String prefix = PlaceMediaObjectKey.MANAGED_PREFIX;
        Set<String> seenKeys = new HashSet<>();
        List<ReviewMediaReference> removedReferences = new ArrayList<>();
        List<OrphanCandidate> orphanCandidates = new ArrayList<>();
        long objectsExamined = 0;
        long outOfNamespace = 0;
        long duplicates = 0;
        long unrecognized = 0;
        long activeReferences = 0;
        int pagesScanned = 0;

        String marker = startAfter;
        boolean complete = false;

        while (pagesScanned < maxPages) {
            StoredObjectPage page = storageListingPort.listObjects(prefix, marker, pageSize);
            pagesScanned++;

            Map<String, StoredObject> managed = new LinkedHashMap<>();
            for (StoredObject object : page.objects()) {
                objectsExamined++;
                if (!PlaceMediaObjectKey.isInManagedNamespace(object.key())) {
                    outOfNamespace++;
                } else if (!seenKeys.add(object.key())) {
                    duplicates++;
                } else if (!PlaceMediaObjectKey.isManagedKey(object.key())) {
                    unrecognized++;
                } else {
                    managed.put(object.key(), object);
                }
            }

            Map<String, PlaceMedia> references = new HashMap<>();
            if (!managed.isEmpty()) {
                for (PlaceMedia media : placeMediaRepository.findByObjectKeyIn(new ArrayList<>(managed.keySet()))) {
                    references.putIfAbsent(media.getObjectKey(), media);
                }
            }

            List<OrphanCandidate> pageCandidates = new ArrayList<>();
            for (StoredObject object : managed.values()) {
                PlaceMedia media = references.get(object.key());
                if (media == null) {
                    pageCandidates.add(new OrphanCandidate(object.key(), object.sizeBytes(), object.lastModified(), now));
                } else if (media.isActive() || media.isUnderReview()) {
                    activeReferences++;
                } else if (media.isRemoved()) {
                    boolean graceElapsed = media.getRemovedAt() != null
                            && !media.getRemovedAt().plus(Duration.ofHours(24)).isAfter(now);
                    if (media.getPurgedAt() == null && graceElapsed) {
                        pageCandidates.add(new OrphanCandidate(object.key(), object.sizeBytes(), object.lastModified(), now));
                    } else {
                        removedReferences.add(new ReviewMediaReference(media.getId(), media.getPlaceId(), media.getObjectKey(),
                                ReviewMediaStatus.REMOVED, media.getCreatedAt(), media.getUpdatedAt()));
                    }
                } else {
                    ReviewMediaStatus refStatus = ReviewMediaStatus.ACTIVE;
                    removedReferences.add(new ReviewMediaReference(media.getId(), media.getPlaceId(), media.getObjectKey(), refStatus,
                            media.getCreatedAt(), media.getUpdatedAt()));
                }
            }
            if (!pageCandidates.isEmpty()) {
                quarantineRepository.recordObservations(pageCandidates);
                orphanCandidates.addAll(pageCandidates);
            }

            if (!page.hasMore()) {
                complete = true;
                break;
            }
            if (marker != null && page.nextStartAfter().compareTo(marker) <= 0) {
                throw new IllegalStateException("Storage listing did not advance past the previous marker");
            }
            marker = page.nextStartAfter();
        }

        StorageReconciliationReport report = new StorageReconciliationReport(
                prefix,
                now,
                startAfter,
                complete ? null : marker,
                complete,
                pagesScanned,
                objectsExamined,
                outOfNamespace,
                duplicates,
                unrecognized,
                activeReferences,
                removedReferences,
                orphanCandidates
        );

        log.debug("Reconciliação de storage de locais (prefixo '{}'): páginas={}, examinados={}, ativos={}, removidos={}, "
                        + "candidatosOrfaos={}, naoReconhecidos={}, foraDoPrefixo={}, duplicados={}, completo={}",
                prefix, pagesScanned, objectsExamined, activeReferences, removedReferences.size(),
                orphanCandidates.size(), unrecognized, outOfNamespace, duplicates, complete);

        return report;
    }
}
