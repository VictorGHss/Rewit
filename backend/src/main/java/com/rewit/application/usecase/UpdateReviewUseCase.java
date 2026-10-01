package com.rewit.application.usecase;

import com.rewit.application.dto.ReviewDto.UpdateReviewCommand;
import com.rewit.application.port.RateableTargetStatsRepository;
import com.rewit.application.port.ReviewReactionRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.ReviewTargetRepository;
import com.rewit.application.service.ReputationService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewTarget;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Caso de uso para atualização controlada de avaliações pelo próprio autor (Step 25.2).
 *
 * <p>Invariantes e regras aplicadas:
 * 1. Carregamento com lock pessimista (SELECT ... FOR UPDATE) na mesma transação.
 * 2. Validação estrita de autorização anti-IDOR (requesterId == review.userId).
 * 3. Validação de estado: apenas avaliações com status ACTIVE podem ser editadas.
 * 4. Janela temporal de tolerância: now <= createdAt + 24h.
 * 5. Bloqueio de alteração de ratings caso a avaliação já possua votos de Helpful (> 0).
 * 6. Estrutura de alvos imutável (rejeita targetId não pertencente à review).
 * 7. Recálculo transacional atômico de RateableTargetStats em ordem determinística (targetId ASC)
 *    estritamente para alvos cuja nota foi efetivamente modificada.
 * 8. Recálculo factual de UserReputation se e somente se o status de anonimato sofrer alteração.
 */
@Service
public class UpdateReviewUseCase {

    private final ReviewRepository reviewRepository;
    private final ReviewTargetRepository reviewTargetRepository;
    private final ReviewReactionRepository reviewReactionRepository;
    private final RateableTargetStatsRepository rateableTargetStatsRepository;
    private final ReputationService reputationService;

    public UpdateReviewUseCase(
            ReviewRepository reviewRepository,
            ReviewTargetRepository reviewTargetRepository,
            ReviewReactionRepository reviewReactionRepository,
            RateableTargetStatsRepository rateableTargetStatsRepository,
            ReputationService reputationService
    ) {
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "reviewRepository must not be null");
        this.reviewTargetRepository = Objects.requireNonNull(reviewTargetRepository, "reviewTargetRepository must not be null");
        this.reviewReactionRepository = Objects.requireNonNull(reviewReactionRepository, "reviewReactionRepository must not be null");
        this.rateableTargetStatsRepository = Objects.requireNonNull(rateableTargetStatsRepository, "rateableTargetStatsRepository must not be null");
        this.reputationService = Objects.requireNonNull(reputationService, "reputationService must not be null");
    }

    @Transactional
    public Review execute(UUID reviewId, UUID requesterId, UpdateReviewCommand command) {
        if (reviewId == null) {
            throw new BusinessException("Identificador da avaliação é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_REVIEW_ID");
        }
        if (requesterId == null) {
            throw new BusinessException("Identificador do solicitante é obrigatório", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (command == null) {
            throw new BusinessException("Comando de atualização é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_COMMAND");
        }
        if (command.now() == null) {
            throw new BusinessException("O timestamp de atualização é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_UPDATE_TIMESTAMP");
        }

        // 1. Carregamento transacional da Review com Lock Pessimista de Escrita
        Review review = reviewRepository.findByIdForUpdate(reviewId)
                .orElseThrow(() -> new BusinessException("Avaliação não encontrada", HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));

        // 2. Autorização anti-IDOR
        if (!review.getUserId().equals(requesterId)) {
            throw new BusinessException("Apenas o autor pode editar a avaliação", HttpStatus.FORBIDDEN, "FORBIDDEN");
        }

        // 3. Validação de estado
        if (review.getStatus() == ReviewStatus.REMOVED) {
            throw new BusinessException("A avaliação já se encontra removida", HttpStatus.UNPROCESSABLE_CONTENT, "REVIEW_ALREADY_REMOVED");
        }
        if (review.getStatus() != ReviewStatus.ACTIVE) {
            throw new BusinessException("Avaliações sob moderação não podem ser editadas", HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_REVIEW_STATUS_FOR_EDIT");
        }

        // 4. Janela temporal de tolerância de 24 horas
        Instant maxEditWindow = review.getCreatedAt().plus(24, ChronoUnit.HOURS);
        if (command.now().isBefore(review.getCreatedAt())) {
            throw new BusinessException("O timestamp de atualização não pode ser anterior à data de criação", HttpStatus.BAD_REQUEST, "INVALID_UPDATE_TIMESTAMP");
        }
        if (command.now().isAfter(maxEditWindow)) {
            throw new BusinessException("A janela de edição de 24 horas expirou", HttpStatus.UNPROCESSABLE_CONTENT, "EDIT_WINDOW_EXPIRED");
        }

        // 5. Identificação precisa de alvos com alteração real de nota
        List<UUID> targetsWithChangedRating = new ArrayList<>();
        Map<UUID, BigDecimal> targetRatings = command.targetRatings() != null ? command.targetRatings() : Map.of();

        if (!targetRatings.isEmpty()) {
            for (Map.Entry<UUID, BigDecimal> entry : targetRatings.entrySet()) {
                UUID targetId = entry.getKey();
                BigDecimal newRating = entry.getValue();

                if (targetId == null) {
                    throw new BusinessException("O identificador do alvo avaliado é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_TARGET_ID");
                }

                ReviewTarget existingTarget = review.getTargets().stream()
                        .filter(t -> t.getTargetId().equals(targetId))
                        .findFirst()
                        .orElseThrow(() -> new BusinessException("Alvo avaliado não pertence a esta avaliação", HttpStatus.UNPROCESSABLE_CONTENT, "TARGET_NOT_FOUND"));

                if (newRating != null && newRating.compareTo(existingTarget.getRating()) != 0) {
                    targetsWithChangedRating.add(targetId);
                }
            }
        }

        // 6. Bloqueio de Helpful: nota não pode ser alterada se houver reações úteis
        if (!targetsWithChangedRating.isEmpty()) {
            long helpfulCount = reviewReactionRepository.countHelpful(review.getId());
            if (helpfulCount > 0) {
                throw new BusinessException(
                        "Não é permitido alterar notas de uma avaliação que já possui votos de útil",
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "RATING_EDIT_BLOCKED_BY_HELPFUL"
                );
            }
        }

        // 7. Rastreamento de alteração de anonimato para cômputo de reputação
        boolean oldAnonymous = review.isAnonymous();
        Boolean newAnonymous = command.isAnonymous();
        boolean anonymousChanged = (newAnonymous != null && newAnonymous != oldAnonymous);

        // 8. Mutação de Domínio
        review.editContent(
                command.experienceText(),
                command.targetRatings(),
                command.isAnonymous(),
                command.visibility(),
                command.now()
        );

        // 9. Persistência
        Review savedReview = reviewRepository.save(review);
        if (!targetsWithChangedRating.isEmpty()) {
            reviewTargetRepository.saveAll(review.getTargets());
        }

        // 10. Recálculo atômico de RateableTargetStats em ordem determinística (targetId ASC)
        if (!targetsWithChangedRating.isEmpty()) {
            targetsWithChangedRating.stream()
                    .distinct()
                    .sorted()
                    .forEach(rateableTargetStatsRepository::recalculateAndSave);
        }

        // 11. Recálculo de UserReputation se a elegibilidade factual mudou por anonimato
        if (anonymousChanged) {
            reputationService.recalculateAndSave(review.getUserId());
        }

        return savedReview;
    }
}
