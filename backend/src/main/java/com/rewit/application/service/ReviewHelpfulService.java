package com.rewit.application.service;

import com.rewit.application.port.ReviewReactionRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.UserFollowRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Review;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Serviço de aplicação para gestão da validação de utilidade (Helpful) em avaliações (Step 16.0).
 */
@Service
public class ReviewHelpfulService {

    private final ReviewReactionRepository reviewReactionRepository;
    private final ReviewRepository reviewRepository;
    private final UserFollowRepository userFollowRepository;
    private final NotificationService notificationService;

    @org.springframework.beans.factory.annotation.Autowired
    public ReviewHelpfulService(
            ReviewReactionRepository reviewReactionRepository,
            ReviewRepository reviewRepository,
            UserFollowRepository userFollowRepository,
            NotificationService notificationService
    ) {
        this.reviewReactionRepository = Objects.requireNonNull(reviewReactionRepository, "reviewReactionRepository must not be null");
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "reviewRepository must not be null");
        this.userFollowRepository = userFollowRepository;
        this.notificationService = notificationService;
    }

    public ReviewHelpfulService(
            ReviewReactionRepository reviewReactionRepository,
            ReviewRepository reviewRepository,
            UserFollowRepository userFollowRepository
    ) {
        this(reviewReactionRepository, reviewRepository, userFollowRepository, null);
    }

    public record HelpfulResult(boolean helpful, long helpfulCount) {}

    /**
     * Marca uma avaliação como Helpful de forma segura e idempotente.
     */
    @Transactional
    public HelpfulResult addHelpful(UUID reviewId, UUID requesterUserId) {
        Review review = validateAndGetReview(reviewId, requesterUserId);
        boolean added = reviewReactionRepository.addHelpful(review.getId(), requesterUserId);
        if (added && notificationService != null) {
            notificationService.notifyReviewHelpful(review.getId(), review.getUserId());
        }
        long count = reviewReactionRepository.countHelpful(review.getId());
        return new HelpfulResult(true, count);
    }

    /**
     * Remove a marcação de Helpful de uma avaliação de forma idempotente.
     */
    @Transactional
    public HelpfulResult removeHelpful(UUID reviewId, UUID requesterUserId) {
        Review review = validateAndGetReview(reviewId, requesterUserId);
        reviewReactionRepository.removeHelpful(review.getId(), requesterUserId);
        long count = reviewReactionRepository.countHelpful(review.getId());
        return new HelpfulResult(false, count);
    }

    private Review validateAndGetReview(UUID reviewId, UUID requesterUserId) {
        if (requesterUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (reviewId == null) {
            throw new BusinessException("Identificador de avaliação obrigatório", HttpStatus.BAD_REQUEST, "MISSING_REVIEW_ID");
        }

        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException("Avaliação não encontrada", HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));

        if (review.getStatus() != ReviewStatus.ACTIVE) {
            throw new BusinessException("Avaliação não encontrada", HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND");
        }

        if (requesterUserId.equals(review.getUserId())) {
            throw new BusinessException("O autor não pode marcar sua própria avaliação como útil", HttpStatus.BAD_REQUEST, "SELF_HELPFUL_FORBIDDEN");
        }

        String visibility = review.getVisibility() != null ? review.getVisibility() : "PUBLIC";
        if ("PRIVATE".equalsIgnoreCase(visibility)) {
            throw new BusinessException("Acesso negado a esta avaliação privada", HttpStatus.FORBIDDEN, "FORBIDDEN");
        } else if ("FOLLOWERS".equalsIgnoreCase(visibility)) {
            boolean isFollower = userFollowRepository != null && userFollowRepository.isFollowing(requesterUserId, review.getUserId());
            if (!isFollower) {
                throw new BusinessException("Esta avaliação é visível apenas para seguidores", HttpStatus.FORBIDDEN, "FORBIDDEN");
            }
        }

        return review;
    }
}
