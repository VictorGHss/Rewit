package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseView;
import com.rewit.application.dto.business.BusinessResponseDtos.PublishDiscussionBusinessResponseCommand;
import com.rewit.application.port.BusinessResponseRepository;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.application.port.RateLimiter;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.application.service.ReviewVisibilityPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.BusinessResponse;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewDiscussion;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Publica a resposta oficial de uma empresa a um comentário raiz de uma avaliação (C11).
 *
 * <p>Mesmas exigências da resposta à avaliação ({@link PublishReviewBusinessResponseUseCase}): administrador atual de
 * conta aprovada, local escolhido explicitamente, ativo, vinculado agora à conta e associado à avaliação do comentário,
 * travado durante a publicação; avaliação legível pelo ator e pública. Sem dependência de role de plataforma.
 *
 * <p>O comentário segue a regra de leitura das threads ({@code DiscussionThreadQueryService}): inexistente ou em
 * quarentena responde {@code 404 DISCUSSION_NOT_FOUND} para todos, sem revelar o motivo. Só depois de confirmado o
 * acesso à avaliação, comentário que não é raiz ou removido responde {@code 409 DISCUSSION_NOT_RESPONDABLE}.
 * {@code ReviewDiscussion.isFromOwner} não participa: a identidade comercial é a conta e o local da resposta.
 *
 * <p>Uma resposta ativa por empresa e comentário; a garantia é o índice parcial
 * {@code uq_business_responses_discussion_active}, cuja violação vira {@code 409 BUSINESS_RESPONSE_ALREADY_EXISTS}.
 * A resposta à avaliação e as respostas a outros comentários não contam para esse limite.
 */
@Service
public class PublishDiscussionBusinessResponseUseCase {

    static final String ACTIVE_DISCUSSION_RESPONSE_INDEX = "uq_business_responses_discussion_active";

    private final BusinessRepresentationPolicy representationPolicy;
    private final DiscussionRepository discussionRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewVisibilityPolicy reviewVisibilityPolicy;
    private final BusinessResponseRepository businessResponseRepository;
    private final RateLimiter rateLimiter;

    public PublishDiscussionBusinessResponseUseCase(BusinessRepresentationPolicy representationPolicy,
                                                    DiscussionRepository discussionRepository,
                                                    ReviewRepository reviewRepository,
                                                    ReviewVisibilityPolicy reviewVisibilityPolicy,
                                                    BusinessResponseRepository businessResponseRepository,
                                                    RateLimiter rateLimiter) {
        this.representationPolicy = Objects.requireNonNull(representationPolicy,
                "BusinessRepresentationPolicy must not be null");
        this.discussionRepository = Objects.requireNonNull(discussionRepository, "DiscussionRepository must not be null");
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "ReviewRepository must not be null");
        this.reviewVisibilityPolicy = Objects.requireNonNull(reviewVisibilityPolicy,
                "ReviewVisibilityPolicy must not be null");
        this.businessResponseRepository = Objects.requireNonNull(businessResponseRepository,
                "BusinessResponseRepository must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "RateLimiter must not be null");
    }

    @Transactional
    public BusinessResponseView execute(PublishDiscussionBusinessResponseCommand command) {
        return execute(command, Instant.now());
    }

    @Transactional
    public BusinessResponseView execute(PublishDiscussionBusinessResponseCommand command, Instant now) {
        Objects.requireNonNull(command, "PublishDiscussionBusinessResponseCommand must not be null");
        if (command.actorUserId() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (command.discussionId() == null) {
            throw new BusinessException("A discussão é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_DISCUSSION_ID");
        }
        if (command.placeId() == null) {
            throw new BusinessException("O local representado é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_PLACE_ID");
        }
        // Conteúdo validado antes de qualquer I/O
        String content = BusinessResponse.normalizeContent(command.content());
        Instant publishedAt = now != null ? now : Instant.now();

        // Autorização antes de ler o alvo, como na resposta à avaliação: quem não age por uma conta aprovada não
        // descobre nada sobre comentários
        BusinessAccount account = representationPolicy.requireApprovedAccount(command.actorUserId(),
                command.businessAccountId());

        rateLimiter.acquireOrThrow(RateLimitedAction.BUSINESS_RESPONSE_CREATION,
                RateLimitSubject.ofUser(command.actorUserId()));

        ReviewDiscussion discussion = discussionRepository.findById(command.discussionId())
                .filter(candidate -> candidate.getStatus() != DiscussionStatus.UNDER_REVIEW)
                .orElseThrow(ListDiscussionBusinessResponsesUseCase::discussionNotFound);
        Review review = reviewRepository.findById(discussion.getReviewId())
                .orElseThrow(PublishReviewBusinessResponseUseCase::reviewNotFound);
        reviewVisibilityPolicy.validateCanAccess(review, command.actorUserId());
        if (discussion.getParentId() != null || !discussion.isActive()) {
            throw new BusinessException("Este comentário não aceita respostas oficiais", HttpStatus.CONFLICT,
                    "DISCUSSION_NOT_RESPONDABLE");
        }
        if (!PublishReviewBusinessResponseUseCase.isRespondable(review)) {
            throw new BusinessException("Esta avaliação não aceita respostas oficiais", HttpStatus.CONFLICT,
                    "REVIEW_NOT_RESPONDABLE");
        }

        Set<UUID> reviewPlaceIds = reviewRepository.findAssociatedPlaceIds(review.getId());
        Place place = representationPolicy.lockRepresentedPlace(account, command.placeId(), reviewPlaceIds);

        if (businessResponseRepository.findActive(review.getId(), discussion.getId(), account.getId()).isPresent()) {
            throw alreadyExists();
        }
        BusinessResponse response = BusinessResponse.publish(review.getId(), discussion.getId(), account.getId(),
                place.getId(), command.actorUserId(), content, publishedAt);
        try {
            return BusinessResponseView.of(businessResponseRepository.save(response), account, place);
        } catch (DataIntegrityViolationException ex) {
            if (CreateBusinessAccountUseCase.violates(ex, ACTIVE_DISCUSSION_RESPONSE_INDEX)) {
                throw alreadyExists();
            }
            throw ex;
        }
    }

    private static BusinessException alreadyExists() {
        return PublishReviewBusinessResponseUseCase.alreadyExists("A empresa já tem uma resposta ativa a este comentário");
    }
}
