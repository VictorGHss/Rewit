package com.rewit.application.dto.storage;

import com.rewit.domain.enums.ReviewMediaStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Contratos da reconciliação entre o Object Storage e as referências de mídia no PostgreSQL (Step 28.1).
 *
 * <p>Uso exclusivamente interno: nenhum destes tipos é exposto pela API pública do Rewit,
 * pois carregam chaves internas do storage.
 */
public final class StorageReconciliationDtos {

    private StorageReconciliationDtos() {}

    /**
     * Objeto observado na listagem do storage, sem nenhum tipo do SDK.
     *
     * @param key          chave completa do objeto
     * @param sizeBytes    tamanho informado pelo storage
     * @param lastModified instante de última modificação informado pelo storage; pode ser null
     *                     se o provedor não o fornecer
     */
    public record StoredObject(
            String key,
            long sizeBytes,
            Instant lastModified
    ) {
        public StoredObject {
            Objects.requireNonNull(key, "key must not be null");
        }
    }

    /**
     * Página de listagem por prefixo.
     *
     * @param objects        objetos da página, em ordem lexicográfica de chave
     * @param nextStartAfter marcador para a próxima página (a chave após a qual continuar);
     *                       null quando a listagem do prefixo terminou
     */
    public record StoredObjectPage(
            List<StoredObject> objects,
            String nextStartAfter
    ) {
        public StoredObjectPage {
            objects = List.copyOf(Objects.requireNonNull(objects, "objects must not be null"));
        }

        public boolean hasMore() {
            return nextStartAfter != null;
        }
    }

    /**
     * Referência persistida de um objeto, projetada de review_media apenas com colunas existentes.
     */
    public record ReviewMediaReference(
            UUID mediaId,
            UUID reviewId,
            String objectKey,
            ReviewMediaStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /**
     * Objeto sob o namespace gerenciado, com formato de chave da aplicação, sem nenhuma referência
     * em review_media no momento da consulta.
     *
     * <p><b>Órfão observado não é órfão removível.</b> O upload grava o objeto antes de commitar a
     * referência, então uma mídia em criação aparece aqui legitimamente. Uma futura remoção precisa,
     * no mínimo: aguardar um grace period medido a partir de {@code observedAt}/{@code lastModified}
     * e reconsultar o PostgreSQL imediatamente antes do delete. Este contrato não define esse prazo.
     *
     * @param observedAt instante em que a reconciliação observou o objeto sem referência
     */
    public record OrphanCandidate(
            String objectKey,
            long sizeBytes,
            Instant lastModified,
            Instant observedAt
    ) {}

    /**
     * Resultado de diagnóstico de uma execução de reconciliação. Não autoriza nenhuma remoção.
     *
     * @param managedPrefix         prefixo varrido
     * @param observedAt            instante de referência da execução
     * @param startAfter            marcador de início recebido (null = início do prefixo)
     * @param nextStartAfter        marcador para retomar; null quando {@code complete}
     * @param complete              true se o prefixo inteiro foi varrido a partir de {@code startAfter}
     * @param pagesScanned          páginas de storage lidas
     * @param objectsExamined       objetos recebidos do storage, inclusive ignorados
     * @param outOfNamespaceIgnored objetos fora de {@code managedPrefix}, ignorados
     * @param duplicatesIgnored     ocorrências repetidas de uma chave já processada nesta execução
     * @param unrecognizedKeys      chaves no prefixo que não seguem o formato gerado pela aplicação
     * @param activeReferences      objetos com referência ACTIVE
     * @param removedReferences     objetos ainda presentes cuja referência está REMOVED
     * @param orphanCandidates      objetos gerenciados sem referência (somente observação)
     */
    public record StorageReconciliationReport(
            String managedPrefix,
            Instant observedAt,
            String startAfter,
            String nextStartAfter,
            boolean complete,
            int pagesScanned,
            long objectsExamined,
            long outOfNamespaceIgnored,
            long duplicatesIgnored,
            long unrecognizedKeys,
            long activeReferences,
            List<ReviewMediaReference> removedReferences,
            List<OrphanCandidate> orphanCandidates
    ) {
        public StorageReconciliationReport {
            removedReferences = List.copyOf(removedReferences);
            orphanCandidates = List.copyOf(orphanCandidates);
        }
    }
}
