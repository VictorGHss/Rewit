package com.rewit.application.service;

import com.rewit.application.dto.feed.FeedV2CandidatePage;
import com.rewit.application.dto.feed.FeedV2PageProjection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Facade de aplicação para o Feed V2 (Step 24.4.3).
 *
 * <p>Conecta a orquestração do pipeline de candidatos ({@link FeedV2Service})
 * ao carregamento em lote e projeção pública ({@link FeedV2Hydrator}),
 * mantendo a camada de apresentação desacoplada dos detalhes de orquestração interna.</p>
 */
@Service
public class FeedV2QueryService {

    private final FeedV2Service feedV2Service;
    private final FeedV2Hydrator feedV2Hydrator;

    public FeedV2QueryService(FeedV2Service feedV2Service, FeedV2Hydrator feedV2Hydrator) {
        this.feedV2Service = Objects.requireNonNull(feedV2Service, "feedV2Service is required");
        this.feedV2Hydrator = Objects.requireNonNull(feedV2Hydrator, "feedV2Hydrator is required");
    }

    /**
     * Executa o pipeline completo do Feed V2:
     * retrieval → ranking → diversidade → fatiamento de candidatos → hidratação em lote.
     *
     * @param requesterId identificador do usuário solicitante
     * @param page número da página (0-indexed)
     * @param size tamanho da página (1 a 50)
     * @param referenceTime instante de referência temporal para cálculo de recência
     * @return projeção pública da página solicitada
     */
    @Transactional(readOnly = true)
    public FeedV2PageProjection getFeed(UUID requesterId, int page, int size, Instant referenceTime) {
        FeedV2CandidatePage candidatePage = feedV2Service.getCandidatePage(requesterId, page, size, referenceTime);
        return feedV2Hydrator.hydrate(candidatePage, requesterId);
    }
}
