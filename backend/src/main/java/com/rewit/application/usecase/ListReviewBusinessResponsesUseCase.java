package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.BusinessResponseRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.service.ReviewVisibilityPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.Review;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Respostas oficiais ativas a uma avaliação (C11), na forma pública: empresa e local representado, nunca o autor
 * pessoal nem o documento da empresa.
 *
 * <p>Segue a mesma regra de leitura dos comentários ({@link ReviewVisibilityPolicy#validateCanAccess}): avaliação
 * inexistente, em quarentena ou removida responde 404; FOLLOWERS e PRIVATE só para quem pode lê-las. Respostas
 * removidas não aparecem. Ordem cronológica estável (created_at, id).
 */
@Service
public class ListReviewBusinessResponsesUseCase {

    public static final int MAX_PAGE_SIZE = 50;

    private final ReviewRepository reviewRepository;
    private final ReviewVisibilityPolicy reviewVisibilityPolicy;
    private final BusinessResponseRepository businessResponseRepository;

    public ListReviewBusinessResponsesUseCase(ReviewRepository reviewRepository,
                                              ReviewVisibilityPolicy reviewVisibilityPolicy,
                                              BusinessResponseRepository businessResponseRepository) {
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "ReviewRepository must not be null");
        this.reviewVisibilityPolicy = Objects.requireNonNull(reviewVisibilityPolicy,
                "ReviewVisibilityPolicy must not be null");
        this.businessResponseRepository = Objects.requireNonNull(businessResponseRepository,
                "BusinessResponseRepository must not be null");
    }

    @Transactional(readOnly = true)
    public PageResult<BusinessResponseView> execute(UUID viewerUserId, UUID reviewId, int page, int size) {
        if (viewerUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (reviewId == null) {
            throw new BusinessException("A avaliação é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_REVIEW_ID");
        }
        if (page < 0) {
            throw new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException("O tamanho da página deve ser entre 1 e 50", HttpStatus.BAD_REQUEST,
                    "INVALID_PAGE_SIZE");
        }

        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(PublishReviewBusinessResponseUseCase::reviewNotFound);
        reviewVisibilityPolicy.validateCanAccess(review, viewerUserId);

        return businessResponseRepository.findActiveReviewResponseViews(reviewId, page, size);
    }
}
