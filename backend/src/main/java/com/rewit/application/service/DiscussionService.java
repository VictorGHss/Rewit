package com.rewit.application.service;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.discussion.DiscussionDtos.CreateDiscussionCommand;
import com.rewit.application.dto.discussion.DiscussionDtos.DiscussionView;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.application.port.RateLimiter;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewDiscussion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Serviço de aplicação para gerenciamento de discussões e comentários de avaliações (Step 20.0).
 */
@Service
public class DiscussionService {

    private final DiscussionRepository discussionRepository;
    private final ReviewRepository reviewRepository;
    private final AccountStatusPolicy accountStatusPolicy;
    private final ReviewVisibilityPolicy reviewVisibilityPolicy;
    private final RateLimiter rateLimiter;
    private final NotificationService notificationService;

    @Autowired
    public DiscussionService(
            DiscussionRepository discussionRepository,
            ReviewRepository reviewRepository,
            AccountStatusPolicy accountStatusPolicy,
            ReviewVisibilityPolicy reviewVisibilityPolicy,
            RateLimiter rateLimiter,
            NotificationService notificationService
    ) {
        this.discussionRepository = Objects.requireNonNull(discussionRepository, "DiscussionRepository must not be null");
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "ReviewRepository must not be null");
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
        this.reviewVisibilityPolicy = Objects.requireNonNull(reviewVisibilityPolicy, "ReviewVisibilityPolicy must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "RateLimiter must not be null");
        this.notificationService = notificationService;
    }

    public DiscussionService(
            DiscussionRepository discussionRepository,
            ReviewRepository reviewRepository,
            AccountStatusPolicy accountStatusPolicy,
            ReviewVisibilityPolicy reviewVisibilityPolicy,
            RateLimiter rateLimiter
    ) {
        this(discussionRepository, reviewRepository, accountStatusPolicy, reviewVisibilityPolicy, rateLimiter, null);
    }

    /**
     * Cria um novo comentário (raiz ou resposta com parentId) para uma avaliação.
     */
    @Transactional
    public DiscussionView createDiscussion(CreateDiscussionCommand cmd) {
        Objects.requireNonNull(cmd, "CreateDiscussionCommand cannot be null");
        if (cmd.authorUserId() == null) {
            throw new BusinessException("O autor é obrigatório", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (cmd.reviewId() == null) {
            throw new BusinessException("A avaliação é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_REVIEW_ID");
        }

        accountStatusPolicy.requireOperational(cmd.authorUserId());

        // 1. Rate limiting defensivo contra spam de comentários
        rateLimiter.acquireOrThrow(RateLimitedAction.DISCUSSION_CREATION, RateLimitSubject.ofUser(cmd.authorUserId()));

        // 2. Busca a Review e valida status e visibilidade
        Review review = reviewRepository.findById(cmd.reviewId())
                .orElseThrow(() -> new BusinessException("Avaliação não encontrada", HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));

        reviewVisibilityPolicy.validateCanAccess(review, cmd.authorUserId());

        // 3. Validação de parentId e regra de nesting (máximo 1 nível de resposta)
        ReviewDiscussion parent = null;
        if (cmd.parentId() != null) {
            parent = discussionRepository.findById(cmd.parentId())
                    .orElseThrow(() -> new BusinessException("Comentário pai não encontrado", HttpStatus.NOT_FOUND, "DISCUSSION_NOT_FOUND"));

            if (!parent.isActive()) {
                throw new BusinessException("Comentário pai não encontrado ou indisponível", HttpStatus.NOT_FOUND, "DISCUSSION_NOT_FOUND");
            }

            if (!parent.getReviewId().equals(cmd.reviewId())) {
                throw new BusinessException("Comentário pai pertence a outra avaliação", HttpStatus.BAD_REQUEST, "INVALID_PARENT_DISCUSSION");
            }

            if (parent.getParentId() != null) {
                throw new BusinessException("Nível máximo de encadeamento de discussões atingido", HttpStatus.BAD_REQUEST, "DISCUSSION_NESTING_LIMIT_EXCEEDED");
            }
        }

        // 4. Projeção estrita de isFromOwner calculada exclusivamente no backend
        boolean isFromOwner = cmd.authorUserId().equals(review.getUserId());

        // 5. Instanciação e persistência do comentário
        ReviewDiscussion discussion = new ReviewDiscussion(
                null,
                cmd.reviewId(),
                cmd.authorUserId(),
                cmd.parentId(),
                cmd.content(),
                isFromOwner
        );

        ReviewDiscussion saved = discussionRepository.save(discussion);

        // 6. Disparo de notificações de acordo com a hierarquia do comentário
        if (notificationService != null) {
            if (parent == null) {
                notificationService.notifyNewDiscussion(review.getId(), review.getUserId(), cmd.authorUserId(), saved.getId());
            } else {
                boolean maskActor = review.isAnonymous() && isFromOwner;
                // parent é sempre raiz (o nesting acima recusa resposta de resposta): o id dele é a raiz da thread
                notificationService.notifyDiscussionReply(review.getId(), parent.getUserId(), cmd.authorUserId(), saved.getId(),
                        parent.getId(), maskActor);
            }
        }

        return DiscussionView.fromDomain(saved, review.isAnonymous());
    }

    /**
     * Consulta discussões ativas de uma avaliação de forma paginada e cronológica (created_at ASC, id ASC).
     */
    @Transactional(readOnly = true)
    public PageResult<DiscussionView> findDiscussionsByReviewId(UUID reviewId, UUID requesterUserId, int page, int size) {
        if (reviewId == null) {
            throw new BusinessException("A avaliação é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_REVIEW_ID");
        }
        if (requesterUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (page < 0) {
            throw new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        if (size < 1 || size > 50) {
            throw new BusinessException("O tamanho da página deve ser entre 1 e 50", HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE");
        }

        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException("Avaliação não encontrada", HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));

        reviewVisibilityPolicy.validateCanAccess(review, requesterUserId);

        PageResult<ReviewDiscussion> pagedDiscussions = discussionRepository.findActiveByReviewId(reviewId, page, size);

        List<DiscussionView> views = pagedDiscussions.content().stream()
                .map(d -> DiscussionView.fromDomain(d, review.isAnonymous()))
                .toList();

        return PageResult.of(views, pagedDiscussions.pageNumber(), pagedDiscussions.pageSize(), pagedDiscussions.totalElements());
    }

    /**
     * Remove um comentário via soft delete (status = 'REMOVED').
     * Apenas o autor do comentário pode removê-lo. Operação idempotente.
     */
    @Transactional
    public void deleteDiscussion(UUID discussionId, UUID requesterUserId) {
        if (discussionId == null) {
            throw new BusinessException("Identificador de discussão obrigatório", HttpStatus.BAD_REQUEST, "MISSING_DISCUSSION_ID");
        }
        if (requesterUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }

        accountStatusPolicy.requireOperational(requesterUserId);

        // Lock da discussão: serializa a exclusão com a auto-quarentena e com a moderação do mesmo comentário
        ReviewDiscussion discussion = discussionRepository.findByIdForUpdate(discussionId)
                .orElseThrow(() -> new BusinessException("Comentário não encontrado", HttpStatus.NOT_FOUND, "DISCUSSION_NOT_FOUND"));

        if (!discussion.getUserId().equals(requesterUserId)) {
            throw new BusinessException("Apenas o autor pode remover o comentário", HttpStatus.FORBIDDEN, "FORBIDDEN");
        }

        // Em análise: só a moderação encerra a quarentena (as denúncias pendentes dependem dessa decisão)
        if (discussion.isUnderReview()) {
            throw ReviewDiscussion.underReviewMutationDenied();
        }

        if (!discussion.isActive()) {
            return; // REMOVED: idempotente
        }

        discussion.markRemoved();
        discussionRepository.save(discussion);
    }
}
