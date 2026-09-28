package com.rewit.application.service;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.ReviewDetailView;
import com.rewit.application.dto.ReviewDto.ReviewTargetView;
import com.rewit.application.dto.ReviewDto.PublicAuthorView;
import com.rewit.application.dto.ReviewDto.ReviewPublicView;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.ReviewTargetRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewTarget;
import com.rewit.domain.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Serviço de aplicação para orquestração de publicações de avaliação multi-alvo (Reviews).
 * Garante atomismo transacional completo, validações de domínio e conformidade com ADR-006 e ADR-009.
 */
@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewTargetRepository reviewTargetRepository;
    private final UserRepository userRepository;
    private final RateableTargetRepository rateableTargetRepository;
    private final PlaceRepository placeRepository;
    private final ProfileRepository profileRepository;

    @Autowired
    public ReviewService(ReviewRepository reviewRepository,
                         ReviewTargetRepository reviewTargetRepository,
                         UserRepository userRepository,
                         RateableTargetRepository rateableTargetRepository,
                         PlaceRepository placeRepository,
                         ProfileRepository profileRepository) {
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "reviewRepository must not be null");
        this.reviewTargetRepository = Objects.requireNonNull(reviewTargetRepository, "reviewTargetRepository must not be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.rateableTargetRepository = Objects.requireNonNull(rateableTargetRepository, "rateableTargetRepository must not be null");
        this.placeRepository = Objects.requireNonNull(placeRepository, "placeRepository must not be null");
        this.profileRepository = profileRepository;
    }

    public ReviewService(ReviewRepository reviewRepository,
                         ReviewTargetRepository reviewTargetRepository,
                         UserRepository userRepository,
                         RateableTargetRepository rateableTargetRepository,
                         PlaceRepository placeRepository) {
        this(reviewRepository, reviewTargetRepository, userRepository, rateableTargetRepository, placeRepository, null);
    }

    /**
     * Caso de uso central: Criação atômica de publicação de avaliação multi-alvo.
     * Todas as validações prévias são executadas antes de iniciar a persistência.
     */
    @Transactional
    public ReviewDetailView createReview(CreateReviewCommand cmd) {
        Objects.requireNonNull(cmd, "CreateReviewCommand cannot be null");

        // 1. Validação do autor
        if (cmd.authorUserId() == null) {
            throw new BusinessException("O autor da avaliação é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_AUTHOR_ID");
        }
        User author = userRepository.findById(cmd.authorUserId())
                .orElseThrow(() -> new BusinessException("Autor não encontrado", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        if (author.isDeleted() || !author.isActive()) {
            throw new BusinessException("Autor inativo ou excluído", HttpStatus.FORBIDDEN, "USER_INACTIVE");
        }

        // 2. Validação de contextPlace quando informado
        if (cmd.contextPlaceId() != null) {
            if (placeRepository.findById(cmd.contextPlaceId()).isEmpty()) {
                throw new BusinessException("Local de contexto não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND");
            }
        }

        // 3. Validação dos targets
        if (cmd.targets() == null || cmd.targets().isEmpty()) {
            throw new BusinessException("Uma avaliação deve possuir pelo menos um alvo", HttpStatus.UNPROCESSABLE_CONTENT, "REVIEW_WITHOUT_TARGET");
        }

        // 3.1. Validações estruturais e em memória de cada target (fail-fast antes de I/O)
        Set<UUID> seenTargetIds = new HashSet<>();
        for (CreateReviewTargetCommand targetCmd : cmd.targets()) {
            if (targetCmd == null) {
                throw new BusinessException("Alvo de avaliação não pode ser nulo", HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_TARGET");
            }
            if (targetCmd.rateableTargetId() == null) {
                throw new BusinessException("O alvo avaliado (RateableTarget) é obrigatório", HttpStatus.UNPROCESSABLE_CONTENT, "MISSING_TARGET_ID");
            }
            if (!seenTargetIds.add(targetCmd.rateableTargetId())) {
                throw new BusinessException("O mesmo alvo não pode ser avaliado mais de uma vez na mesma publicação", HttpStatus.UNPROCESSABLE_CONTENT, "DUPLICATE_REVIEW_TARGET");
            }
            if (targetCmd.rating() == null || targetCmd.rating().compareTo(BigDecimal.valueOf(1.0)) < 0 || targetCmd.rating().compareTo(BigDecimal.valueOf(5.0)) > 0) {
                throw new BusinessException("A nota de avaliação deve estar rigorosamente entre 1.0 e 5.0", HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_RATING_RANGE");
            }
            if (targetCmd.rating().scale() > 1) {
                throw new BusinessException("A nota deve possuir no máximo uma casa decimal", HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_RATING_PRECISION");
            }
        }

        // 3.2. Validação de existência no banco (I/O)
        for (CreateReviewTargetCommand targetCmd : cmd.targets()) {
            if (!rateableTargetRepository.existsById(targetCmd.rateableTargetId())) {
                throw new BusinessException("Alvo avaliável não encontrado: " + targetCmd.rateableTargetId(), HttpStatus.NOT_FOUND, "RATEABLE_TARGET_NOT_FOUND");
            }
        }

        // 4. Construção do agregado de domínio
        Review review = new Review(
                UUID.randomUUID(),
                author.getId(),
                cmd.contextPlaceId(),
                cmd.experienceText(),
                cmd.isAnonymous(),
                cmd.visibility(),
                null,
                null,
                null
        );

        for (CreateReviewTargetCommand targetCmd : cmd.targets()) {
            ReviewTarget target = new ReviewTarget(
                    UUID.randomUUID(),
                    review.getId(),
                    targetCmd.rateableTargetId(),
                    targetCmd.rating(),
                    targetCmd.specificComment()
            );
            review.addTarget(target);
        }

        review.validateHasAtLeastOneTarget();

        // 5. Persistência atômica
        Review savedReview = reviewRepository.save(review);
        List<ReviewTarget> savedTargets = reviewTargetRepository.saveAll(review.getTargets());

        return toDetailView(savedReview, savedTargets);
    }

    @Transactional(readOnly = true)
    public Optional<ReviewDetailView> getReviewById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return reviewRepository.findById(id)
                .map(review -> toDetailView(review, review.getTargets()));
    }

    @Transactional(readOnly = true)
    public List<ReviewDetailView> getReviewsByUserId(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return reviewRepository.findByUserId(userId).stream()
                .map(review -> toDetailView(review, review.getTargets()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReviewDetailView> getReviewsByContextPlaceId(UUID contextPlaceId) {
        if (contextPlaceId == null) {
            return List.of();
        }
        return reviewRepository.findByContextPlaceId(contextPlaceId).stream()
                .map(review -> toDetailView(review, review.getTargets()))
                .toList();
    }

    private static ReviewDetailView toDetailView(Review review, List<ReviewTarget> targets) {
        List<ReviewTargetView> targetViews = targets != null
                ? targets.stream()
                .map(t -> new ReviewTargetView(
                        t.getId(),
                        t.getReviewId(),
                        t.getTargetId(),
                        t.getRating(),
                        t.getSpecificComment(),
                        t.getCreatedAt()
                ))
                .toList()
                : List.of();

        return new ReviewDetailView(
                review.getId(),
                review.getUserId(),
                review.getContextPlaceId(),
                review.getExperienceText(),
                review.isAnonymous(),
                review.isVerifiedOnSite(),
                review.getStatus() != null ? review.getStatus().name() : "ACTIVE",
                review.getVisibility(),
                review.getCreatedAt(),
                review.getUpdatedAt(),
                targetViews
        );
    }

    @Transactional
    public ReviewPublicView createReviewAndGetPublicView(CreateReviewCommand cmd) {
        ReviewDetailView detail = createReview(cmd);
        PublicAuthorView authorView = resolvePublicAuthor(detail.userId(), detail.isAnonymous());
        return new ReviewPublicView(
                detail.id(),
                authorView,
                detail.contextPlaceId(),
                detail.experienceText(),
                detail.isAnonymous(),
                detail.visibility(),
                detail.status(),
                detail.createdAt(),
                detail.updatedAt(),
                detail.targets()
        );
    }

    @Transactional(readOnly = true)
    public ReviewPublicView getReviewPublicView(UUID reviewId, UUID requesterUserId) {
        if (reviewId == null) {
            throw new BusinessException("Identificador de avaliação obrigatório", HttpStatus.BAD_REQUEST, "MISSING_REVIEW_ID");
        }

        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException("Avaliação não encontrada", HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));

        // Validação de visibilidade
        String visibility = review.getVisibility() != null ? review.getVisibility() : "PUBLIC";
        if ("PRIVATE".equalsIgnoreCase(visibility)) {
            if (requesterUserId == null || !requesterUserId.equals(review.getUserId())) {
                throw new BusinessException("Acesso negado a esta avaliação privada", HttpStatus.FORBIDDEN, "FORBIDDEN");
            }
        } else if ("FOLLOWERS".equalsIgnoreCase(visibility)) {
            if (requesterUserId == null || !requesterUserId.equals(review.getUserId())) {
                throw new BusinessException("Esta avaliação é visível apenas para seguidores", HttpStatus.FORBIDDEN, "FORBIDDEN");
            }
        }

        PublicAuthorView authorView = resolvePublicAuthor(review.getUserId(), review.isAnonymous());

        List<ReviewTargetView> targetViews = review.getTargets() != null
                ? review.getTargets().stream()
                .map(t -> new ReviewTargetView(t.getId(), t.getReviewId(), t.getTargetId(), t.getRating(), t.getSpecificComment(), t.getCreatedAt()))
                .toList()
                : List.of();

        return new ReviewPublicView(
                review.getId(),
                authorView,
                review.getContextPlaceId(),
                review.getExperienceText(),
                review.isAnonymous(),
                review.getVisibility(),
                review.getStatus() != null ? review.getStatus().name() : "ACTIVE",
                review.getCreatedAt(),
                review.getUpdatedAt(),
                targetViews
        );
    }

    private PublicAuthorView resolvePublicAuthor(UUID userId, boolean isAnonymous) {
        if (isAnonymous) {
            return PublicAuthorView.anonymous();
        }

        if (profileRepository != null && userId != null) {
            Optional<Profile> profileOpt = profileRepository.findByUserId(userId);
            if (profileOpt.isPresent()) {
                Profile profile = profileOpt.get();
                return new PublicAuthorView(userId, profile.getHandle(), profile.getDisplayName(), profile.getAvatarUrl(), false);
            }
        }

        return new PublicAuthorView(userId, null, null, null, false);
    }
}
