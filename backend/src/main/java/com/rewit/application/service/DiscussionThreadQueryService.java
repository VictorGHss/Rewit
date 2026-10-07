package com.rewit.application.service;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.discussion.DiscussionThreadDtos.DiscussionItemView;
import com.rewit.application.dto.discussion.DiscussionThreadDtos.DiscussionThreadView;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewDiscussion;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Leitura da thread de discussões de uma avaliação (C3 D2), montada no servidor em dois níveis.
 *
 * <ul>
 *   <li>Raiz {@code ACTIVE}: visível, com as primeiras respostas visíveis embutidas.</li>
 *   <li>Raiz {@code UNDER_REVIEW}: a conversa inteira some para terceiros; o autor vê o próprio comentário como
 *       {@code PENDING_REVIEW}, sem respostas.</li>
 *   <li>Raiz {@code REMOVED}: tombstone sem conteúdo e sem autor, somente enquanto houver resposta visível.</li>
 *   <li>Resposta {@code UNDER_REVIEW}: aparece apenas para o próprio autor, como {@code PENDING_REVIEW}.</li>
 * </ul>
 *
 * <p>Custo fixo por página, sem N+1: raízes, primeiras respostas, contagens e perfis em quatro consultas.
 */
@Service
public class DiscussionThreadQueryService {

    /** Respostas embutidas por raiz; as demais são paginadas em {@code /discussions/{id}/replies}. */
    public static final int INLINE_REPLY_LIMIT = 3;
    public static final int MAX_PAGE_SIZE = 50;

    private final DiscussionRepository discussionRepository;
    private final ReviewRepository reviewRepository;
    private final ProfileRepository profileRepository;
    private final ReviewVisibilityPolicy reviewVisibilityPolicy;
    private final DiscussionPresentationPolicy presentationPolicy;

    public DiscussionThreadQueryService(DiscussionRepository discussionRepository,
                                        ReviewRepository reviewRepository,
                                        ProfileRepository profileRepository,
                                        ReviewVisibilityPolicy reviewVisibilityPolicy,
                                        DiscussionPresentationPolicy presentationPolicy) {
        this.discussionRepository = Objects.requireNonNull(discussionRepository, "DiscussionRepository must not be null");
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "ReviewRepository must not be null");
        this.profileRepository = Objects.requireNonNull(profileRepository, "ProfileRepository must not be null");
        this.reviewVisibilityPolicy = Objects.requireNonNull(reviewVisibilityPolicy, "ReviewVisibilityPolicy must not be null");
        this.presentationPolicy = Objects.requireNonNull(presentationPolicy, "DiscussionPresentationPolicy must not be null");
    }

    /** Página de raízes da thread, cada uma com as primeiras respostas visíveis ao leitor. */
    @Transactional(readOnly = true)
    public PageResult<DiscussionThreadView> getThread(UUID reviewId, UUID viewerId, int page, int size) {
        requireViewer(viewerId);
        if (reviewId == null) {
            throw new BusinessException("A avaliação é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_REVIEW_ID");
        }
        validatePage(page, size);

        Review review = accessibleReview(reviewId, viewerId);
        PageResult<ReviewDiscussion> roots = discussionRepository.findThreadRootsVisibleTo(reviewId, viewerId, page, size);

        // Raiz em análise não expõe respostas, nem ao próprio autor
        List<UUID> expandableRootIds = roots.content().stream()
                .filter(root -> root.getStatus() != DiscussionStatus.UNDER_REVIEW)
                .map(ReviewDiscussion::getId)
                .toList();
        Map<UUID, List<ReviewDiscussion>> repliesByRoot = discussionRepository
                .findFirstRepliesVisibleTo(expandableRootIds, viewerId, INLINE_REPLY_LIMIT).stream()
                .collect(Collectors.groupingBy(ReviewDiscussion::getParentId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, Long> replyCounts = discussionRepository.countRepliesVisibleTo(expandableRootIds, viewerId);

        Map<UUID, Profile> profiles = profilesOf(Stream.concat(
                roots.content().stream(), repliesByRoot.values().stream().flatMap(List::stream)).toList(), review);

        List<DiscussionThreadView> threads = new ArrayList<>(roots.content().size());
        for (ReviewDiscussion root : roots.content()) {
            List<DiscussionItemView> replies = repliesByRoot.getOrDefault(root.getId(), List.of()).stream()
                    .map(reply -> presentationPolicy.present(reply, viewerId, review.isAnonymous(), profiles))
                    .toList();
            long replyCount = replyCounts.getOrDefault(root.getId(), 0L);
            threads.add(new DiscussionThreadView(
                    presentationPolicy.present(root, viewerId, review.isAnonymous(), profiles),
                    replies,
                    replyCount,
                    replyCount > replies.size()));
        }
        return PageResult.of(threads, roots.pageNumber(), roots.pageSize(), roots.totalElements());
    }

    /**
     * Respostas visíveis de uma raiz, paginadas. Raiz em análise ou inexistente responde 404 para todos, sem
     * distinguir o motivo; raiz removida continua listando as respostas visíveis.
     */
    @Transactional(readOnly = true)
    public PageResult<DiscussionItemView> getReplies(UUID rootId, UUID viewerId, int page, int size) {
        requireViewer(viewerId);
        if (rootId == null) {
            throw new BusinessException("A discussão é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_DISCUSSION_ID");
        }
        validatePage(page, size);

        ReviewDiscussion root = discussionRepository.findById(rootId)
                .filter(discussion -> discussion.getParentId() == null)
                .filter(discussion -> discussion.getStatus() != DiscussionStatus.UNDER_REVIEW)
                .orElseThrow(() -> new BusinessException("Comentário não encontrado ou indisponível",
                        HttpStatus.NOT_FOUND, "DISCUSSION_NOT_FOUND"));
        Review review = accessibleReview(root.getReviewId(), viewerId);

        PageResult<ReviewDiscussion> replies = discussionRepository.findRepliesVisibleTo(rootId, viewerId, page, size);
        Map<UUID, Profile> profiles = profilesOf(replies.content(), review);
        List<DiscussionItemView> views = replies.content().stream()
                .map(reply -> presentationPolicy.present(reply, viewerId, review.isAnonymous(), profiles))
                .toList();
        return PageResult.of(views, replies.pageNumber(), replies.pageSize(), replies.totalElements());
    }

    private Review accessibleReview(UUID reviewId, UUID viewerId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException("Avaliação não encontrada", HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));
        reviewVisibilityPolicy.validateCanAccess(review, viewerId);
        return review;
    }

    /** Perfis apenas dos autores que serão expostos, numa única consulta. */
    private Map<UUID, Profile> profilesOf(Collection<ReviewDiscussion> discussions, Review review) {
        Set<UUID> authorIds = discussions.stream()
                .filter(discussion -> presentationPolicy.exposesAuthor(discussion, review.isAnonymous()))
                .map(ReviewDiscussion::getUserId)
                .collect(Collectors.toSet());
        if (authorIds.isEmpty()) {
            return Map.of();
        }
        return profileRepository.findByUserIdIn(authorIds).stream()
                .collect(Collectors.toMap(Profile::getUserId, Function.identity(), (first, second) -> first));
    }

    private static void requireViewer(UUID viewerId) {
        if (viewerId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0) {
            throw new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException("O tamanho da página deve ser entre 1 e 50", HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE");
        }
    }
}
