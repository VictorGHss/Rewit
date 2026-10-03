package com.rewit.application.usecase;

import com.rewit.application.dto.storage.StorageReconciliationDtos.OrphanCandidate;
import com.rewit.application.dto.storage.StorageReconciliationDtos.ReviewMediaReference;
import com.rewit.application.dto.storage.StorageReconciliationDtos.StorageReconciliationReport;
import com.rewit.application.dto.storage.StorageReconciliationDtos.StoredObject;
import com.rewit.application.dto.storage.StorageReconciliationDtos.StoredObjectPage;
import com.rewit.application.port.ObjectStorageListingPort;
import com.rewit.application.port.ReviewMediaRepository;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.model.ReviewMediaObjectKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Compara os objetos do namespace de mídias no storage com as referências em review_media e
 * produz um diagnóstico (Step 28.1). <b>Nunca remove nada.</b>
 *
 * <p>Regras do contrato:
 * <ul>
 *   <li>Somente {@link ReviewMediaObjectKey#MANAGED_PREFIX} é varrido; objetos fora dele são ignorados
 *       mesmo que a listagem os devolva, e chaves no prefixo fora do formato gerado pela aplicação
 *       são contadas como não reconhecidas, nunca como órfãs.</li>
 *   <li>Qualquer linha em review_media, em qualquer status, impede a classificação como órfão.
 *       Linhas REMOVED com objeto presente são reportadas à parte; não há política de retenção aqui.</li>
 *   <li>O resultado é observação, não autorização: veja {@link OrphanCandidate}.</li>
 *   <li>Varredura em lotes: cada página é cruzada com o banco por uma consulta limitada às suas chaves,
 *       e uma execução lê no máximo {@code maxPages} páginas, retornando o marcador para retomar.</li>
 * </ul>
 *
 * <p>Depende apenas de {@link ObjectStorageListingPort}, que não oferece escrita nem remoção.
 * Classe deliberadamente livre de Spring, como os demais use cases operacionais: não há
 * agendamento nem exposição HTTP neste passo.
 */
public class ReconcileReviewMediaStorageUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReconcileReviewMediaStorageUseCase.class);

    private final ObjectStorageListingPort storageListingPort;
    private final ReviewMediaRepository reviewMediaRepository;
    private final int pageSize;
    private final int maxPages;

    public ReconcileReviewMediaStorageUseCase(ObjectStorageListingPort storageListingPort,
                                              ReviewMediaRepository reviewMediaRepository,
                                              int pageSize,
                                              int maxPages) {
        this.storageListingPort = Objects.requireNonNull(storageListingPort, "storageListingPort must not be null");
        this.reviewMediaRepository = Objects.requireNonNull(reviewMediaRepository, "reviewMediaRepository must not be null");
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        if (maxPages <= 0) {
            throw new IllegalArgumentException("maxPages must be positive");
        }
        this.pageSize = pageSize;
        this.maxPages = maxPages;
    }

    /**
     * Executa uma passada de reconciliação a partir de {@code startAfter}.
     *
     * @param startAfter marcador retornado por uma execução anterior ({@code nextStartAfter}),
     *                   ou null para começar do início do prefixo gerenciado
     * @param now        instante de referência registrado como {@code observedAt}
     * @return diagnóstico da passada
     */
    public StorageReconciliationReport reconcile(String startAfter, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (startAfter != null && !ReviewMediaObjectKey.isInManagedNamespace(startAfter)) {
            throw new IllegalArgumentException("startAfter must belong to the managed prefix");
        }

        String prefix = ReviewMediaObjectKey.MANAGED_PREFIX;
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

            // Chaves gerenciadas e inéditas desta página, na ordem da listagem
            Map<String, StoredObject> managed = new LinkedHashMap<>();
            for (StoredObject object : page.objects()) {
                objectsExamined++;
                if (!ReviewMediaObjectKey.isInManagedNamespace(object.key())) {
                    outOfNamespace++;
                } else if (!seenKeys.add(object.key())) {
                    duplicates++;
                } else if (!ReviewMediaObjectKey.isManagedKey(object.key())) {
                    unrecognized++;
                } else {
                    managed.put(object.key(), object);
                }
            }

            Map<String, ReviewMediaReference> references = new HashMap<>();
            if (!managed.isEmpty()) {
                for (ReviewMediaReference reference : reviewMediaRepository.findReferencesByObjectKeys(managed.keySet())) {
                    references.putIfAbsent(reference.objectKey(), reference);
                }
            }

            for (StoredObject object : managed.values()) {
                ReviewMediaReference reference = references.get(object.key());
                if (reference == null) {
                    orphanCandidates.add(new OrphanCandidate(object.key(), object.sizeBytes(), object.lastModified(), now));
                } else if (reference.status() == ReviewMediaStatus.ACTIVE) {
                    activeReferences++;
                } else {
                    removedReferences.add(reference);
                }
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

        // Somente contagens: chaves e metadados ficam no relatório, fora do log
        log.info("Reconciliação de storage (prefixo '{}'): páginas={}, examinados={}, ativos={}, removidos={}, "
                        + "candidatosOrfaos={}, naoReconhecidos={}, foraDoPrefixo={}, duplicados={}, completo={}",
                prefix, pagesScanned, objectsExamined, activeReferences, removedReferences.size(),
                orphanCandidates.size(), unrecognized, outOfNamespace, duplicates, complete);

        return report;
    }
}
