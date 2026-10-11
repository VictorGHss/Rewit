package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseView;
import com.rewit.application.dto.business.BusinessResponseDtos.PublishReviewBusinessResponseCommand;
import com.rewit.application.port.BusinessResponseRepository;
import com.rewit.application.port.RateLimiter;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.application.service.ReviewVisibilityPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.BusinessResponse;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Review;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Publica a resposta oficial de uma empresa a uma avaliação (C11).
 *
 * <p>Quem publica é o administrador atual de uma conta comercial aprovada que controla, agora, o local escolhido, e
 * o local precisa estar associado à avaliação (alvo PLACE, local de SERVICE/EVENT avaliado ou local de contexto). Não
 * depende de role de plataforma: um USER ou um MODERATOR publica do mesmo jeito, pela conta que administra.
 *
 * <p>A avaliação precisa estar legível pelo ator ({@link ReviewVisibilityPolicy#validateCanAccess}: inexistente, em
 * quarentena ou removida responde 404; sem acesso, 403) e, além disso, ser pública
 * ({@code 409 REVIEW_NOT_RESPONDABLE}).
 *
 * <p>Uma resposta ativa por empresa e avaliação, mesmo que a empresa controle vários locais dela. A checagem prévia dá
 * a mensagem; a garantia é o índice único parcial {@code uq_business_responses_review_active}, cuja violação vira
 * {@code 409 BUSINESS_RESPONSE_ALREADY_EXISTS}. Publicações da mesma empresa pelo mesmo local se serializam no lock
 * do local; por locais diferentes, só o índice decide.
 */
@Service
public class PublishReviewBusinessResponseUseCase {

    static final String ACTIVE_REVIEW_RESPONSE_INDEX = "uq_business_responses_review_active";

    private final BusinessRepresentationPolicy representationPolicy;
    private final ReviewRepository reviewRepository;
    private final ReviewVisibilityPolicy reviewVisibilityPolicy;
    private final BusinessResponseRepository businessResponseRepository;
    private final RateLimiter rateLimiter;

    public PublishReviewBusinessResponseUseCase(BusinessRepresentationPolicy representationPolicy,
                                                ReviewRepository reviewRepository,
                                                ReviewVisibilityPolicy reviewVisibilityPolicy,
                                                BusinessResponseRepository businessResponseRepository,
                                                RateLimiter rateLimiter) {
        this.representationPolicy = Objects.requireNonNull(representationPolicy,
                "BusinessRepresentationPolicy must not be null");
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "ReviewRepository must not be null");
        this.reviewVisibilityPolicy = Objects.requireNonNull(reviewVisibilityPolicy,
                "ReviewVisibilityPolicy must not be null");
        this.businessResponseRepository = Objects.requireNonNull(businessResponseRepository,
                "BusinessResponseRepository must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "RateLimiter must not be null");
    }

    @Transactional
    public BusinessResponseView execute(PublishReviewBusinessResponseCommand command) {
        return execute(command, Instant.now());
    }

    @Transactional
    public BusinessResponseView execute(PublishReviewBusinessResponseCommand command, Instant now) {
        Objects.requireNonNull(command, "PublishReviewBusinessResponseCommand must not be null");
        if (command.actorUserId() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (command.reviewId() == null) {
            throw new BusinessException("A avaliação é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_REVIEW_ID");
        }
        if (command.placeId() == null) {
            throw new BusinessException("O local representado é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_PLACE_ID");
        }
        // Conteúdo validado antes de qualquer I/O
        String content = BusinessResponse.normalizeContent(command.content());
        Instant publishedAt = now != null ? now : Instant.now();

        BusinessAccount account = representationPolicy.requireApprovedAccount(command.actorUserId(),
                command.businessAccountId());

        rateLimiter.acquireOrThrow(RateLimitedAction.BUSINESS_RESPONSE_CREATION,
                RateLimitSubject.ofUser(command.actorUserId()));

        Review review = reviewRepository.findById(command.reviewId())
                .orElseThrow(PublishReviewBusinessResponseUseCase::reviewNotFound);
        reviewVisibilityPolicy.validateCanAccess(review, command.actorUserId());
        if (!isRespondable(review)) {
            throw new BusinessException("Esta avaliação não aceita respostas oficiais", HttpStatus.CONFLICT,
                    "REVIEW_NOT_RESPONDABLE");
        }

        Set<UUID> reviewPlaceIds = reviewRepository.findAssociatedPlaceIds(review.getId());
        Place place = representationPolicy.lockRepresentedPlace(account, command.placeId(), reviewPlaceIds);

        if (businessResponseRepository.findActive(review.getId(), null, account.getId()).isPresent()) {
            throw alreadyExists();
        }
        BusinessResponse response = BusinessResponse.publish(review.getId(), null, account.getId(), place.getId(),
                command.actorUserId(), content, publishedAt);
        try {
            return BusinessResponseView.of(businessResponseRepository.save(response), account, place);
        } catch (DataIntegrityViolationException ex) {
            if (CreateBusinessAccountUseCase.violates(ex, ACTIVE_REVIEW_RESPONSE_INDEX)) {
                throw alreadyExists();
            }
            throw ex;
        }
    }

    /** Respostas oficiais só em avaliação ativa e pública (ausência de visibilidade vale PUBLIC, como na leitura). */
    static boolean isRespondable(Review review) {
        String visibility = review.getVisibility() != null ? review.getVisibility() : "PUBLIC";
        return review.getStatus() == ReviewStatus.ACTIVE && "PUBLIC".equalsIgnoreCase(visibility);
    }

    static BusinessException reviewNotFound() {
        return new BusinessException("Avaliação não encontrada", HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND");
    }

    private static BusinessException alreadyExists() {
        return new BusinessException("A empresa já tem uma resposta ativa a esta avaliação", HttpStatus.CONFLICT,
                "BUSINESS_RESPONSE_ALREADY_EXISTS");
    }
}
