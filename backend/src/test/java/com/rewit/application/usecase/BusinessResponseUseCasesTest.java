package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseEligibilityView;
import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseView;
import com.rewit.application.dto.business.BusinessResponseDtos.EligibleBusinessAccount;
import com.rewit.application.dto.business.BusinessResponseDtos.EligiblePlace;
import com.rewit.application.dto.business.BusinessResponseDtos.PublishReviewBusinessResponseCommand;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.application.port.BusinessResponseRepository;
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
import com.rewit.domain.enums.BusinessResponseRemovalSource;
import com.rewit.domain.enums.BusinessResponseStatus;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.Role;
import com.rewit.domain.enums.VerificationStatus;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.BusinessResponse;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Review;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Casos de uso das respostas oficiais a avaliações (C11)")
class BusinessResponseUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    @Mock
    private UserRepository userRepository;
    @Mock
    private BusinessAccountRepository businessAccountRepository;
    @Mock
    private PlaceRepository placeRepository;
    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private UserFollowRepository userFollowRepository;
    @Mock
    private BusinessResponseRepository businessResponseRepository;
    @Mock
    private RateLimiter rateLimiter;

    private PublishReviewBusinessResponseUseCase publish;
    private ListReviewBusinessResponsesUseCase list;
    private GetBusinessResponseEligibilityUseCase eligibility;
    private RemoveBusinessResponseUseCase remove;

    private final UUID actor = UUID.randomUUID();
    private final UUID reviewer = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID placeId = UUID.randomUUID();
    private final UUID reviewId = UUID.randomUUID();
    private BusinessAccount account;
    private Place place;

    @BeforeEach
    void setUp() {
        BusinessRepresentationPolicy policy = new BusinessRepresentationPolicy(new AccountStatusPolicy(userRepository),
                businessAccountRepository, placeRepository);
        ReviewVisibilityPolicy visibility = new ReviewVisibilityPolicy(userFollowRepository);
        publish = new PublishReviewBusinessResponseUseCase(policy, reviewRepository, visibility,
                businessResponseRepository, rateLimiter);
        list = new ListReviewBusinessResponsesUseCase(reviewRepository, visibility, businessResponseRepository);
        eligibility = new GetBusinessResponseEligibilityUseCase(policy, reviewRepository, visibility,
                businessAccountRepository, placeRepository, businessResponseRepository);
        remove = new RemoveBusinessResponseUseCase(policy, businessResponseRepository, businessAccountRepository);

        account = account(accountId, actor, VerificationStatus.APPROVED, "Padaria Central LTDA");
        place = place(placeId, "Padaria Central - Centro", accountId, "ACTIVE");
        lenient().when(userRepository.findById(actor)).thenReturn(Optional.of(user(actor, Role.USER)));
        lenient().when(businessAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
        lenient().when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review(ReviewStatus.ACTIVE, "PUBLIC")));
        lenient().when(reviewRepository.findAssociatedPlaceIds(reviewId)).thenReturn(Set.of(placeId));
        lenient().when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(place));
        lenient().when(businessResponseRepository.findActive(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(businessResponseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ---------------------------------------------------------------- publicação

    @Test
    @DisplayName("USER administrador publica: resposta ativa pelo local escolhido, autor só no registro interno")
    void userPublishes() {
        BusinessResponseView view = publish.execute(command(placeId, "  Obrigado pela visita!  "), NOW);

        ArgumentCaptor<BusinessResponse> saved = ArgumentCaptor.forClass(BusinessResponse.class);
        verify(businessResponseRepository).save(saved.capture());
        BusinessResponse response = saved.getValue();
        assertEquals(reviewId, response.getReviewId());
        assertNull(response.getDiscussionId());
        assertEquals(accountId, response.getBusinessAccountId());
        assertEquals(placeId, response.getPlaceId());
        assertEquals(actor, response.getAuthorUserId());
        assertEquals(BusinessResponseStatus.ACTIVE, response.getStatus());

        assertEquals(response.getId(), view.id());
        assertEquals(reviewId, view.reviewId());
        assertEquals(accountId, view.businessAccountId());
        assertEquals("Padaria Central LTDA", view.corporateName());
        assertEquals(placeId, view.placeId());
        assertEquals("Padaria Central - Centro", view.placeName());
        assertEquals("Obrigado pela visita!", view.content());
        assertEquals(NOW, view.createdAt());
        verify(rateLimiter).acquireOrThrow(RateLimitedAction.BUSINESS_RESPONSE_CREATION, RateLimitSubject.ofUser(actor));
    }

    @Test
    @DisplayName("MODERATOR que administra uma empresa publica como empresa, sem depender da role")
    void moderatorPublishesAsBusiness() {
        when(userRepository.findById(actor)).thenReturn(Optional.of(user(actor, Role.MODERATOR)));

        assertEquals(accountId, publish.execute(command(placeId, "Resposta"), NOW).businessAccountId());
    }

    @Test
    @DisplayName("Ordem: autorização, rate limit, avaliação, lock do local, checagem prévia e gravação")
    void publicationOrder() {
        publish.execute(command(placeId, "Resposta"), NOW);

        InOrder order = inOrder(businessAccountRepository, rateLimiter, reviewRepository, placeRepository,
                businessResponseRepository);
        order.verify(businessAccountRepository).findById(accountId);
        order.verify(rateLimiter).acquireOrThrow(eq(RateLimitedAction.BUSINESS_RESPONSE_CREATION), any());
        order.verify(reviewRepository).findById(reviewId);
        order.verify(placeRepository).findByIdForUpdate(placeId);
        order.verify(businessResponseRepository).findActive(reviewId, null, accountId);
        order.verify(businessResponseRepository).save(any());
    }

    @Test
    @DisplayName("Conteúdo inválido: 400 antes de qualquer I/O")
    void invalidContent() {
        assertEquals("EMPTY_CONTENT", assertThrows(BusinessException.class,
                () -> publish.execute(command(placeId, "   "), NOW)).getErrorCode());
        assertEquals("INVALID_CONTENT_LENGTH", assertThrows(BusinessException.class,
                () -> publish.execute(command(placeId, "a".repeat(2001)), NOW)).getErrorCode());

        verifyNoInteractions(userRepository, businessAccountRepository, rateLimiter, reviewRepository, placeRepository,
                businessResponseRepository);
    }

    @Test
    @DisplayName("Conta PENDING ou REJECTED: 409 sem consumir limite nem ler avaliação ou respostas")
    void accountNotApproved() {
        for (VerificationStatus status : new VerificationStatus[] {VerificationStatus.PENDING, VerificationStatus.REJECTED}) {
            when(businessAccountRepository.findById(accountId))
                    .thenReturn(Optional.of(account(accountId, actor, status, "Padaria Central LTDA")));

            BusinessException ex = assertThrows(BusinessException.class, () -> publish.execute(command(placeId, "x"), NOW));
            assertEquals("BUSINESS_ACCOUNT_NOT_APPROVED", ex.getErrorCode());
        }
        verifyNoInteractions(rateLimiter, reviewRepository, placeRepository, businessResponseRepository);
    }

    @Test
    @DisplayName("Conta de outro usuário: 404 BUSINESS_ACCOUNT_NOT_FOUND, nada mais é lido")
    void foreignAccount() {
        when(businessAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account(accountId, UUID.randomUUID(), VerificationStatus.APPROVED, "Outra")));

        assertEquals("BUSINESS_ACCOUNT_NOT_FOUND", assertThrows(BusinessException.class,
                () -> publish.execute(command(placeId, "x"), NOW)).getErrorCode());
        verifyNoInteractions(rateLimiter, reviewRepository, placeRepository, businessResponseRepository);
    }

    @Test
    @DisplayName("Local não associado à avaliação ou de outra empresa: 403 sem gravar")
    void placeNotEligible() {
        UUID elsewhere = UUID.randomUUID();
        assertEquals("BUSINESS_NOT_RESPONSIBLE", assertThrows(BusinessException.class,
                () -> publish.execute(command(elsewhere, "x"), NOW)).getErrorCode());

        when(placeRepository.findByIdForUpdate(placeId))
                .thenReturn(Optional.of(place(placeId, "Centro", UUID.randomUUID(), "ACTIVE")));
        assertEquals("BUSINESS_NOT_RESPONSIBLE", assertThrows(BusinessException.class,
                () -> publish.execute(command(placeId, "x"), NOW)).getErrorCode());

        verify(businessResponseRepository, never()).findActive(any(), any(), any());
        verify(businessResponseRepository, never()).save(any());
    }

    @Test
    @DisplayName("Avaliação em quarentena, removida ou inexistente: 404; privada de terceiro: 403")
    void unreadableReview() {
        for (ReviewStatus status : new ReviewStatus[] {ReviewStatus.UNDER_REVIEW, ReviewStatus.REMOVED}) {
            when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review(status, "PUBLIC")));
            BusinessException ex = assertThrows(BusinessException.class, () -> publish.execute(command(placeId, "x"), NOW));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            assertEquals("REVIEW_NOT_FOUND", ex.getErrorCode());
        }
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review(ReviewStatus.ACTIVE, "PRIVATE")));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> publish.execute(command(placeId, "x"), NOW)).getStatus());

        when(reviewRepository.findById(reviewId)).thenReturn(Optional.empty());
        assertEquals("REVIEW_NOT_FOUND", assertThrows(BusinessException.class,
                () -> publish.execute(command(placeId, "x"), NOW)).getErrorCode());

        verifyNoInteractions(placeRepository, businessResponseRepository);
    }

    @Test
    @DisplayName("Avaliação legível mas não pública (seguidor em FOLLOWERS): 409 REVIEW_NOT_RESPONDABLE")
    void readableButNotPublic() {
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review(ReviewStatus.ACTIVE, "FOLLOWERS")));
        when(userFollowRepository.isFollowing(actor, reviewer)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () -> publish.execute(command(placeId, "x"), NOW));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("REVIEW_NOT_RESPONDABLE", ex.getErrorCode());
        verifyNoInteractions(placeRepository, businessResponseRepository);
    }

    @Test
    @DisplayName("Resposta ativa já existente: 409 pela checagem prévia, sem gravar")
    void duplicateByPrecheck() {
        BusinessResponse existing = BusinessResponse.publish(reviewId, null, accountId, placeId, actor, "Anterior", NOW);
        when(businessResponseRepository.findActive(reviewId, null, accountId)).thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class, () -> publish.execute(command(placeId, "x"), NOW));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("BUSINESS_RESPONSE_ALREADY_EXISTS", ex.getErrorCode());
        verify(businessResponseRepository, never()).save(any());
    }

    @Test
    @DisplayName("Corrida: violação do índice de resposta ativa vira 409; outra violação de integridade não é mascarada")
    void concurrentDuplicateTranslatedOnlyForUniqueIndex() {
        doThrow(integrityViolation(
                "duplicate key value violates unique constraint \"uq_business_responses_review_active\""))
                .when(businessResponseRepository).save(any());
        assertEquals("BUSINESS_RESPONSE_ALREADY_EXISTS", assertThrows(BusinessException.class,
                () -> publish.execute(command(placeId, "x"), NOW)).getErrorCode());

        DataIntegrityViolationException other = integrityViolation(
                "insert or update on table \"business_responses\" violates foreign key constraint \"business_responses_place_id_fkey\"");
        doThrow(other).when(businessResponseRepository).save(any());
        assertSame(other, assertThrows(DataIntegrityViolationException.class,
                () -> publish.execute(command(placeId, "x"), NOW)));
    }

    @Test
    @DisplayName("Limite de criação excedido: 429 antes de ler a avaliação")
    void rateLimited() {
        when(rateLimiter.acquireOrThrow(eq(RateLimitedAction.BUSINESS_RESPONSE_CREATION), any()))
                .thenThrow(new BusinessException("Limite", HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(BusinessException.class,
                () -> publish.execute(command(placeId, "x"), NOW)).getStatus());
        verifyNoInteractions(reviewRepository, placeRepository, businessResponseRepository);
    }

    // ---------------------------------------------------------------- listagem

    @Test
    @DisplayName("Listagem: respeita a leitura da avaliação e não expõe autor pessoal, documento nem remoção")
    void listRespectsVisibility() {
        PageResult<BusinessResponseView> page = PageResult.of(List.of(new BusinessResponseView(UUID.randomUUID(), reviewId,
                null, accountId, "Padaria Central LTDA", placeId, "Centro", "Obrigado", NOW)), 0, 20, 1);
        when(businessResponseRepository.findActiveReviewResponseViews(reviewId, 0, 20)).thenReturn(page);

        assertSame(page, list.execute(reviewer, reviewId, 0, 20));

        List<String> components = Arrays.stream(BusinessResponseView.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();
        assertEquals(List.of("id", "reviewId", "discussionId", "businessAccountId", "corporateName", "placeId", "placeName", "content",
                "createdAt"), components);
    }

    @Test
    @DisplayName("Listagem de avaliação privada de terceiro, em quarentena ou removida não chega às respostas")
    void listHidesUnreadableReviews() {
        UUID stranger = UUID.randomUUID();
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review(ReviewStatus.ACTIVE, "PRIVATE")));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> list.execute(stranger, reviewId, 0, 20)).getStatus());

        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review(ReviewStatus.ACTIVE, "FOLLOWERS")));
        when(userFollowRepository.isFollowing(stranger, reviewer)).thenReturn(false);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> list.execute(stranger, reviewId, 0, 20)).getStatus());

        for (ReviewStatus status : new ReviewStatus[] {ReviewStatus.UNDER_REVIEW, ReviewStatus.REMOVED}) {
            when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review(status, "PUBLIC")));
            assertEquals("REVIEW_NOT_FOUND", assertThrows(BusinessException.class,
                    () -> list.execute(reviewer, reviewId, 0, 20)).getErrorCode());
        }
        verify(businessResponseRepository, never()).findActiveReviewResponseViews(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Listagem: ator e paginação validados")
    void listValidation() {
        assertEquals("UNAUTHORIZED", assertThrows(BusinessException.class,
                () -> list.execute(null, reviewId, 0, 20)).getErrorCode());
        assertEquals("INVALID_PAGE", assertThrows(BusinessException.class,
                () -> list.execute(reviewer, reviewId, -1, 20)).getErrorCode());
        assertEquals("INVALID_PAGE_SIZE", assertThrows(BusinessException.class,
                () -> list.execute(reviewer, reviewId, 0, 51)).getErrorCode());
        verifyNoInteractions(reviewRepository, businessResponseRepository);
    }

    // ---------------------------------------------------------------- elegibilidade

    @Test
    @DisplayName("Elegibilidade: só contas aprovadas do ator, com os locais que cada uma controla agora na avaliação")
    void eligibilityListsAccountsAndPlaces() {
        UUID secondAccountId = UUID.randomUUID();
        UUID pendingAccountId = UUID.randomUUID();
        BusinessAccount second = account(secondAccountId, actor, VerificationStatus.APPROVED, "Café Norte ME");
        BusinessAccount pending = account(pendingAccountId, actor, VerificationStatus.PENDING, "Pendente ME");
        UUID branch = UUID.randomUUID();
        UUID inactive = UUID.randomUUID();
        UUID northActive = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        UUID pendingPlace = UUID.randomUUID();
        when(businessAccountRepository.findByUserId(actor)).thenReturn(List.of(account, second, pending));
        when(reviewRepository.findAssociatedPlaceIds(reviewId))
                .thenReturn(Set.of(placeId, branch, inactive, northActive, foreign, pendingPlace));
        when(placeRepository.findById(placeId)).thenReturn(Optional.of(place));
        when(placeRepository.findById(branch)).thenReturn(Optional.of(place(branch, "Padaria Central - Batel", accountId, "ACTIVE")));
        when(placeRepository.findById(inactive)).thenReturn(Optional.of(place(inactive, "Café Norte - Antigo", secondAccountId, "INACTIVE")));
        when(placeRepository.findById(northActive)).thenReturn(Optional.of(place(northActive, "Café Norte", secondAccountId, "ACTIVE")));
        when(placeRepository.findById(foreign)).thenReturn(Optional.of(place(foreign, "Terceiro", UUID.randomUUID(), "ACTIVE")));
        when(placeRepository.findById(pendingPlace)).thenReturn(Optional.of(place(pendingPlace, "Pendente", pendingAccountId, "ACTIVE")));
        BusinessResponse existing = BusinessResponse.publish(reviewId, null, accountId, placeId, actor, "Anterior", NOW);
        when(businessResponseRepository.findActive(reviewId, null, accountId)).thenReturn(Optional.of(existing));

        BusinessResponseEligibilityView view = eligibility.execute(actor, reviewId);

        assertTrue(view.respondable());
        assertEquals(2, view.accounts().size());
        EligibleBusinessAccount central = view.accounts().get(0);
        assertEquals(accountId, central.businessAccountId());
        assertEquals("Padaria Central LTDA", central.corporateName());
        assertEquals(List.of(new EligiblePlace(branch, "Padaria Central - Batel"),
                new EligiblePlace(placeId, "Padaria Central - Centro")), central.places());
        assertEquals(existing.getId(), central.activeResponseId());
        assertFalse(central.canPublish());
        EligibleBusinessAccount north = view.accounts().get(1);
        assertEquals(secondAccountId, north.businessAccountId());
        assertEquals(List.of(new EligiblePlace(northActive, "Café Norte")), north.places());
        assertNull(north.activeResponseId());
        assertTrue(north.canPublish());

        assertFalse(Arrays.stream(EligibleBusinessAccount.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("taxId")));
        verifyNoInteractions(rateLimiter);
        verify(businessResponseRepository, never()).save(any());
        verify(placeRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("Elegibilidade: avaliação legível e não pública responde respondable=false sem consultar contas")
    void eligibilityForNonPublicReview() {
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review(ReviewStatus.ACTIVE, "FOLLOWERS")));
        when(userFollowRepository.isFollowing(actor, reviewer)).thenReturn(true);

        BusinessResponseEligibilityView view = eligibility.execute(actor, reviewId);

        assertFalse(view.respondable());
        assertTrue(view.accounts().isEmpty());
        verifyNoInteractions(businessAccountRepository, placeRepository, businessResponseRepository);
    }

    @Test
    @DisplayName("Elegibilidade: sem conta aprovada, lista vazia; avaliação ilegível segue a regra de leitura")
    void eligibilityWithoutApprovedAccounts() {
        when(businessAccountRepository.findByUserId(actor))
                .thenReturn(List.of(account(accountId, actor, VerificationStatus.PENDING, "Pendente")));
        assertTrue(eligibility.execute(actor, reviewId).accounts().isEmpty());
        verifyNoInteractions(placeRepository);

        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review(ReviewStatus.REMOVED, "PUBLIC")));
        assertEquals("REVIEW_NOT_FOUND", assertThrows(BusinessException.class,
                () -> eligibility.execute(actor, reviewId)).getErrorCode());
    }

    // ---------------------------------------------------------------- remoção

    @Test
    @DisplayName("Administrador remove a resposta da conta: REMOVED com origem BUSINESS, alvo e autoria intactos")
    void removeOwnResponse() {
        BusinessResponse response = stored(BusinessResponse.publish(reviewId, null, accountId, placeId, actor, "Resposta", NOW));

        remove.execute(actor, response.getId(), NOW.plusSeconds(30));

        ArgumentCaptor<BusinessResponse> saved = ArgumentCaptor.forClass(BusinessResponse.class);
        verify(businessResponseRepository).save(saved.capture());
        BusinessResponse removed = saved.getValue();
        assertEquals(BusinessResponseStatus.REMOVED, removed.getStatus());
        assertEquals(BusinessResponseRemovalSource.BUSINESS, removed.getRemovedBy());
        assertEquals(NOW.plusSeconds(30), removed.getRemovedAt());
        assertEquals(reviewId, removed.getReviewId());
        assertEquals(accountId, removed.getBusinessAccountId());
        assertEquals(placeId, removed.getPlaceId());
        assertEquals(actor, removed.getAuthorUserId());
    }

    @Test
    @DisplayName("Administrador de outra conta: 403 sem travar nem gravar")
    void removeByOtherAdministrator() {
        UUID other = UUID.randomUUID();
        when(userRepository.findById(other)).thenReturn(Optional.of(user(other, Role.MODERATOR)));
        BusinessResponse response = stored(BusinessResponse.publish(reviewId, null, accountId, placeId, actor, "Resposta", NOW));

        BusinessException ex = assertThrows(BusinessException.class, () -> remove.execute(other, response.getId(), NOW));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("BUSINESS_NOT_RESPONSIBLE", ex.getErrorCode());
        verify(businessResponseRepository, never()).findByIdForUpdate(any());
        verify(businessResponseRepository, never()).save(any());
    }

    @Test
    @DisplayName("Remoção depois de perder o vínculo com o local e sem conta aprovada: permitida, sem consultar o local")
    void removeAfterLosingPlaceLink() {
        when(businessAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account(accountId, actor, VerificationStatus.REJECTED, "Padaria Central LTDA")));
        BusinessResponse response = stored(BusinessResponse.publish(reviewId, null, accountId, placeId, actor, "Resposta", NOW));

        remove.execute(actor, response.getId(), NOW);

        verify(businessResponseRepository).save(any());
        verifyNoInteractions(placeRepository, reviewRepository);
    }

    @Test
    @DisplayName("Remoção repetida: 409 do domínio, sem gravar; resposta inexistente: 404")
    void removeTwiceOrMissing() {
        BusinessResponse response = BusinessResponse.publish(reviewId, null, accountId, placeId, actor, "Resposta", NOW);
        response.removeByBusiness(NOW);
        stored(response);

        assertEquals("BUSINESS_RESPONSE_ALREADY_REMOVED", assertThrows(BusinessException.class,
                () -> remove.execute(actor, response.getId(), NOW)).getErrorCode());
        verify(businessResponseRepository, never()).save(any());

        UUID missing = UUID.randomUUID();
        when(businessResponseRepository.findById(missing)).thenReturn(Optional.empty());
        assertEquals("BUSINESS_RESPONSE_NOT_FOUND", assertThrows(BusinessException.class,
                () -> remove.execute(actor, missing, NOW)).getErrorCode());
    }

    @Test
    @DisplayName("Remoção por ator inoperante: 401 antes de ler a resposta")
    void removeByInoperativeActor() {
        when(userRepository.findById(actor)).thenReturn(Optional.of(User.rehydrate(actor, "ator@rewit.test", "hash",
                AuthProvider.LOCAL, null, AccountStatus.DEACTIVATED, true, Role.USER, null, NOW, NOW)));

        assertEquals("ACCOUNT_DISABLED", assertThrows(BusinessException.class,
                () -> remove.execute(actor, UUID.randomUUID(), NOW)).getErrorCode());
        verifyNoInteractions(businessResponseRepository, businessAccountRepository);
    }

    // ---------------------------------------------------------------- apoio

    private PublishReviewBusinessResponseCommand command(UUID chosenPlace, String content) {
        return new PublishReviewBusinessResponseCommand(actor, accountId, reviewId, chosenPlace, content);
    }

    /** A resposta como o repositório a devolve: cópia independente, como a releitura com lock. */
    private BusinessResponse stored(BusinessResponse response) {
        when(businessResponseRepository.findById(response.getId())).thenReturn(Optional.of(copy(response)));
        lenient().when(businessResponseRepository.findByIdForUpdate(response.getId())).thenReturn(Optional.of(copy(response)));
        return response;
    }

    private static BusinessResponse copy(BusinessResponse r) {
        return new BusinessResponse(r.getId(), r.getReviewId(), r.getDiscussionId(), r.getBusinessAccountId(),
                r.getPlaceId(), r.getAuthorUserId(), r.getContent(), r.getStatus(), r.getRemovedBy(), r.getRemovedAt(),
                r.getCreatedAt(), r.getUpdatedAt());
    }

    private Review review(ReviewStatus status, String visibility) {
        return new Review(reviewId, reviewer, null, "Ótimo café", false, false, status, visibility, null, null, null,
                NOW, NOW);
    }

    private static User user(UUID id, Role role) {
        return User.rehydrate(id, "ator@rewit.test", "hash", AuthProvider.LOCAL, null, AccountStatus.ACTIVE, true, role,
                null, NOW, NOW);
    }

    private static BusinessAccount account(UUID id, UUID adminUserId, VerificationStatus status, String name) {
        return new BusinessAccount(id, adminUserId, name, "12345678000190", status, "FREE", NOW, NOW);
    }

    private static Place place(UUID id, String name, UUID claimedBy, String status) {
        return new Place(id, name, "slug-" + id, "CAFE", null, "Rua A", "Curitiba", "PR", "BR", -25.42, -49.27, 50,
                "USER", false, claimedBy, status);
    }

    private static DataIntegrityViolationException integrityViolation(String message) {
        return new DataIntegrityViolationException("could not execute statement", new SQLException(message));
    }
}
