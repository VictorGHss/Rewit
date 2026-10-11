package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseView;
import com.rewit.application.dto.business.BusinessResponseDtos.PublishDiscussionBusinessResponseCommand;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.application.port.BusinessResponseRepository;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateLimiter;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.UserFollowRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.application.service.AccountStatusPolicy;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.application.service.ReviewVisibilityPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AccountStatus;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.BusinessResponseStatus;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.Role;
import com.rewit.domain.enums.VerificationStatus;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.BusinessResponse;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewDiscussion;
import com.rewit.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Casos de uso das respostas oficiais a comentários (C11)")
class DiscussionBusinessResponseUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    @Mock
    private UserRepository userRepository;
    @Mock
    private BusinessAccountRepository businessAccountRepository;
    @Mock
    private PlaceRepository placeRepository;
    @Mock
    private DiscussionRepository discussionRepository;
    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private UserFollowRepository userFollowRepository;
    @Mock
    private BusinessResponseRepository businessResponseRepository;
    @Mock
    private RateLimiter rateLimiter;

    private PublishDiscussionBusinessResponseUseCase publish;
    private ListDiscussionBusinessResponsesUseCase list;
    private GetBusinessResponseEligibilityUseCase eligibility;

    private final UUID actor = UUID.randomUUID();
    private final UUID reviewer = UUID.randomUUID();
    private final UUID commenter = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID placeId = UUID.randomUUID();
    private final UUID reviewId = UUID.randomUUID();
    private final UUID rootId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        BusinessRepresentationPolicy policy = new BusinessRepresentationPolicy(new AccountStatusPolicy(userRepository),
                businessAccountRepository, placeRepository);
        ReviewVisibilityPolicy visibility = new ReviewVisibilityPolicy(userFollowRepository);
        publish = new PublishDiscussionBusinessResponseUseCase(policy, discussionRepository, reviewRepository, visibility,
                businessResponseRepository, rateLimiter);
        list = new ListDiscussionBusinessResponsesUseCase(discussionRepository, reviewRepository, visibility,
                businessResponseRepository);
        eligibility = new GetBusinessResponseEligibilityUseCase(policy, reviewRepository, visibility,
                businessAccountRepository, placeRepository, businessResponseRepository);

        lenient().when(userRepository.findById(actor)).thenReturn(Optional.of(user(actor, Role.USER)));
        lenient().when(businessAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account(accountId, actor, VerificationStatus.APPROVED)));
        lenient().when(discussionRepository.findById(rootId)).thenReturn(Optional.of(discussion(rootId, null, DiscussionStatus.ACTIVE)));
        lenient().when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review(ReviewStatus.ACTIVE, "PUBLIC")));
        lenient().when(reviewRepository.findAssociatedPlaceIds(reviewId)).thenReturn(Set.of(placeId));
        lenient().when(placeRepository.findByIdForUpdate(placeId))
                .thenReturn(Optional.of(place(placeId, "Padaria Central - Centro", accountId)));
        lenient().when(businessResponseRepository.findActive(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(businessResponseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ---------------------------------------------------------------- publicação

    @Test
    @DisplayName("USER administrador responde ao comentário raiz: avaliação do comentário, comentário, conta, local e autor")
    void userPublishes() {
        BusinessResponseView view = publish.execute(command(rootId, placeId, "  Vamos verificar  "), NOW);

        ArgumentCaptor<BusinessResponse> saved = ArgumentCaptor.forClass(BusinessResponse.class);
        verify(businessResponseRepository).save(saved.capture());
        BusinessResponse response = saved.getValue();
        assertEquals(reviewId, response.getReviewId());
        assertEquals(rootId, response.getDiscussionId());
        assertEquals(accountId, response.getBusinessAccountId());
        assertEquals(placeId, response.getPlaceId());
        assertEquals(actor, response.getAuthorUserId());
        assertEquals(BusinessResponseStatus.ACTIVE, response.getStatus());
        assertEquals("Vamos verificar", response.getContent());

        assertEquals(reviewId, view.reviewId());
        assertEquals(rootId, view.discussionId());
        assertEquals("Padaria Central LTDA", view.corporateName());
        assertEquals("Padaria Central - Centro", view.placeName());
        verify(businessResponseRepository).findActive(reviewId, rootId, accountId);
        verify(rateLimiter).acquireOrThrow(RateLimitedAction.BUSINESS_RESPONSE_CREATION, RateLimitSubject.ofUser(actor));
    }

    @Test
    @DisplayName("MODERATOR responde pela própria conta comercial, sem depender da role")
    void moderatorPublishesAsBusiness() {
        when(userRepository.findById(actor)).thenReturn(Optional.of(user(actor, Role.MODERATOR)));

        assertEquals(rootId, publish.execute(command(rootId, placeId, "Resposta"), NOW).discussionId());
    }

    @Test
    @DisplayName("Ordem: autorização, rate limit, comentário, avaliação, lock do local, checagem prévia e gravação")
    void publicationOrder() {
        publish.execute(command(rootId, placeId, "Resposta"), NOW);

        InOrder order = inOrder(businessAccountRepository, rateLimiter, discussionRepository, reviewRepository,
                placeRepository, businessResponseRepository);
        order.verify(businessAccountRepository).findById(accountId);
        order.verify(rateLimiter).acquireOrThrow(eq(RateLimitedAction.BUSINESS_RESPONSE_CREATION), any());
        order.verify(discussionRepository).findById(rootId);
        order.verify(reviewRepository).findById(reviewId);
        order.verify(placeRepository).findByIdForUpdate(placeId);
        order.verify(businessResponseRepository).findActive(reviewId, rootId, accountId);
        order.verify(businessResponseRepository).save(any());
    }

    @Test
    @DisplayName("Conta PENDING ou de outro usuário: recusada sem ler comentário, avaliação nem respostas")
    void unauthorizedAccount() {
        when(businessAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account(accountId, actor, VerificationStatus.PENDING)));
        assertEquals("BUSINESS_ACCOUNT_NOT_APPROVED", assertThrows(BusinessException.class,
                () -> publish.execute(command(rootId, placeId, "x"), NOW)).getErrorCode());

        when(businessAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account(accountId, UUID.randomUUID(), VerificationStatus.APPROVED)));
        assertEquals("BUSINESS_ACCOUNT_NOT_FOUND", assertThrows(BusinessException.class,
                () -> publish.execute(command(rootId, placeId, "x"), NOW)).getErrorCode());

        verifyNoInteractions(rateLimiter, discussionRepository, reviewRepository, placeRepository, businessResponseRepository);
    }

    @Test
    @DisplayName("Local que não representa a avaliação do comentário: 403 sem gravar")
    void placeNotEligible() {
        assertEquals("BUSINESS_NOT_RESPONSIBLE", assertThrows(BusinessException.class,
                () -> publish.execute(command(rootId, UUID.randomUUID(), "x"), NOW)).getErrorCode());

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(place(placeId, "Centro", UUID.randomUUID())));
        assertEquals("BUSINESS_NOT_RESPONSIBLE", assertThrows(BusinessException.class,
                () -> publish.execute(command(rootId, placeId, "x"), NOW)).getErrorCode());

        verify(businessResponseRepository, never()).save(any());
    }

    @Test
    @DisplayName("Comentário inexistente ou em quarentena: o mesmo 404, sem revelar o motivo")
    void missingOrQuarantinedDiscussion() {
        UUID missing = UUID.randomUUID();
        when(discussionRepository.findById(missing)).thenReturn(Optional.empty());
        UUID quarantined = UUID.randomUUID();
        when(discussionRepository.findById(quarantined))
                .thenReturn(Optional.of(discussion(quarantined, null, DiscussionStatus.UNDER_REVIEW)));

        for (UUID target : new UUID[] {missing, quarantined}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> publish.execute(command(target, placeId, "x"), NOW));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            assertEquals("DISCUSSION_NOT_FOUND", ex.getErrorCode());
        }
        verifyNoInteractions(reviewRepository, placeRepository, businessResponseRepository);
    }

    @Test
    @DisplayName("Resposta de outro comentário ou comentário removido: 409 DISCUSSION_NOT_RESPONDABLE")
    void replyOrRemovedDiscussion() {
        UUID reply = UUID.randomUUID();
        when(discussionRepository.findById(reply)).thenReturn(Optional.of(discussion(reply, rootId, DiscussionStatus.ACTIVE)));
        UUID removed = UUID.randomUUID();
        when(discussionRepository.findById(removed)).thenReturn(Optional.of(discussion(removed, null, DiscussionStatus.REMOVED)));

        for (UUID target : new UUID[] {reply, removed}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> publish.execute(command(target, placeId, "x"), NOW));
            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertEquals("DISCUSSION_NOT_RESPONDABLE", ex.getErrorCode());
        }
        verifyNoInteractions(placeRepository, businessResponseRepository);
    }

    @Test
    @DisplayName("A avaliação do comentário decide o acesso antes de revelar o estado do comentário")
    void reviewAccessComesFirst() {
        UUID removed = UUID.randomUUID();
        when(discussionRepository.findById(removed)).thenReturn(Optional.of(discussion(removed, null, DiscussionStatus.REMOVED)));
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review(ReviewStatus.ACTIVE, "PRIVATE")));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> publish.execute(command(removed, placeId, "x"), NOW)).getStatus());

        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review(ReviewStatus.UNDER_REVIEW, "PUBLIC")));
        assertEquals("REVIEW_NOT_FOUND", assertThrows(BusinessException.class,
                () -> publish.execute(command(rootId, placeId, "x"), NOW)).getErrorCode());

        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review(ReviewStatus.ACTIVE, "FOLLOWERS")));
        when(userFollowRepository.isFollowing(actor, reviewer)).thenReturn(true);
        assertEquals("REVIEW_NOT_RESPONDABLE", assertThrows(BusinessException.class,
                () -> publish.execute(command(rootId, placeId, "x"), NOW)).getErrorCode());

        verifyNoInteractions(placeRepository, businessResponseRepository);
    }

    @Test
    @DisplayName("Conteúdo inválido: 400 antes de qualquer I/O")
    void invalidContent() {
        assertEquals("EMPTY_CONTENT", assertThrows(BusinessException.class,
                () -> publish.execute(command(rootId, placeId, " "), NOW)).getErrorCode());
        assertEquals("INVALID_CONTENT_LENGTH", assertThrows(BusinessException.class,
                () -> publish.execute(command(rootId, placeId, "a".repeat(2001)), NOW)).getErrorCode());
        verifyNoInteractions(userRepository, businessAccountRepository, rateLimiter, discussionRepository,
                reviewRepository, placeRepository, businessResponseRepository);
    }

    @Test
    @DisplayName("Resposta ativa da empresa ao comentário: 409 pela checagem prévia, sem gravar")
    void duplicateByPrecheck() {
        when(businessResponseRepository.findActive(reviewId, rootId, accountId)).thenReturn(Optional.of(
                BusinessResponse.publish(reviewId, rootId, accountId, placeId, actor, "Anterior", NOW)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> publish.execute(command(rootId, placeId, "x"), NOW));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("BUSINESS_RESPONSE_ALREADY_EXISTS", ex.getErrorCode());
        verify(businessResponseRepository, never()).save(any());
    }

    @Test
    @DisplayName("Corrida: só a violação do índice do comentário vira 409; a do índice da avaliação ou FK não")
    void concurrentDuplicateTranslatedOnlyForDiscussionIndex() {
        doThrow(integrityViolation("duplicate key value violates unique constraint \"uq_business_responses_discussion_active\""))
                .when(businessResponseRepository).save(any());
        assertEquals("BUSINESS_RESPONSE_ALREADY_EXISTS", assertThrows(BusinessException.class,
                () -> publish.execute(command(rootId, placeId, "x"), NOW)).getErrorCode());

        for (String message : new String[] {
                "duplicate key value violates unique constraint \"uq_business_responses_review_active\"",
                "violates foreign key constraint \"fk_business_responses_discussion\""}) {
            DataIntegrityViolationException other = integrityViolation(message);
            doThrow(other).when(businessResponseRepository).save(any());
            assertSame(other, assertThrows(DataIntegrityViolationException.class,
                    () -> publish.execute(command(rootId, placeId, "x"), NOW)));
        }
    }

    @Test
    @DisplayName("Rate limit aplicado à publicação; a elegibilidade não consome limite")
    void rateLimit() {
        when(rateLimiter.acquireOrThrow(eq(RateLimitedAction.BUSINESS_RESPONSE_CREATION), any()))
                .thenThrow(new BusinessException("Limite", HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(BusinessException.class,
                () -> publish.execute(command(rootId, placeId, "x"), NOW)).getStatus());
        verifyNoInteractions(discussionRepository, businessResponseRepository);

        when(businessAccountRepository.findByUserId(actor)).thenReturn(List.of(account(accountId, actor, VerificationStatus.APPROVED)));
        when(placeRepository.findById(placeId)).thenReturn(Optional.of(place(placeId, "Centro", accountId)));
        eligibility.execute(actor, reviewId);
        verify(rateLimiter, times(1)).acquireOrThrow(any(), any());
    }

    // ---------------------------------------------------------------- listagem

    @Test
    @DisplayName("Listagem: leitor autorizado recebe a página do repositório, sem autor pessoal nem documento")
    void listForAuthorizedViewer() {
        PageResult<BusinessResponseView> page = PageResult.of(List.of(new BusinessResponseView(UUID.randomUUID(),
                reviewId, rootId, accountId, "Padaria Central LTDA", placeId, "Centro", "Obrigado", NOW)), 1, 10, 11);
        when(businessResponseRepository.findActiveDiscussionResponseViews(rootId, 1, 10)).thenReturn(page);

        assertSame(page, list.execute(reviewer, rootId, 1, 10));

        List<String> components = Arrays.stream(BusinessResponseView.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();
        assertEquals(List.of("id", "reviewId", "discussionId", "businessAccountId", "corporateName", "placeId",
                "placeName", "content", "createdAt"), components);
    }

    @Test
    @DisplayName("Listagem: comentário inexistente, resposta de outro, em quarentena ou removido responde o mesmo 404")
    void listHidesUnavailableDiscussions() {
        UUID missing = UUID.randomUUID();
        when(discussionRepository.findById(missing)).thenReturn(Optional.empty());
        UUID reply = UUID.randomUUID();
        when(discussionRepository.findById(reply)).thenReturn(Optional.of(discussion(reply, rootId, DiscussionStatus.ACTIVE)));
        UUID quarantined = UUID.randomUUID();
        when(discussionRepository.findById(quarantined))
                .thenReturn(Optional.of(discussion(quarantined, null, DiscussionStatus.UNDER_REVIEW)));
        UUID removed = UUID.randomUUID();
        when(discussionRepository.findById(removed)).thenReturn(Optional.of(discussion(removed, null, DiscussionStatus.REMOVED)));

        for (UUID target : new UUID[] {missing, reply, quarantined, removed}) {
            BusinessException ex = assertThrows(BusinessException.class, () -> list.execute(reviewer, target, 0, 20));
            assertEquals("DISCUSSION_NOT_FOUND", ex.getErrorCode());
        }
        verifyNoInteractions(reviewRepository, businessResponseRepository);
    }

    @Test
    @DisplayName("Listagem: avaliação privada de terceiro, de seguidores sem seguir, em quarentena ou removida não expõe respostas")
    void listRespectsReviewVisibility() {
        UUID stranger = UUID.randomUUID();
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review(ReviewStatus.ACTIVE, "PRIVATE")));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> list.execute(stranger, rootId, 0, 20)).getStatus());

        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review(ReviewStatus.ACTIVE, "FOLLOWERS")));
        when(userFollowRepository.isFollowing(stranger, reviewer)).thenReturn(false);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> list.execute(stranger, rootId, 0, 20)).getStatus());

        for (ReviewStatus status : new ReviewStatus[] {ReviewStatus.UNDER_REVIEW, ReviewStatus.REMOVED}) {
            when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review(status, "PUBLIC")));
            assertEquals("REVIEW_NOT_FOUND", assertThrows(BusinessException.class,
                    () -> list.execute(reviewer, rootId, 0, 20)).getErrorCode());
        }
        verify(businessResponseRepository, never()).findActiveDiscussionResponseViews(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Listagem: ator e paginação validados antes de qualquer leitura")
    void listValidation() {
        assertEquals("UNAUTHORIZED", assertThrows(BusinessException.class,
                () -> list.execute(null, rootId, 0, 20)).getErrorCode());
        assertEquals("MISSING_DISCUSSION_ID", assertThrows(BusinessException.class,
                () -> list.execute(reviewer, null, 0, 20)).getErrorCode());
        assertEquals("INVALID_PAGE", assertThrows(BusinessException.class,
                () -> list.execute(reviewer, rootId, -1, 20)).getErrorCode());
        assertEquals("INVALID_PAGE_SIZE", assertThrows(BusinessException.class,
                () -> list.execute(reviewer, rootId, 0, 0)).getErrorCode());
        verifyNoInteractions(discussionRepository, reviewRepository, businessResponseRepository);
    }

    // ---------------------------------------------------------------- apoio

    private PublishDiscussionBusinessResponseCommand command(UUID discussionId, UUID chosenPlace, String content) {
        return new PublishDiscussionBusinessResponseCommand(actor, accountId, discussionId, chosenPlace, content);
    }

    /** Comentário de um terceiro (não o autor da avaliação): isFromOwner false, sem relação com a empresa. */
    private ReviewDiscussion discussion(UUID id, UUID parentId, DiscussionStatus status) {
        return new ReviewDiscussion(id, reviewId, commenter, parentId, "Comentário", false, status, NOW, NOW);
    }

    private Review review(ReviewStatus status, String visibility) {
        return new Review(reviewId, reviewer, null, "Ótimo café", false, false, status, visibility, null, null, null,
                NOW, NOW);
    }

    private static User user(UUID id, Role role) {
        return User.rehydrate(id, "ator@rewit.test", "hash", AuthProvider.LOCAL, null, AccountStatus.ACTIVE, true, role,
                null, NOW, NOW);
    }

    private static BusinessAccount account(UUID id, UUID adminUserId, VerificationStatus status) {
        return new BusinessAccount(id, adminUserId, "Padaria Central LTDA", "12345678000190", status, "FREE", NOW, NOW);
    }

    private static Place place(UUID id, String name, UUID claimedBy) {
        return new Place(id, name, "slug-" + id, "CAFE", null, "Rua A", "Curitiba", "PR", "BR", -25.42, -49.27, 50,
                "USER", false, claimedBy, "ACTIVE");
    }

    private static DataIntegrityViolationException integrityViolation(String message) {
        return new DataIntegrityViolationException("could not execute statement", new SQLException(message));
    }
}
