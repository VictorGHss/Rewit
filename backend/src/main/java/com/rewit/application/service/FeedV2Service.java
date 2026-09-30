package com.rewit.application.service;

import com.rewit.application.dto.feed.FeedV2CandidatePage;
import com.rewit.application.port.FeedCandidateRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.feed.FeedCandidate;
import com.rewit.domain.feed.FeedV2Diversifier;
import com.rewit.domain.feed.FeedV2Ranker;
import com.rewit.domain.feed.RankedFeedCandidate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Serviço de aplicação para orquestração determinística do Feed V2 (Step 24.4.1).
 *
 * <p>Pipeline em 4 estágios:
 * <ol>
 *   <li><b>Retrieval</b>: Recupera candidatos elegíveis do PostgreSQL via {@link FeedCandidateRepository}.</li>
 *   <li><b>Ranking</b>: Pontua e ordena via {@link FeedV2Ranker} com base no instante de referência temporal.</li>
 *   <li><b>Diversidade</b>: Aplica espaçamento de autores e alvos via {@link FeedV2Diversifier}.</li>
 *   <li><b>Fatiamento</b>: Recorta a fatia da página em memória sobre a janela ordenada.</li>
 * </ol>
 *
 * <p>Responsabilidades e isolamento:
 * <ul>
 *   <li>Não acessa JPA, EntityManager ou SQL diretamente.</li>
 *   <li>Não constrói DTOs HTTP e não realiza hidratação de perfis ou mídias.</li>
 *   <li>Não interfere no Feed V1 nem em endpoints existentes.</li>
 * </ul>
 */
@Service
public class FeedV2Service {

    private final FeedCandidateRepository feedCandidateRepository;
    private final FeedV2Ranker feedV2Ranker;
    private final FeedV2Diversifier feedV2Diversifier;

    @Autowired
    public FeedV2Service(FeedCandidateRepository feedCandidateRepository) {
        this(feedCandidateRepository, new FeedV2Ranker(), new FeedV2Diversifier());
    }

    public FeedV2Service(
            FeedCandidateRepository feedCandidateRepository,
            FeedV2Ranker feedV2Ranker,
            FeedV2Diversifier feedV2Diversifier
    ) {
        this.feedCandidateRepository = Objects.requireNonNull(feedCandidateRepository, "feedCandidateRepository is required");
        this.feedV2Ranker = Objects.requireNonNull(feedV2Ranker, "feedV2Ranker is required");
        this.feedV2Diversifier = Objects.requireNonNull(feedV2Diversifier, "feedV2Diversifier is required");
    }

    /**
     * Orquestra a geração da página de candidatos do Feed V2.
     *
     * @param requesterId   identificador interno do usuário autenticado solicitante
     * @param page          índice da página (base 0)
     * @param size          tamanho da página (1 a 50)
     * @param referenceTime instante de referência temporal explícito para cálculo de recência
     * @return página recortada sobre a janela de candidatos do ranker
     */
    @Transactional(readOnly = true)
    public FeedV2CandidatePage getCandidatePage(UUID requesterId, int page, int size, Instant referenceTime) {
        validateInputs(requesterId, page, size, referenceTime);

        // Stage 1: Retrieval da janela de candidatos
        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requesterId, FeedCandidateRepository.CANDIDATE_WINDOW);

        if (candidates == null || candidates.isEmpty()) {
            return new FeedV2CandidatePage(List.of(), page, size, 0);
        }

        // Stage 2: Ranking puro e determinístico
        List<RankedFeedCandidate> ranked = feedV2Ranker.rank(candidates, referenceTime);

        // Stage 3: Diversidade pura
        List<RankedFeedCandidate> diversified = feedV2Diversifier.diversify(ranked);

        // Stage 4: Fatiamento em memória
        int total = diversified.size();
        long longOffset = (long) page * (long) size;
        if (longOffset >= total) {
            return new FeedV2CandidatePage(List.of(), page, size, total);
        }

        int offset = (int) longOffset;
        int end = Math.min(offset + size, total);
        List<RankedFeedCandidate> pageItems = diversified.subList(offset, end);

        return new FeedV2CandidatePage(pageItems, page, size, total);
    }

    /**
     * Alias de conveniência para {@link #getCandidatePage(UUID, int, int, Instant)}.
     */
    @Transactional(readOnly = true)
    public FeedV2CandidatePage findFeedCandidates(UUID requesterId, int page, int size, Instant referenceTime) {
        return getCandidatePage(requesterId, page, size, referenceTime);
    }

    private void validateInputs(UUID requesterId, int page, int size, Instant referenceTime) {
        if (requesterId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (referenceTime == null) {
            throw new BusinessException("O instante de referência é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_REFERENCE_TIME");
        }
        if (page < 0) {
            throw new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        if (size < 1) {
            throw new BusinessException("O tamanho da página deve ser maior que zero", HttpStatus.BAD_REQUEST, "INVALID_SIZE");
        }
        if (size > 50) {
            throw new BusinessException("O tamanho da página não pode ser superior a 50", HttpStatus.BAD_REQUEST, "PAGE_SIZE_EXCEEDED");
        }
    }
}
