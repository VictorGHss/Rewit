package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.BusinessResponseRepository;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.service.ReviewVisibilityPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewDiscussion;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Respostas oficiais ativas a um comentário raiz (C11), na forma pública: empresa e local representado, nunca o autor
 * pessoal nem o documento da empresa. A autoria do comentário (e {@code isFromOwner}) não aparece aqui.
 *
 * <p>A visibilidade acompanha o comentário e a avaliação: comentário inexistente, que não é raiz, em quarentena ou
 * removido responde {@code 404 DISCUSSION_NOT_FOUND} para todos, sem distinguir o motivo; a avaliação segue
 * {@link ReviewVisibilityPolicy#validateCanAccess}. Ordem cronológica estável (created_at, id).
 */
@Service
public class ListDiscussionBusinessResponsesUseCase {

    private final DiscussionRepository discussionRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewVisibilityPolicy reviewVisibilityPolicy;
    private final BusinessResponseRepository businessResponseRepository;

    public ListDiscussionBusinessResponsesUseCase(DiscussionRepository discussionRepository,
                                                  ReviewRepository reviewRepository,
                                                  ReviewVisibilityPolicy reviewVisibilityPolicy,
                                                  BusinessResponseRepository businessResponseRepository) {
        this.discussionRepository = Objects.requireNonNull(discussionRepository, "DiscussionRepository must not be null");
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "ReviewRepository must not be null");
        this.reviewVisibilityPolicy = Objects.requireNonNull(reviewVisibilityPolicy,
                "ReviewVisibilityPolicy must not be null");
        this.businessResponseRepository = Objects.requireNonNull(businessResponseRepository,
                "BusinessResponseRepository must not be null");
    }

    @Transactional(readOnly = true)
    public PageResult<BusinessResponseView> execute(UUID viewerUserId, UUID discussionId, int page, int size) {
        if (viewerUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (discussionId == null) {
            throw new BusinessException("A discussão é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_DISCUSSION_ID");
        }
        if (page < 0) {
            throw new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        if (size < 1 || size > ListReviewBusinessResponsesUseCase.MAX_PAGE_SIZE) {
            throw new BusinessException("O tamanho da página deve ser entre 1 e 50", HttpStatus.BAD_REQUEST,
                    "INVALID_PAGE_SIZE");
        }

        ReviewDiscussion discussion = discussionRepository.findById(discussionId)
                .filter(candidate -> candidate.getParentId() == null)
                .filter(candidate -> candidate.isActive())
                .orElseThrow(ListDiscussionBusinessResponsesUseCase::discussionNotFound);
        Review review = reviewRepository.findById(discussion.getReviewId())
                .orElseThrow(PublishReviewBusinessResponseUseCase::reviewNotFound);
        reviewVisibilityPolicy.validateCanAccess(review, viewerUserId);

        return businessResponseRepository.findActiveDiscussionResponseViews(discussionId, page, size);
    }

    static BusinessException discussionNotFound() {
        return new BusinessException("Comentário não encontrado ou indisponível", HttpStatus.NOT_FOUND,
                "DISCUSSION_NOT_FOUND");
    }
}
