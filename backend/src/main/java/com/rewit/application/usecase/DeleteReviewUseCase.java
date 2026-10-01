package com.rewit.application.usecase;

import com.rewit.application.port.RateableTargetStatsRepository;
import com.rewit.application.port.ReviewMediaRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.service.ReputationService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewMedia;
import com.rewit.domain.model.ReviewTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Caso de uso para exclusão de avaliação pelo próprio autor via Soft Delete (Step 25.2).
 *
 * <p>Invariantes e regras aplicadas:
 * 1. Carregamento com lock pessimista (SELECT ... FOR UPDATE) na mesma transação.
 * 2. Validação estrita de autorização anti-IDOR (requesterId == review.userId).
 * 3. Validação de estado: permite transição a partir de ACTIVE ou UNDER_REVIEW. Rejeita se já REMOVED.
 * 4. Não apaga fisicamente nenhum registro (reviews, review_targets, reactions, reports, check_ins permanecem intactos).
 * 5. Recálculo transacional atômico de RateableTargetStats para todos os alvos avaliados em ordem determinística (targetId ASC).
 * 6. Recálculo factual de UserReputation expurgando os sinais da avaliação removida.
 * 7. Marcação lógica de ReviewMedia para REMOVED quando o repositório estiver presente (sem exclusão física de storage).
 */
@Service
public class DeleteReviewUseCase {

    private final ReviewRepository reviewRepository;
    private final RateableTargetStatsRepository rateableTargetStatsRepository;
    private final ReputationService reputationService;
    private final ReviewMediaRepository reviewMediaRepository;

    @Autowired
    public DeleteReviewUseCase(
            ReviewRepository reviewRepository,
            RateableTargetStatsRepository rateableTargetStatsRepository,
            ReputationService reputationService,
            @Autowired(required = false) ReviewMediaRepository reviewMediaRepository
    ) {
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "reviewRepository must not be null");
        this.rateableTargetStatsRepository = Objects.requireNonNull(rateableTargetStatsRepository, "rateableTargetStatsRepository must not be null");
        this.reputationService = Objects.requireNonNull(reputationService, "reputationService must not be null");
        this.reviewMediaRepository = reviewMediaRepository;
    }

    public DeleteReviewUseCase(
            ReviewRepository reviewRepository,
            RateableTargetStatsRepository rateableTargetStatsRepository,
            ReputationService reputationService
    ) {
        this(reviewRepository, rateableTargetStatsRepository, reputationService, null);
    }

    @Transactional
    public void execute(UUID reviewId, UUID requesterId, Instant now) {
        if (reviewId == null) {
            throw new BusinessException("Identificador da avaliação é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_REVIEW_ID");
        }
        if (requesterId == null) {
            throw new BusinessException("Identificador do solicitante é obrigatório", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (now == null) {
            throw new BusinessException("O timestamp de atualização é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_UPDATE_TIMESTAMP");
        }

        // 1. Carregamento transacional da Review com Lock Pessimista de Escrita
        Review review = reviewRepository.findByIdForUpdate(reviewId)
                .orElseThrow(() -> new BusinessException("Avaliação não encontrada", HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));

        // 2. Autorização anti-IDOR
        if (!review.getUserId().equals(requesterId)) {
            throw new BusinessException("Apenas o autor pode excluir a avaliação", HttpStatus.FORBIDDEN, "FORBIDDEN");
        }

        // 3. Validação de estado
        if (review.getStatus() == ReviewStatus.REMOVED) {
            throw new BusinessException("A avaliação já se encontra removida", HttpStatus.UNPROCESSABLE_CONTENT, "REVIEW_ALREADY_REMOVED");
        }

        // 4. Mutação de Domínio para Soft Delete
        review.markRemovedByAuthor(now);

        // 5. Persistência da Review com status REMOVED
        reviewRepository.save(review);

        // 6. Recálculo de RateableTargetStats para todos os alvos da review em ordem determinística (targetId ASC)
        review.getTargets().stream()
                .map(target -> target.getTargetId())
                .distinct()
                .sorted()
                .forEach(rateableTargetStatsRepository::recalculateAndSave);

        // 7. Recálculo da reputação do autor (expurga a review e votos úteis do saldo ativo)
        reputationService.recalculateAndSave(review.getUserId());

        // 8. Marcação lógica de mídia associada como REMOVED (sem hard delete de blobs no storage)
        if (reviewMediaRepository != null) {
            List<ReviewMedia> activeMedia = reviewMediaRepository.findActiveByReviewId(review.getId());
            for (ReviewMedia media : activeMedia) {
                media.markRemoved();
                reviewMediaRepository.save(media);
            }
        }
    }
}
