package com.rewit.application.service;

import com.rewit.application.port.UserFollowRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Review;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Política unificada de autorização de leitura e acesso contextual a avaliações.
 * Garante aplicação uniforme das regras de status (ACTIVE vs UNDER_REVIEW/REMOVED)
 * e visibilidade (PUBLIC, FOLLOWERS, PRIVATE).
 */
@Component
public class ReviewVisibilityPolicy {

    private final UserFollowRepository userFollowRepository;

    public ReviewVisibilityPolicy(UserFollowRepository userFollowRepository) {
        this.userFollowRepository = Objects.requireNonNull(userFollowRepository, "UserFollowRepository must not be null");
    }

    /**
     * Valida se um usuário solicitante possui acesso legítimo à avaliação.
     *
     * @param review a avaliação a ser verificada
     * @param requesterUserId o identificador do usuário autenticado
     * @throws BusinessException com 404 se a avaliação não estiver ACTIVE,
     *                           ou 403 se o solicitante não atender às regras de visibilidade
     */
    public void validateCanAccess(Review review, UUID requesterUserId) {
        if (review == null || review.getStatus() != ReviewStatus.ACTIVE) {
            throw new BusinessException("Avaliação não encontrada", HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND");
        }

        String visibility = review.getVisibility() != null ? review.getVisibility() : "PUBLIC";
        switch (visibility.toUpperCase()) {
            case "PUBLIC" -> {
                // Público: qualquer usuário autenticado possui acesso legítimo
            }
            case "FOLLOWERS" -> {
                boolean isAuthor = requesterUserId != null && requesterUserId.equals(review.getUserId());
                boolean isFollower = requesterUserId != null && userFollowRepository.isFollowing(requesterUserId, review.getUserId());
                if (!isAuthor && !isFollower) {
                    throw new BusinessException("Esta avaliação é visível apenas para seguidores", HttpStatus.FORBIDDEN, "FORBIDDEN");
                }
            }
            case "PRIVATE" -> {
                boolean isAuthor = requesterUserId != null && requesterUserId.equals(review.getUserId());
                if (!isAuthor) {
                    throw new BusinessException("Acesso negado a esta avaliação privada", HttpStatus.FORBIDDEN, "FORBIDDEN");
                }
            }
            default -> throw new BusinessException("Acesso negado a esta avaliação", HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }
}
