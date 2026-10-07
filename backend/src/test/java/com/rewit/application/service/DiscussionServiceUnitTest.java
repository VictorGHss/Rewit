package com.rewit.application.service;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.discussion.DiscussionDtos.CreateDiscussionCommand;
import com.rewit.application.dto.discussion.DiscussionDtos.DiscussionView;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.application.port.RateLimiter;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.UserFollowRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewDiscussion;
import com.rewit.infrastructure.ratelimit.RateLimitTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: DiscussionService e Regras de Negócio de Discussões (Step 20.0)")
class DiscussionServiceUnitTest {

    @Mock
    private AccountStatusPolicy accountStatusPolicy;

    @Mock
    private DiscussionRepository discussionRepository;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private UserFollowRepository userFollowRepository;

    private RateLimiter rateLimiter;
    private ReviewVisibilityPolicy reviewVisibilityPolicy;
    private DiscussionService discussionService;

    private final UUID reviewAuthorId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID thirdUserId = UUID.randomUUID();
    private final UUID reviewId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        rateLimiter = RateLimitTestSupport.inMemory();
        reviewVisibilityPolicy = new ReviewVisibilityPolicy(userFollowRepository);
        discussionService = new DiscussionService(
                discussionRepository,
                reviewRepository,
                accountStatusPolicy,
                reviewVisibilityPolicy,
                rateLimiter
        );
    }

    private Review createSampleReview(ReviewStatus status, String visibility) {
        return new Review(
                reviewId,
                reviewAuthorId,
                UUID.randomUUID(),
                "Avaliação de teste",
                false,
                false,
                status,
                visibility,
                null,
                null,
                null,
                Instant.now(),
                Instant.now()
        );
    }

    // 1. Criação de comentário raiz
    @Test
    @DisplayName("1. Deve criar comentário raiz com sucesso quando dados válidos")
    void shouldCreateRootDiscussionSuccessfully() {
        Review review = createSampleReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(discussionRepository.save(any(ReviewDiscussion.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, otherUserId, null, "Excelente avaliação!");
        DiscussionView view = discussionService.createDiscussion(cmd);

        assertNotNull(view);
        assertEquals(reviewId, view.reviewId());
        assertEquals(otherUserId, view.authorId());
        assertNull(view.parentId());
        assertEquals("Excelente avaliação!", view.content());
        assertFalse(view.isFromOwner());
        assertEquals("ACTIVE", view.status());
        verify(discussionRepository).save(any(ReviewDiscussion.class));
    }

    // 2. Criação de resposta
    @Test
    @DisplayName("2. Deve criar resposta a comentário raiz com sucesso quando dados válidos")
    void shouldCreateReplyDiscussionSuccessfully() {
        Review review = createSampleReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        UUID parentId = UUID.randomUUID();
        ReviewDiscussion parent = new ReviewDiscussion(parentId, reviewId, thirdUserId, null, "Pergunta sobre a avaliação", false);
        when(discussionRepository.findById(parentId)).thenReturn(Optional.of(parent));
        when(discussionRepository.save(any(ReviewDiscussion.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, otherUserId, parentId, "Minha resposta aqui.");
        DiscussionView view = discussionService.createDiscussion(cmd);

        assertNotNull(view);
        assertEquals(parentId, view.parentId());
        assertEquals("Minha resposta aqui.", view.content());
        verify(discussionRepository).save(any(ReviewDiscussion.class));
    }

    // 3. Cálculo de isFromOwner
    @Test
    @DisplayName("3. Deve calcular isFromOwner = true quando autor do comentário é o dono da Review")
    void shouldCalculateIsFromOwnerTrueWhenAuthorIsReviewOwner() {
        Review review = createSampleReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        ArgumentCaptor<ReviewDiscussion> captor = ArgumentCaptor.forClass(ReviewDiscussion.class);
        when(discussionRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, reviewAuthorId, null, "Obrigado pelo feedback!");
        DiscussionView view = discussionService.createDiscussion(cmd);

        assertTrue(view.isFromOwner());
        assertTrue(captor.getValue().isFromOwner());
    }

    // 4. parentId de outra Review
    @Test
    @DisplayName("4. Deve rejeitar resposta com parentId pertencente a outra Review")
    void shouldRejectReplyWhenParentBelongsToDifferentReview() {
        Review review = createSampleReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        UUID otherReviewId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        ReviewDiscussion parentFromOtherReview = new ReviewDiscussion(parentId, otherReviewId, thirdUserId, null, "Comentário de outra review", false);
        when(discussionRepository.findById(parentId)).thenReturn(Optional.of(parentFromOtherReview));

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, otherUserId, parentId, "Tentativa cruzada");
        BusinessException ex = assertThrows(BusinessException.class, () -> discussionService.createDiscussion(cmd));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_PARENT_DISCUSSION", ex.getErrorCode());
        verify(discussionRepository, never()).save(any());
    }

    // 5. Pai inexistente
    @Test
    @DisplayName("5. Deve rejeitar resposta quando comentário pai não existe")
    void shouldRejectReplyWhenParentDoesNotExist() {
        Review review = createSampleReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        UUID parentId = UUID.randomUUID();
        when(discussionRepository.findById(parentId)).thenReturn(Optional.empty());

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, otherUserId, parentId, "Resposta para pai fantasma");
        BusinessException ex = assertThrows(BusinessException.class, () -> discussionService.createDiscussion(cmd));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("DISCUSSION_NOT_FOUND", ex.getErrorCode());
        verify(discussionRepository, never()).save(any());
    }

    // 6. Pai removido
    @Test
    @DisplayName("6. Deve rejeitar resposta quando comentário pai está REMOVED")
    void shouldRejectReplyWhenParentIsRemoved() {
        Review review = createSampleReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        UUID parentId = UUID.randomUUID();
        ReviewDiscussion removedParent = new ReviewDiscussion(
                parentId, reviewId, thirdUserId, null, "Comentário deletado", false,
                "REMOVED", Instant.now(), Instant.now()
        );
        when(discussionRepository.findById(parentId)).thenReturn(Optional.of(removedParent));

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, otherUserId, parentId, "Tentativa de responder removido");
        BusinessException ex = assertThrows(BusinessException.class, () -> discussionService.createDiscussion(cmd));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("DISCUSSION_NOT_FOUND", ex.getErrorCode());
        verify(discussionRepository, never()).save(any());
    }

    // 7. Tentativa de nesting de segundo nível
    @Test
    @DisplayName("7. Deve rejeitar resposta a uma resposta (DISCUSSION_NESTING_LIMIT_EXCEEDED)")
    void shouldRejectSecondLevelNesting() {
        Review review = createSampleReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        UUID grandParentId = UUID.randomUUID();
        UUID parentReplyId = UUID.randomUUID();
        ReviewDiscussion replyDiscussion = new ReviewDiscussion(
                parentReplyId, reviewId, thirdUserId, grandParentId, "Eu já sou uma resposta", false
        );
        when(discussionRepository.findById(parentReplyId)).thenReturn(Optional.of(replyDiscussion));

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, otherUserId, parentReplyId, "Tentativa de 2º nível");
        BusinessException ex = assertThrows(BusinessException.class, () -> discussionService.createDiscussion(cmd));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("DISCUSSION_NESTING_LIMIT_EXCEEDED", ex.getErrorCode());
        verify(discussionRepository, never()).save(any());
    }

    // 8. Review inexistente
    @Test
    @DisplayName("8. Deve retornar 404 REVIEW_NOT_FOUND quando a Review não existe")
    void shouldReturn404WhenReviewDoesNotExist() {
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.empty());

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, otherUserId, null, "Comentário");
        BusinessException ex = assertThrows(BusinessException.class, () -> discussionService.createDiscussion(cmd));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("REVIEW_NOT_FOUND", ex.getErrorCode());
    }

    // 9. Review UNDER_REVIEW
    @Test
    @DisplayName("9. Deve retornar 404 REVIEW_NOT_FOUND quando a Review estiver UNDER_REVIEW")
    void shouldReturn404WhenReviewIsUnderReview() {
        Review review = createSampleReview(ReviewStatus.UNDER_REVIEW, "PUBLIC");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, otherUserId, null, "Comentário");
        BusinessException ex = assertThrows(BusinessException.class, () -> discussionService.createDiscussion(cmd));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("REVIEW_NOT_FOUND", ex.getErrorCode());
        verify(discussionRepository, never()).save(any());
    }

    // 10. Review REMOVED
    @Test
    @DisplayName("10. Deve retornar 404 REVIEW_NOT_FOUND quando a Review estiver REMOVED")
    void shouldReturn404WhenReviewIsRemoved() {
        Review review = createSampleReview(ReviewStatus.REMOVED, "PUBLIC");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, otherUserId, null, "Comentário");
        BusinessException ex = assertThrows(BusinessException.class, () -> discussionService.createDiscussion(cmd));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("REVIEW_NOT_FOUND", ex.getErrorCode());
        verify(discussionRepository, never()).save(any());
    }

    // 11. PUBLIC
    @Test
    @DisplayName("11. Deve permitir comentário em Review PUBLIC por qualquer usuário autenticado")
    void shouldAllowDiscussionOnPublicReview() {
        Review review = createSampleReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(discussionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, otherUserId, null, "Legal!");
        assertDoesNotThrow(() -> discussionService.createDiscussion(cmd));
    }

    // 12. FOLLOWERS autorizado
    @Test
    @DisplayName("12. Deve permitir comentário em Review FOLLOWERS quando usuário for seguidor")
    void shouldAllowDiscussionOnFollowersReviewWhenRequesterIsFollower() {
        Review review = createSampleReview(ReviewStatus.ACTIVE, "FOLLOWERS");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(userFollowRepository.isFollowing(otherUserId, reviewAuthorId)).thenReturn(true);
        when(discussionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, otherUserId, null, "Seguidor comentando");
        assertDoesNotThrow(() -> discussionService.createDiscussion(cmd));
    }

    // 13. FOLLOWERS não autorizado
    @Test
    @DisplayName("13. Deve rejeitar com 403 comentário em Review FOLLOWERS quando usuário não for seguidor")
    void shouldRejectDiscussionOnFollowersReviewWhenRequesterIsNotFollower() {
        Review review = createSampleReview(ReviewStatus.ACTIVE, "FOLLOWERS");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(userFollowRepository.isFollowing(otherUserId, reviewAuthorId)).thenReturn(false);

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, otherUserId, null, "Não seguidor tentando");
        BusinessException ex = assertThrows(BusinessException.class, () -> discussionService.createDiscussion(cmd));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("FORBIDDEN", ex.getErrorCode());
        verify(discussionRepository, never()).save(any());
    }

    // 14. PRIVATE por terceiro
    @Test
    @DisplayName("14. Deve rejeitar com 403 comentário em Review PRIVATE feito por terceiro")
    void shouldRejectDiscussionOnPrivateReviewByThirdParty() {
        Review review = createSampleReview(ReviewStatus.ACTIVE, "PRIVATE");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, otherUserId, null, "Terceiro em review privada");
        BusinessException ex = assertThrows(BusinessException.class, () -> discussionService.createDiscussion(cmd));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("FORBIDDEN", ex.getErrorCode());
        verify(discussionRepository, never()).save(any());
    }

    // 15. PRIVATE pelo autor
    @Test
    @DisplayName("15. Deve permitir comentário em Review PRIVATE quando feito pelo próprio autor da review")
    void shouldAllowDiscussionOnPrivateReviewByAuthor() {
        Review review = createSampleReview(ReviewStatus.ACTIVE, "PRIVATE");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(discussionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, reviewAuthorId, null, "Nota própria do autor");
        assertDoesNotThrow(() -> discussionService.createDiscussion(cmd));
    }

    // 16. Conteúdo vazio
    @Test
    @DisplayName("16. Deve rejeitar criação quando o conteúdo for vazio ou whitespace")
    void shouldRejectDiscussionWhenContentIsEmptyOrWhitespace() {
        Review review = createSampleReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        CreateDiscussionCommand cmdBlank = new CreateDiscussionCommand(reviewId, otherUserId, null, "   ");
        BusinessException ex = assertThrows(BusinessException.class, () -> discussionService.createDiscussion(cmdBlank));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("EMPTY_CONTENT", ex.getErrorCode());
        verify(discussionRepository, never()).save(any());
    }

    // 17. Conteúdo acima do limite
    @Test
    @DisplayName("17. Deve rejeitar criação quando o conteúdo exceder 2000 caracteres")
    void shouldRejectDiscussionWhenContentExceedsMaxLength() {
        Review review = createSampleReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        String longContent = "A".repeat(2001);
        CreateDiscussionCommand cmdLong = new CreateDiscussionCommand(reviewId, otherUserId, null, longContent);
        BusinessException ex = assertThrows(BusinessException.class, () -> discussionService.createDiscussion(cmdLong));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_CONTENT_LENGTH", ex.getErrorCode());
        verify(discussionRepository, never()).save(any());
    }

    // 18. authorUserId não pode ser forjado pelo payload (validado pela chamada do service com ID do contexto de auth)
    @Test
    @DisplayName("18. authorUserId é atribuído exclusivamente pela identidade autenticada")
    void shouldEnforceAuthenticatedUserAsAuthor() {
        Review review = createSampleReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        ArgumentCaptor<ReviewDiscussion> captor = ArgumentCaptor.forClass(ReviewDiscussion.class);
        when(discussionRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        UUID realAuthenticatedUser = UUID.randomUUID();
        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, realAuthenticatedUser, null, "Comentário legítimo");
        discussionService.createDiscussion(cmd);

        assertEquals(realAuthenticatedUser, captor.getValue().getUserId());
    }

    // 19. isFromOwner não pode ser forjado (calculado internamente pelo backend)
    @Test
    @DisplayName("19. isFromOwner é calculado estritamente no backend e independe de qualquer parâmetro externo")
    void shouldCalculateIsFromOwnerStrictlyOnBackend() {
        Review review = createSampleReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        ArgumentCaptor<ReviewDiscussion> captor = ArgumentCaptor.forClass(ReviewDiscussion.class);
        when(discussionRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // Usuário diferente do dono da review sempre resulta em isFromOwner = false
        CreateDiscussionCommand cmdNotOwner = new CreateDiscussionCommand(reviewId, otherUserId, null, "Não sou dono");
        DiscussionView view = discussionService.createDiscussion(cmdNotOwner);

        assertFalse(view.isFromOwner());
        assertFalse(captor.getValue().isFromOwner());
    }

    // 20. Remoção por autor
    @Test
    @DisplayName("20. Deve permitir remoção (soft delete) pelo autor do comentário com sucesso")
    void shouldAllowRemovalByDiscussionAuthor() {
        UUID discussionId = UUID.randomUUID();
        ReviewDiscussion discussion = new ReviewDiscussion(
                discussionId, reviewId, otherUserId, null, "Comentário que será deletado", false
        );
        when(discussionRepository.findByIdForUpdate(discussionId)).thenReturn(Optional.of(discussion));

        discussionService.deleteDiscussion(discussionId, otherUserId);

        assertEquals(DiscussionStatus.REMOVED, discussion.getStatus());
        assertFalse(discussion.isActive());
        verify(discussionRepository).save(discussion);
    }

    // 21. Remoção por terceiro bloqueada
    @Test
    @DisplayName("21. Deve bloquear remoção de comentário por terceiro com 403 FORBIDDEN")
    void shouldBlockRemovalByThirdPartyWithForbidden() {
        UUID discussionId = UUID.randomUUID();
        ReviewDiscussion discussion = new ReviewDiscussion(
                discussionId, reviewId, otherUserId, null, "Comentário alheio", false
        );
        when(discussionRepository.findByIdForUpdate(discussionId)).thenReturn(Optional.of(discussion));

        BusinessException ex = assertThrows(BusinessException.class, () -> discussionService.deleteDiscussion(discussionId, thirdUserId));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("FORBIDDEN", ex.getErrorCode());
        assertEquals(DiscussionStatus.ACTIVE, discussion.getStatus());
        verify(discussionRepository, never()).save(any());
    }

    // Remoção idempotente
    @Test
    @DisplayName("Remoção de comentário já REMOVED deve ser idempotente")
    void shouldBeIdempotentWhenDeletingAlreadyRemovedDiscussion() {
        UUID discussionId = UUID.randomUUID();
        ReviewDiscussion discussion = new ReviewDiscussion(
                discussionId, reviewId, otherUserId, null, "Já deletado", false,
                "REMOVED", Instant.now(), Instant.now()
        );
        when(discussionRepository.findByIdForUpdate(discussionId)).thenReturn(Optional.of(discussion));

        assertDoesNotThrow(() -> discussionService.deleteDiscussion(discussionId, otherUserId));
        verify(discussionRepository, never()).save(any());
    }

    // Listagem - Ordenação e paginação
    @Test
    @DisplayName("Listagem deve retornar apenas discussões ACTIVE de forma cronológica")
    void shouldReturnActiveDiscussionsInChronologicalOrder() {
        Review review = createSampleReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        ReviewDiscussion d1 = new ReviewDiscussion(UUID.randomUUID(), reviewId, otherUserId, null, "Primeiro", false);
        ReviewDiscussion d2 = new ReviewDiscussion(UUID.randomUUID(), reviewId, reviewAuthorId, d1.getId(), "Segundo", true);
        PageResult<ReviewDiscussion> repoPage = PageResult.of(List.of(d1, d2), 0, 20, 2L);

        when(discussionRepository.findActiveByReviewId(reviewId, 0, 20)).thenReturn(repoPage);

        PageResult<DiscussionView> result = discussionService.findDiscussionsByReviewId(reviewId, thirdUserId, 0, 20);

        assertEquals(2, result.content().size());
        assertEquals(d1.getId(), result.content().get(0).id());
        assertEquals(d2.getId(), result.content().get(1).id());
        assertEquals(2L, result.totalElements());
    }

    // Listagem - Review UNDER_REVIEW deve bloquear com 404
    @Test
    @DisplayName("Listagem deve retornar 404 quando Review estiver UNDER_REVIEW")
    void shouldReturn404OnListingWhenReviewIsUnderReview() {
        Review review = createSampleReview(ReviewStatus.UNDER_REVIEW, "PUBLIC");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                discussionService.findDiscussionsByReviewId(reviewId, thirdUserId, 0, 20));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("REVIEW_NOT_FOUND", ex.getErrorCode());
        verify(discussionRepository, never()).findActiveByReviewId(any(), anyInt(), anyInt());
    }

    // Auditoria de Anonimato - Caso A: Review anônima + comentário do proprietário (authorId deve ser mascarado como null)
    @Test
    @DisplayName("Caso A: Review anônima + comentário do dono deve retornar authorId = null e isFromOwner = true")
    void shouldMaskAuthorIdWhenOwnerCommentsOnAnonymousReview() {
        Review anonymousReview = new Review(
                reviewId, reviewAuthorId, UUID.randomUUID(), "Review anônima",
                true, false, ReviewStatus.ACTIVE, "PUBLIC",
                null, null, null, Instant.now(), Instant.now()
        );
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(anonymousReview));
        when(discussionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, reviewAuthorId, null, "Dono comentando anonimamente");
        DiscussionView view = discussionService.createDiscussion(cmd);

        assertNull(view.authorId(), "O authorId deve ser null para proteger a identidade do autor da review anônima");
        assertTrue(view.isFromOwner(), "O isFromOwner deve ser mantido como true para valor semântico de resposta do proprietário");
    }

    // Auditoria de Anonimato - Caso B: Review anônima + comentário de terceiro (authorId deve ser revelado normalmente)
    @Test
    @DisplayName("Caso B: Review anônima + comentário de terceiro deve expor o authorId do terceiro normalmente")
    void shouldExposeAuthorIdWhenThirdPartyCommentsOnAnonymousReview() {
        Review anonymousReview = new Review(
                reviewId, reviewAuthorId, UUID.randomUUID(), "Review anônima",
                true, false, ReviewStatus.ACTIVE, "PUBLIC",
                null, null, null, Instant.now(), Instant.now()
        );
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(anonymousReview));
        when(discussionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, otherUserId, null, "Terceiro comentando");
        DiscussionView view = discussionService.createDiscussion(cmd);

        assertEquals(otherUserId, view.authorId(), "O autor do terceiro deve ser exposto normalmente");
        assertFalse(view.isFromOwner());
    }

    // Auditoria de Anonimato - Caso C: Review não anônima + comentário do proprietário (authorId deve ser retornado normalmente)
    @Test
    @DisplayName("Caso C: Review não anônima + comentário do proprietário deve expor authorId e isFromOwner = true")
    void shouldExposeAuthorIdWhenOwnerCommentsOnNonAnonymousReview() {
        Review publicReview = createSampleReview(ReviewStatus.ACTIVE, "PUBLIC");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(publicReview));
        when(discussionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(reviewId, reviewAuthorId, null, "Dono comentando publicamente");
        DiscussionView view = discussionService.createDiscussion(cmd);

        assertEquals(reviewAuthorId, view.authorId(), "O authorId deve ser exposto normalmente quando a review não é anônima");
        assertTrue(view.isFromOwner());
    }

    // Auditoria de Anonimato - Caso E: Garantir que a listagem também mascara o authorId do proprietário em Review anônima
    @Test
    @DisplayName("Caso E: Listagem de discussões de Review anônima deve mascarar authorId do dono e manter o de terceiros")
    void shouldPreserveAnonymityRulesOnListing() {
        Review anonymousReview = new Review(
                reviewId, reviewAuthorId, UUID.randomUUID(), "Review anônima",
                true, false, ReviewStatus.ACTIVE, "PUBLIC",
                null, null, null, Instant.now(), Instant.now()
        );
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(anonymousReview));

        ReviewDiscussion thirdPartyComment = new ReviewDiscussion(UUID.randomUUID(), reviewId, otherUserId, null, "Comentário de terceiro", false);
        ReviewDiscussion ownerComment = new ReviewDiscussion(UUID.randomUUID(), reviewId, reviewAuthorId, thirdPartyComment.getId(), "Resposta do dono anônimo", true);

        PageResult<ReviewDiscussion> repoPage = PageResult.of(List.of(thirdPartyComment, ownerComment), 0, 20, 2L);
        when(discussionRepository.findActiveByReviewId(reviewId, 0, 20)).thenReturn(repoPage);

        PageResult<DiscussionView> result = discussionService.findDiscussionsByReviewId(reviewId, thirdUserId, 0, 20);

        assertEquals(2, result.content().size());

        // Comentário 1 (terceiro): authorId preservado
        DiscussionView v1 = result.content().get(0);
        assertEquals(otherUserId, v1.authorId());
        assertFalse(v1.isFromOwner());

        // Comentário 2 (proprietário anônimo): authorId mascarado para null, isFromOwner = true
        DiscussionView v2 = result.content().get(1);
        assertNull(v2.authorId(), "authorId do proprietário deve ser nulo na listagem de review anônima");
        assertTrue(v2.isFromOwner(), "isFromOwner deve permanecer true");
    }
}

