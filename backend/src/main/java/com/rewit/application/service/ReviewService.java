package com.rewit.application.service;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.ReviewDetailView;
import com.rewit.application.dto.ReviewDto.ReviewTargetView;
import com.rewit.application.dto.ReviewDto.PublicAuthorView;
import com.rewit.application.dto.ReviewDto.ReviewPublicView;
import com.rewit.application.dto.ReviewDto.TargetStatsView;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.RateableTargetStatsRepository;
import com.rewit.application.port.ReviewReactionRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.ReviewTargetRepository;
import com.rewit.application.port.UserFollowRepository;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.rewit.application.dto.catalog.CatalogDtos;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.CheckInRepository;
import com.rewit.domain.enums.CheckInStatus;
import com.rewit.domain.enums.VerificationMethod;
import com.rewit.domain.model.CheckIn;

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
    private final CheckInRepository checkInRepository;
    private final RateableTargetStatsRepository rateableTargetStatsRepository;
    private final UserFollowRepository userFollowRepository;
    private final ReviewReactionRepository reviewReactionRepository;

    @Autowired
    public ReviewService(ReviewRepository reviewRepository,
                         ReviewTargetRepository reviewTargetRepository,
                         UserRepository userRepository,
                         RateableTargetRepository rateableTargetRepository,
                         PlaceRepository placeRepository,
                         ProfileRepository profileRepository,
                         CheckInRepository checkInRepository,
                         RateableTargetStatsRepository rateableTargetStatsRepository,
                         UserFollowRepository userFollowRepository,
                         ReviewReactionRepository reviewReactionRepository) {
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "reviewRepository must not be null");
        this.reviewTargetRepository = Objects.requireNonNull(reviewTargetRepository, "reviewTargetRepository must not be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.rateableTargetRepository = Objects.requireNonNull(rateableTargetRepository, "rateableTargetRepository must not be null");
        this.placeRepository = Objects.requireNonNull(placeRepository, "placeRepository must not be null");
        this.profileRepository = profileRepository;
        this.checkInRepository = checkInRepository;
        this.rateableTargetStatsRepository = rateableTargetStatsRepository;
        this.userFollowRepository = userFollowRepository;
        this.reviewReactionRepository = reviewReactionRepository;
    }

    public ReviewService(ReviewRepository reviewRepository,
                         ReviewTargetRepository reviewTargetRepository,
                         UserRepository userRepository,
                         RateableTargetRepository rateableTargetRepository,
                         PlaceRepository placeRepository,
                         ProfileRepository profileRepository,
                         CheckInRepository checkInRepository,
                         RateableTargetStatsRepository rateableTargetStatsRepository,
                         UserFollowRepository userFollowRepository) {
        this(reviewRepository, reviewTargetRepository, userRepository, rateableTargetRepository, placeRepository, profileRepository, checkInRepository, rateableTargetStatsRepository, userFollowRepository, null);
    }

    public ReviewService(ReviewRepository reviewRepository,
                         ReviewTargetRepository reviewTargetRepository,
                         UserRepository userRepository,
                         RateableTargetRepository rateableTargetRepository,
                         PlaceRepository placeRepository,
                         ProfileRepository profileRepository,
                         CheckInRepository checkInRepository,
                         RateableTargetStatsRepository rateableTargetStatsRepository) {
        this(reviewRepository, reviewTargetRepository, userRepository, rateableTargetRepository, placeRepository, profileRepository, checkInRepository, rateableTargetStatsRepository, null, null);
    }

    public ReviewService(ReviewRepository reviewRepository,
                         ReviewTargetRepository reviewTargetRepository,
                         UserRepository userRepository,
                         RateableTargetRepository rateableTargetRepository,
                         PlaceRepository placeRepository) {
        this(reviewRepository, reviewTargetRepository, userRepository, rateableTargetRepository, placeRepository, null, null, null, null, null);
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
                cmd.userLatitude(),
                cmd.userLongitude(),
                cmd.locationAccuracyMeters()
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

        // 6. Avaliação de presença física e registro de CheckIn (Step 12.0)
        if (checkInRepository != null && cmd.contextPlaceId() != null && cmd.userLatitude() != null && cmd.userLongitude() != null) {
            CatalogDtos.SpatialValidationResult spatialResult = placeRepository
                    .validateProximity(cmd.contextPlaceId(), cmd.userLatitude(), cmd.userLongitude())
                    .orElseThrow(() -> new BusinessException("Local de contexto não encontrado para validação espacial", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));

            CheckIn checkIn;
            if (spatialResult.isWithinRadius()) {
                checkIn = new CheckIn(
                        UUID.randomUUID(),
                        savedReview.getId(),
                        author.getId(),
                        cmd.contextPlaceId(),
                        cmd.userLatitude(),
                        cmd.userLongitude(),
                        spatialResult.distanceMeters(),
                        CheckInStatus.VERIFIED,
                        VerificationMethod.GPS,
                        Instant.now()
                );
            } else {
                checkIn = new CheckIn(
                        UUID.randomUUID(),
                        savedReview.getId(),
                        author.getId(),
                        cmd.contextPlaceId(),
                        cmd.userLatitude(),
                        cmd.userLongitude(),
                        spatialResult.distanceMeters(),
                        CheckInStatus.REJECTED,
                        VerificationMethod.GPS,
                        null
                );
            }

            CheckIn savedCheckIn = checkInRepository.save(checkIn);
            savedReview.attachCheckIn(savedCheckIn);
        }

        // 7. Atualização determinística e consistente de estatísticas por alvo (Step 13.0)
        if (rateableTargetStatsRepository != null) {
            List<UUID> targetIdsToUpdate = savedTargets.stream()
                    .map((ReviewTarget target) -> target.getTargetId())
                    .distinct()
                    .sorted(Comparator.comparing((UUID id) -> id.toString()))
                    .toList();

            for (UUID targetId : targetIdsToUpdate) {
                rateableTargetStatsRepository.recalculateAndSave(targetId);
            }
        }

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
                detail.isVerifiedOnSite(),
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
            boolean isAuthor = requesterUserId != null && requesterUserId.equals(review.getUserId());
            boolean isFollower = requesterUserId != null && userFollowRepository != null && userFollowRepository.isFollowing(requesterUserId, review.getUserId());
            if (!isAuthor && !isFollower) {
                throw new BusinessException("Esta avaliação é visível apenas para seguidores", HttpStatus.FORBIDDEN, "FORBIDDEN");
            }
        }

        PublicAuthorView authorView = resolvePublicAuthor(review.getUserId(), review.isAnonymous());

        List<ReviewTargetView> targetViews = review.getTargets() != null
                ? review.getTargets().stream()
                .map(t -> new ReviewTargetView(t.getId(), t.getReviewId(), t.getTargetId(), t.getRating(), t.getSpecificComment(), t.getCreatedAt()))
                .toList()
                : List.of();

        long helpfulCount = reviewReactionRepository != null ? reviewReactionRepository.countHelpful(review.getId()) : 0L;
        boolean isHelpfulByMe = requesterUserId != null && reviewReactionRepository != null && reviewReactionRepository.isHelpful(review.getId(), requesterUserId);

        return new ReviewPublicView(
                review.getId(),
                authorView,
                review.getContextPlaceId(),
                review.getExperienceText(),
                review.isAnonymous(),
                review.isVerifiedOnSite(),
                review.getVisibility(),
                review.getStatus() != null ? review.getStatus().name() : "ACTIVE",
                review.getCreatedAt(),
                review.getUpdatedAt(),
                targetViews,
                helpfulCount,
                isHelpfulByMe
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

    @Transactional(readOnly = true)
    public TargetStatsView getTargetStats(UUID targetId) {
        if (targetId == null) {
            throw new BusinessException("O identificador do alvo avaliável é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_TARGET_ID");
        }

        if (!rateableTargetRepository.existsById(targetId)) {
            throw new BusinessException("Alvo avaliável não encontrado", HttpStatus.NOT_FOUND, "RATEABLE_TARGET_NOT_FOUND");
        }

        if (rateableTargetStatsRepository == null) {
            return new TargetStatsView(targetId, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), 0, null);
        }

        return rateableTargetStatsRepository.findByTargetId(targetId)
                .map(stats -> new TargetStatsView(
                        stats.getTargetId(),
                        stats.getAverageRating(),
                        stats.getReviewsCount(),
                        stats.getLastCalculatedAt()
                ))
                .orElseGet(() -> new TargetStatsView(
                        targetId,
                        BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                        0,
                        null
                ));
    }

    @Transactional(readOnly = true)
    public PageResult<ReviewPublicView> findReviewsByTarget(
            UUID targetId,
            int page,
            int size,
            String sort,
            boolean verifiedOnly,
            UUID requesterUserId
    ) {
        if (targetId == null) {
            throw new BusinessException("O identificador do alvo avaliável é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_TARGET_ID");
        }
        if (page < 0) {
            throw new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        if (size <= 0) {
            throw new BusinessException("O tamanho da página deve ser maior que zero", HttpStatus.BAD_REQUEST, "INVALID_SIZE");
        }
        if (size > 50) {
            throw new BusinessException("O tamanho da página não pode ser superior a 50", HttpStatus.BAD_REQUEST, "PAGE_SIZE_EXCEEDED");
        }

        String normalizedSort = (sort != null && !sort.isBlank()) ? sort.trim().toLowerCase(java.util.Locale.ROOT) : "newest";
        if (!"newest".equals(normalizedSort) && !"rating_desc".equals(normalizedSort) && !"rating_asc".equals(normalizedSort)) {
            throw new BusinessException("Ordenação inválida: " + sort, HttpStatus.BAD_REQUEST, "INVALID_SORT");
        }

        if (!rateableTargetRepository.existsById(targetId)) {
            throw new BusinessException("Alvo avaliável não encontrado", HttpStatus.NOT_FOUND, "RATEABLE_TARGET_NOT_FOUND");
        }

        PageResult<ReviewRepository.ReviewWithTarget> pageResult = reviewRepository.findByTarget(
                targetId, requesterUserId, verifiedOnly, normalizedSort, page, size
        );

        if (pageResult.content().isEmpty()) {
            return PageResult.of(List.of(), page, size, pageResult.totalElements());
        }

        // Resolução em lote de autores para evitar N+1
        Set<UUID> nonAnonymousAuthorIds = pageResult.content().stream()
                .filter(item -> !item.review().isAnonymous())
                .map(item -> item.review().getUserId())
                .collect(Collectors.toSet());

        Map<UUID, Profile> profilesByUserId = (profileRepository != null && !nonAnonymousAuthorIds.isEmpty())
                ? profileRepository.findByUserIdIn(nonAnonymousAuthorIds).stream()
                .collect(Collectors.toMap(p -> p.getUserId(), p -> p, (a, b) -> a))
                : Map.of();

        // Resolução em lote de contagens e votos de Helpful para evitar N+1
        List<UUID> reviewIds = pageResult.content().stream()
                .map(item -> item.review().getId())
                .toList();

        Map<UUID, Long> helpfulCounts = (reviewReactionRepository != null && !reviewIds.isEmpty())
                ? reviewReactionRepository.countHelpfulByReviewIds(reviewIds)
                : Map.of();

        Set<UUID> helpfulByMeSet = (reviewReactionRepository != null && requesterUserId != null && !reviewIds.isEmpty())
                ? reviewReactionRepository.findHelpfulReviewIdsByUser(reviewIds, requesterUserId)
                : Set.of();

        List<ReviewPublicView> publicViews = pageResult.content().stream()
                .map(item -> {
                    Review review = item.review();
                    ReviewTarget target = item.target();

                    PublicAuthorView authorView;
                    if (review.isAnonymous()) {
                        authorView = PublicAuthorView.anonymous();
                    } else {
                        Profile profile = profilesByUserId.get(review.getUserId());
                        if (profile != null) {
                            authorView = new PublicAuthorView(review.getUserId(), profile.getHandle(), profile.getDisplayName(), profile.getAvatarUrl(), false);
                        } else {
                            authorView = new PublicAuthorView(review.getUserId(), null, null, null, false);
                        }
                    }

                    List<ReviewTargetView> targetViews = (target != null)
                            ? List.of(new ReviewTargetView(target.getId(), target.getReviewId(), target.getTargetId(), target.getRating(), target.getSpecificComment(), target.getCreatedAt()))
                            : List.of();

                    long helpfulCount = helpfulCounts.getOrDefault(review.getId(), 0L);
                    boolean isHelpfulByMe = helpfulByMeSet.contains(review.getId());

                    return new ReviewPublicView(
                            review.getId(),
                            authorView,
                            review.getContextPlaceId(),
                            review.getExperienceText(),
                            review.isAnonymous(),
                            review.isVerifiedOnSite(),
                            review.getVisibility(),
                            review.getStatus() != null ? review.getStatus().name() : "ACTIVE",
                            review.getCreatedAt(),
                            review.getUpdatedAt(),
                            targetViews,
                            helpfulCount,
                            isHelpfulByMe
                    );
                })
                .toList();

        return new PageResult<>(
                publicViews,
                pageResult.pageNumber(),
                pageResult.pageSize(),
                pageResult.totalElements(),
                pageResult.totalPages(),
                pageResult.isLast()
        );
    }

    @Transactional(readOnly = true)
    public PageResult<ReviewPublicView> findMyReviews(
            UUID authenticatedUserId,
            int page,
            int size
    ) {
        if (authenticatedUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (page < 0) {
            throw new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        if (size <= 0) {
            throw new BusinessException("O tamanho da página deve ser maior que zero", HttpStatus.BAD_REQUEST, "INVALID_SIZE");
        }
        if (size > 50) {
            throw new BusinessException("O tamanho da página não pode ser superior a 50", HttpStatus.BAD_REQUEST, "PAGE_SIZE_EXCEEDED");
        }

        PageResult<Review> pageResult = reviewRepository.findByUserIdPaged(authenticatedUserId, page, size);

        if (pageResult.content().isEmpty()) {
            return PageResult.of(List.of(), page, size, pageResult.totalElements());
        }

        List<UUID> reviewIds = pageResult.content().stream().map(r -> r.getId()).toList();

        Map<UUID, List<ReviewTarget>> targetsByReviewId = (reviewTargetRepository != null && !reviewIds.isEmpty())
                ? reviewTargetRepository.findByReviewIdIn(reviewIds).stream()
                .collect(Collectors.groupingBy(rt -> rt.getReviewId()))
                : Map.of();

        Optional<Profile> profileOpt = (profileRepository != null)
                ? profileRepository.findByUserId(authenticatedUserId)
                : Optional.empty();

        Map<UUID, Long> helpfulCounts = (reviewReactionRepository != null && !reviewIds.isEmpty())
                ? reviewReactionRepository.countHelpfulByReviewIds(reviewIds)
                : Map.of();

        Set<UUID> helpfulByMeSet = (reviewReactionRepository != null && !reviewIds.isEmpty())
                ? reviewReactionRepository.findHelpfulReviewIdsByUser(reviewIds, authenticatedUserId)
                : Set.of();

        List<ReviewPublicView> publicViews = pageResult.content().stream()
                .map(review -> {
                    PublicAuthorView authorView;
                    if (review.isAnonymous()) {
                        authorView = PublicAuthorView.anonymous();
                    } else if (profileOpt.isPresent()) {
                        Profile profile = profileOpt.get();
                        authorView = new PublicAuthorView(authenticatedUserId, profile.getHandle(), profile.getDisplayName(), profile.getAvatarUrl(), false);
                    } else {
                        authorView = new PublicAuthorView(authenticatedUserId, null, null, null, false);
                    }

                    List<ReviewTarget> targets = targetsByReviewId.getOrDefault(review.getId(), List.of());
                    List<ReviewTargetView> targetViews = targets.stream()
                            .map(t -> new ReviewTargetView(t.getId(), t.getReviewId(), t.getTargetId(), t.getRating(), t.getSpecificComment(), t.getCreatedAt()))
                            .toList();

                    long helpfulCount = helpfulCounts.getOrDefault(review.getId(), 0L);
                    boolean isHelpfulByMe = helpfulByMeSet.contains(review.getId());

                    return new ReviewPublicView(
                            review.getId(),
                            authorView,
                            review.getContextPlaceId(),
                            review.getExperienceText(),
                            review.isAnonymous(),
                            review.isVerifiedOnSite(),
                            review.getVisibility(),
                            review.getStatus() != null ? review.getStatus().name() : "ACTIVE",
                            review.getCreatedAt(),
                            review.getUpdatedAt(),
                            targetViews,
                            helpfulCount,
                            isHelpfulByMe
                    );
                })
                .toList();

        return new PageResult<>(
                publicViews,
                pageResult.pageNumber(),
                pageResult.pageSize(),
                pageResult.totalElements(),
                pageResult.totalPages(),
                pageResult.isLast()
        );
    }

    @Transactional(readOnly = true)
    public PageResult<ReviewPublicView> findFeed(
            UUID requesterUserId,
            int page,
            int size,
            String sort
    ) {
        if (requesterUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (page < 0) {
            throw new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        if (size <= 0) {
            throw new BusinessException("O tamanho da página deve ser maior que zero", HttpStatus.BAD_REQUEST, "INVALID_SIZE");
        }
        if (size > 50) {
            throw new BusinessException("O tamanho da página não pode ser superior a 50", HttpStatus.BAD_REQUEST, "PAGE_SIZE_EXCEEDED");
        }
        if (sort != null && !sort.isBlank() && !"newest".equalsIgnoreCase(sort.trim())) {
            throw new BusinessException("Ordenação inválida: " + sort, HttpStatus.BAD_REQUEST, "INVALID_SORT");
        }

        PageResult<Review> pageResult = reviewRepository.findFeedByFollowing(requesterUserId, page, size);

        if (pageResult.content().isEmpty()) {
            return PageResult.of(List.of(), page, size, pageResult.totalElements());
        }

        List<UUID> reviewIds = pageResult.content().stream().map(r -> r.getId()).toList();

        Map<UUID, List<ReviewTarget>> targetsByReviewId = (reviewTargetRepository != null && !reviewIds.isEmpty())
                ? reviewTargetRepository.findByReviewIdIn(reviewIds).stream()
                .collect(Collectors.groupingBy(t -> t.getReviewId()))
                : Map.of();

        Set<UUID> nonAnonymousAuthorIds = pageResult.content().stream()
                .filter(r -> !r.isAnonymous())
                .map(r -> r.getUserId())
                .collect(Collectors.toSet());

        Map<UUID, Profile> profilesByUserId = (profileRepository != null && !nonAnonymousAuthorIds.isEmpty())
                ? profileRepository.findByUserIdIn(nonAnonymousAuthorIds).stream()
                .collect(Collectors.toMap(p -> p.getUserId(), p -> p, (p1, p2) -> p1))
                : Map.of();

        Map<UUID, Long> helpfulCounts = (reviewReactionRepository != null && !reviewIds.isEmpty())
                ? reviewReactionRepository.countHelpfulByReviewIds(reviewIds)
                : Map.of();

        Set<UUID> helpfulByMeSet = (reviewReactionRepository != null && !reviewIds.isEmpty())
                ? reviewReactionRepository.findHelpfulReviewIdsByUser(reviewIds, requesterUserId)
                : Set.of();

        List<ReviewPublicView> publicViews = pageResult.content().stream()
                .map(review -> {
                    PublicAuthorView authorView;
                    if (review.isAnonymous()) {
                        authorView = PublicAuthorView.anonymous();
                    } else {
                        Profile profile = profilesByUserId.get(review.getUserId());
                        if (profile != null) {
                            authorView = new PublicAuthorView(review.getUserId(), profile.getHandle(), profile.getDisplayName(), profile.getAvatarUrl(), false);
                        } else {
                            authorView = new PublicAuthorView(review.getUserId(), null, null, null, false);
                        }
                    }

                    List<ReviewTarget> targets = targetsByReviewId.getOrDefault(review.getId(), List.of());
                    List<ReviewTargetView> targetViews = targets.stream()
                            .map(t -> new ReviewTargetView(t.getId(), t.getReviewId(), t.getTargetId(), t.getRating(), t.getSpecificComment(), t.getCreatedAt()))
                            .toList();

                    long helpfulCount = helpfulCounts.getOrDefault(review.getId(), 0L);
                    boolean isHelpfulByMe = helpfulByMeSet.contains(review.getId());

                    return new ReviewPublicView(
                            review.getId(),
                            authorView,
                            review.getContextPlaceId(),
                            review.getExperienceText(),
                            review.isAnonymous(),
                            review.isVerifiedOnSite(),
                            review.getVisibility(),
                            review.getStatus() != null ? review.getStatus().name() : "ACTIVE",
                            review.getCreatedAt(),
                            review.getUpdatedAt(),
                            targetViews,
                            helpfulCount,
                            isHelpfulByMe
                    );
                })
                .toList();

        return new PageResult<>(
                publicViews,
                pageResult.pageNumber(),
                pageResult.pageSize(),
                pageResult.totalElements(),
                pageResult.totalPages(),
                pageResult.isLast()
        );
    }
}
