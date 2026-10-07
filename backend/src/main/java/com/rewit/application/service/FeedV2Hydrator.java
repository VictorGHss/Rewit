package com.rewit.application.service;

import com.rewit.application.dto.ReviewDto.PublicAuthorView;
import com.rewit.application.dto.ReviewDto.ReviewTargetView;
import com.rewit.application.dto.feed.FeedV2CandidatePage;
import com.rewit.application.dto.feed.FeedV2PageProjection;
import com.rewit.application.dto.feed.FeedV2Projection;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.ReviewReactionRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.ReviewTargetRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.feed.RankedFeedCandidate;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewTarget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Serviço de aplicação responsável pela hidratação e projeção pública do Feed V2 (Step 24.4.2).
 *
 * <p>Transforma {@link FeedV2CandidatePage} em {@link FeedV2PageProjection} utilizando
 * exclusivamente carregamento em lote (batch loading), eliminando qualquer risco de queries N+1.
 *
 * <p>Regras críticas aplicadas:
 * <ul>
 *   <li><b>Ordem estrita:</b> Preserva exatamente a ordenação de candidatos definida pelo ranker/diversifier.</li>
 *   <li><b>Anonimato estrito:</b> Avaliações anônimas ({@code isAnonymous == true}) utilizam {@link PublicAuthorView#anonymous()},
 *       nunca consultam perfis de autor no banco e nunca expõem o {@code authorId}.</li>
 *   <li><b>Multi-target completo:</b> Todos os targets de cada avaliação são carregados em lote e preservados de forma determinística.</li>
 *   <li><b>Filtro de integridade:</b> Avaliações com status {@code REMOVED} ou {@code UNDER_REVIEW} não geram projeção pública.</li>
 *   <li><b>Eficiência:</b> Páginas vazias não realizam chamadas a repositórios. Apenas dados da página solicitada são hidratados.</li>
 * </ul>
 */
@Service
public class FeedV2Hydrator {

    private final ReviewRepository reviewRepository;
    private final ReviewTargetRepository reviewTargetRepository;
    private final ProfileRepository profileRepository;
    private final ReviewReactionRepository reviewReactionRepository;
    private final UserRepository userRepository;

    public FeedV2Hydrator(
            ReviewRepository reviewRepository,
            ReviewTargetRepository reviewTargetRepository,
            ProfileRepository profileRepository,
            ReviewReactionRepository reviewReactionRepository,
            UserRepository userRepository
    ) {
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "reviewRepository is required");
        this.reviewTargetRepository = Objects.requireNonNull(reviewTargetRepository, "reviewTargetRepository is required");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository is required");
        this.reviewReactionRepository = Objects.requireNonNull(reviewReactionRepository, "reviewReactionRepository is required");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository is required");
    }

    /**
     * Hidrata a página interna de candidatos do Feed V2 em projeções públicas de avaliações.
     *
     * @param candidatePage página ranqueada e diversificada contendo a fatia atual do feed
     * @param requesterId identificador do usuário solicitante (opcional, para computar isHelpfulByMe)
     * @return página contendo a lista de projeções públicas na ordem exata e metadados de paginação
     */
    @Transactional(readOnly = true)
    public FeedV2PageProjection hydrate(FeedV2CandidatePage candidatePage, UUID requesterId) {
        if (candidatePage == null || candidatePage.isEmpty()) {
            int page = candidatePage != null ? candidatePage.page() : 0;
            int size = candidatePage != null ? candidatePage.size() : 10;
            int windowSize = candidatePage != null ? candidatePage.windowSize() : 0;
            return new FeedV2PageProjection(List.of(), page, size, windowSize);
        }

        List<FeedV2Projection> projectedItems = hydrateCandidates(candidatePage.items(), requesterId);

        return new FeedV2PageProjection(
                projectedItems,
                candidatePage.page(),
                candidatePage.size(),
                candidatePage.windowSize()
        );
    }

    /**
     * Sobrecarga conveniente para hidratação direta de lista de candidatos ranqueados.
     */
    @Transactional(readOnly = true)
    public List<FeedV2Projection> hydrate(List<RankedFeedCandidate> candidates, UUID requesterId) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return hydrateCandidates(candidates, requesterId);
    }

    private List<FeedV2Projection> hydrateCandidates(List<RankedFeedCandidate> candidates, UUID requesterId) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // 1. Coleta deduplicada de IDs de reviews da página atual (prevenção de duplicidade)
        Set<UUID> reviewIds = candidates.stream()
                .map(c -> c.candidate().reviewId())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 2. Batch: Carregamento das entidades Review em lote
        Map<UUID, Review> reviewsById = reviewRepository.findByIdIn(reviewIds).stream()
                .collect(Collectors.toMap(review -> review.getId(), r -> r, (r1, r2) -> r1));

        // Filtrar reviews ativas elegíveis para projeção pública (exclui REMOVED e UNDER_REVIEW)
        Set<UUID> activeReviewIds = reviewsById.values().stream()
                .filter(this::isPubliclyProjectable)
                .map(review -> review.getId())
                .collect(Collectors.toSet());

        if (activeReviewIds.isEmpty()) {
            return List.of();
        }

        // 3. Batch: Targets de todas as avaliações ativas da página
        Map<UUID, List<ReviewTargetView>> targetsByReviewId = reviewTargetRepository.findByReviewIdIn(activeReviewIds).stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing((ReviewTarget target) -> target.getCreatedAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(target -> target.getId()))
                .collect(Collectors.groupingBy(
                        target -> target.getReviewId(),
                        Collectors.mapping(
                                t -> new ReviewTargetView(
                                        t.getId(),
                                        t.getReviewId(),
                                        t.getTargetId(),
                                        t.getRating(),
                                        t.getSpecificComment(),
                                        t.getCreatedAt()
                                ),
                                Collectors.toList()
                        )
                ));

        // 4. Batch: Perfis exclusivamente de autores NÃO-ANÔNIMOS da página
        Set<UUID> nonAnonymousAuthorIds = reviewsById.values().stream()
                .filter(this::isPubliclyProjectable)
                .filter(r -> !r.isAnonymous())
                .map(review -> review.getUserId())
                .collect(Collectors.toSet());

        Map<UUID, Profile> profilesByUserId = !nonAnonymousAuthorIds.isEmpty()
                ? profileRepository.findByUserIdIn(nonAnonymousAuthorIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(profile -> profile.getUserId(), p -> p, (p1, p2) -> p1))
                : Map.of();

        // Autores de contas excluídas: identidade oculta, avaliação mantida (C2). Uma consulta por página
        Set<UUID> deletedAuthorIds = !nonAnonymousAuthorIds.isEmpty()
                ? userRepository.findDeletedUserIds(nonAnonymousAuthorIds)
                : Set.of();

        // 5. Batch: Contagem total de votos de Helpful em lote
        Map<UUID, Long> helpfulCounts = reviewReactionRepository.countHelpfulByReviewIds(activeReviewIds);

        // 6. Batch: Votos de Helpful do usuário solicitante (se autenticado)
        Set<UUID> helpfulByMeSet = (requesterId != null)
                ? reviewReactionRepository.findHelpfulReviewIdsByUser(activeReviewIds, requesterId)
                : Set.of();

        // 7. Montagem preservando estritamente a ordem de entrada dos candidatos
        List<FeedV2Projection> projections = new ArrayList<>();
        for (RankedFeedCandidate rankedCandidate : candidates) {
            UUID reviewId = rankedCandidate.candidate().reviewId();
            Review review = reviewsById.get(reviewId);

            if (review == null || !isPubliclyProjectable(review)) {
                continue;
            }

            PublicAuthorView authorView;
            if (review.isAnonymous()) {
                // Anonimato estrito: sem authorId, sem handle, sem avatar
                authorView = PublicAuthorView.anonymous();
            } else if (deletedAuthorIds.contains(review.getUserId())) {
                authorView = PublicAuthorView.deleted();
            } else {
                Profile profile = profilesByUserId.get(review.getUserId());
                if (profile != null) {
                    authorView = new PublicAuthorView(
                            review.getUserId(),
                            profile.getHandle(),
                            profile.getDisplayName(),
                            profile.getAvatarUrl(),
                            false
                    );
                } else {
                    // Autor não anônimo mas sem perfil existente
                    authorView = new PublicAuthorView(
                            review.getUserId(),
                            null,
                            null,
                            null,
                            false
                    );
                }
            }

            List<ReviewTargetView> targets = targetsByReviewId.getOrDefault(review.getId(), List.of());
            long helpfulCount = (helpfulCounts != null) ? helpfulCounts.getOrDefault(review.getId(), 0L) : 0L;
            boolean isHelpfulByMe = helpfulByMeSet != null && helpfulByMeSet.contains(review.getId());

            FeedV2Projection projection = new FeedV2Projection(
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
                    targets,
                    helpfulCount,
                    isHelpfulByMe
            );

            projections.add(projection);
        }

        return List.copyOf(projections);
    }

    private boolean isPubliclyProjectable(Review review) {
        if (review == null) {
            return false;
        }
        ReviewStatus status = review.getStatus();
        return status != null && status != ReviewStatus.REMOVED && status != ReviewStatus.UNDER_REVIEW;
    }
}
