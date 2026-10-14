package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseModerationResult;
import com.rewit.application.dto.business.BusinessResponseDtos.ModerateBusinessResponseCommand;
import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.application.port.BusinessResponseModerationAuditLogRepository;
import com.rewit.application.port.BusinessResponseRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.AccountStatusPolicy;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.application.service.ModeratorRolePolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AccountStatus;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.BusinessResponseModerationAction;
import com.rewit.domain.enums.BusinessResponseRemovalSource;
import com.rewit.domain.enums.BusinessResponseStatus;
import com.rewit.domain.enums.Role;
import com.rewit.domain.enums.VerificationStatus;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.BusinessResponse;
import com.rewit.domain.model.BusinessResponseModerationAuditLog;
import com.rewit.domain.model.Place;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ModerateBusinessResponseUseCase (C11): moderação administrativa de respostas oficiais")
class ModerateBusinessResponseUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(600);
    private static final String JUSTIFICATION = "Conteúdo promocional fora das regras";

    @Mock
    private UserRepository userRepository;
    @Mock
    private BusinessResponseRepository businessResponseRepository;
    @Mock
    private BusinessResponseModerationAuditLogRepository auditLogRepository;
    @Mock
    private BusinessAccountRepository businessAccountRepository;
    @Mock
    private PlaceRepository placeRepository;

    private ModerateBusinessResponseUseCase useCase;

    private final UUID moderator = UUID.randomUUID();
    private final UUID businessAdmin = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID reviewId = UUID.randomUUID();
    private final UUID placeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AccountStatusPolicy accountStatusPolicy = new AccountStatusPolicy(userRepository);
        useCase = new ModerateBusinessResponseUseCase(businessResponseRepository, auditLogRepository,
                businessAccountRepository, new ModeratorRolePolicy(accountStatusPolicy, userRepository),
                new BusinessRepresentationPolicy(accountStatusPolicy, businessAccountRepository, placeRepository));
        CurrentRoleStubs.defaultRole(userRepository, moderator, Role.MODERATOR);
        lenient().when(businessAccountRepository.findById(accountId)).thenReturn(Optional.of(account(VerificationStatus.APPROVED)));
        // Representação elegível por padrão: administrador operacional e local ativo vinculado à conta
        lenient().when(userRepository.findById(businessAdmin)).thenReturn(Optional.of(administrator(AccountStatus.ACTIVE)));
        lenient().when(placeRepository.findById(placeId)).thenReturn(Optional.of(place(accountId, "ACTIVE")));
        lenient().when(businessResponseRepository.findActive(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(businessResponseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(auditLogRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("MODERATOR atual remove resposta ativa: origem MODERATION, histórico e auditoria da transição")
    void moderatorRemoves() {
        BusinessResponse response = stored(active(null));

        BusinessResponseModerationResult result = useCase.execute(command(response.getId(),
                BusinessResponseModerationAction.REMOVE_RESPONSE));

        ArgumentCaptor<BusinessResponse> saved = ArgumentCaptor.forClass(BusinessResponse.class);
        verify(businessResponseRepository).save(saved.capture());
        assertEquals(BusinessResponseStatus.REMOVED, saved.getValue().getStatus());
        assertEquals(BusinessResponseRemovalSource.MODERATION, saved.getValue().getRemovedBy());
        assertEquals(LATER, saved.getValue().getRemovedAt());
        assertEquals("Resposta", saved.getValue().getContent());

        ArgumentCaptor<BusinessResponseModerationAuditLog> audit = ArgumentCaptor.forClass(BusinessResponseModerationAuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        BusinessResponseModerationAuditLog log = audit.getValue();
        assertEquals(response.getId(), log.getResponseId());
        assertEquals(moderator, log.getModeratorUserId());
        assertEquals(BusinessResponseModerationAction.REMOVE_RESPONSE, log.getAction());
        assertEquals("SPAM", log.getReasonCode());
        assertEquals(JUSTIFICATION, log.getJustification());
        assertEquals(BusinessResponseStatus.ACTIVE, log.getPreviousStatus());
        assertEquals(BusinessResponseStatus.REMOVED, log.getNewStatus());
        assertEquals(LATER, log.getCreatedAt());

        assertEquals(BusinessResponseStatus.REMOVED, result.newStatus());
        assertEquals(BusinessResponseRemovalSource.MODERATION, result.removedBy());
        assertEquals(log.getId(), result.auditLogId());
        assertFalse(Arrays.stream(BusinessResponseModerationResult.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("authorUserId") || component.getName().equals("taxId")));
    }

    @Test
    @DisplayName("ADMIN atual remove e restaura uma resposta removida pela moderação")
    void adminRemovesAndRestores() {
        CurrentRoleStubs.currentRole(userRepository, moderator, Role.ADMIN);
        BusinessResponse response = active(null);
        response.removeByModeration(NOW);
        stored(response);

        BusinessResponseModerationResult result = useCase.execute(command(response.getId(),
                BusinessResponseModerationAction.RESTORE_RESPONSE));

        assertEquals(BusinessResponseStatus.REMOVED, result.previousStatus());
        assertEquals(BusinessResponseStatus.ACTIVE, result.newStatus());
        assertNull(result.removedBy());
        verify(businessResponseRepository).findActive(reviewId, null, accountId);

        BusinessResponse other = stored(active(null));
        assertEquals(BusinessResponseStatus.REMOVED, useCase.execute(command(other.getId(),
                BusinessResponseModerationAction.REMOVE_RESPONSE)).newStatus());
    }

    @Test
    @DisplayName("Ator USER: 403 FORBIDDEN antes de consultar a resposta")
    void userForbidden() {
        CurrentRoleStubs.currentRole(userRepository, moderator, Role.USER);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> useCase.execute(command(UUID.randomUUID(), BusinessResponseModerationAction.REMOVE_RESPONSE)));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("FORBIDDEN", ex.getErrorCode());
        verifyNoInteractions(businessResponseRepository, businessAccountRepository, auditLogRepository);
    }

    @Test
    @DisplayName("Moderador inoperante: 401 ACCOUNT_DISABLED antes de consultar a resposta")
    void inoperativeModerator() {
        when(userRepository.findById(moderator)).thenReturn(Optional.of(User.rehydrate(moderator, "mod@rewit.test", "hash",
                AuthProvider.LOCAL, null, AccountStatus.SUSPENDED, true, Role.MODERATOR, null, NOW, NOW)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> useCase.execute(command(UUID.randomUUID(), BusinessResponseModerationAction.REMOVE_RESPONSE)));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
        verifyNoInteractions(businessResponseRepository, auditLogRepository);
    }

    @Test
    @DisplayName("Ordem: cargo atual, lock da resposta, gravação da resposta e só então a auditoria")
    void order() {
        BusinessResponse response = stored(active(null));

        useCase.execute(command(response.getId(), BusinessResponseModerationAction.REMOVE_RESPONSE));

        InOrder order = inOrder(userRepository, businessResponseRepository, auditLogRepository);
        order.verify(userRepository, atLeastOnce()).findById(moderator);
        order.verify(businessResponseRepository).findByIdForUpdate(response.getId());
        order.verify(businessResponseRepository).save(any());
        order.verify(auditLogRepository).save(any());
    }

    @Test
    @DisplayName("Resposta inexistente: 404 BUSINESS_RESPONSE_NOT_FOUND, sem auditoria")
    void missingResponse() {
        UUID missing = UUID.randomUUID();
        when(businessResponseRepository.findByIdForUpdate(missing)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> useCase.execute(command(missing, BusinessResponseModerationAction.REMOVE_RESPONSE)));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("BUSINESS_RESPONSE_NOT_FOUND", ex.getErrorCode());
        verifyNoInteractions(auditLogRepository);
    }

    @Test
    @DisplayName("Remoção repetida (pela empresa ou pela moderação): 409 do domínio, sem gravar nem auditar")
    void removeAlreadyRemoved() {
        for (boolean byBusiness : new boolean[] {true, false}) {
            BusinessResponse response = active(null);
            if (byBusiness) {
                response.removeByBusiness(NOW);
            } else {
                response.removeByModeration(NOW);
            }
            stored(response);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> useCase.execute(command(response.getId(), BusinessResponseModerationAction.REMOVE_RESPONSE)));
            assertEquals("BUSINESS_RESPONSE_ALREADY_REMOVED", ex.getErrorCode());
        }
        verify(businessResponseRepository, never()).save(any());
        verifyNoInteractions(auditLogRepository);
    }

    @Test
    @DisplayName("Resposta removida pela empresa ou ativa não é restaurável: 409 sem gravar nem auditar")
    void restoreNotAllowed() {
        BusinessResponse withdrawn = active(null);
        withdrawn.removeByBusiness(NOW);
        stored(withdrawn);
        BusinessResponse active = stored(active(null));

        for (UUID id : new UUID[] {withdrawn.getId(), active.getId()}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> useCase.execute(command(id, BusinessResponseModerationAction.RESTORE_RESPONSE)));
            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertEquals("BUSINESS_RESPONSE_NOT_RESTORABLE", ex.getErrorCode());
        }
        verify(businessResponseRepository, never()).save(any());
        verifyNoInteractions(auditLogRepository);
    }

    @Test
    @DisplayName("Restaurar com outra resposta ativa da empresa no alvo: 409 pela checagem prévia, sem gravar nem auditar")
    void restoreConflictByPrecheck() {
        UUID discussionId = UUID.randomUUID();
        BusinessResponse removed = active(discussionId);
        removed.removeByModeration(NOW);
        stored(removed);
        when(businessResponseRepository.findActive(reviewId, discussionId, accountId)).thenReturn(Optional.of(
                BusinessResponse.publish(reviewId, discussionId, accountId, placeId, businessAdmin, "Nova", NOW)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> useCase.execute(command(removed.getId(), BusinessResponseModerationAction.RESTORE_RESPONSE)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("BUSINESS_RESPONSE_ALREADY_EXISTS", ex.getErrorCode());
        verify(businessResponseRepository, never()).save(any());
        verifyNoInteractions(auditLogRepository);
    }

    @Test
    @DisplayName("Restaurar com representação inelegível: 409 NOT_RESTORABLE por motivo, sem gravar, auditar nem travar o local")
    void restoreBlockedByIneligibleRepresentation() {
        record Scenario(String name, Runnable arrange, String expectedDetail) {
        }
        UUID otherBusiness = UUID.randomUUID();
        List<Scenario> scenarios = List.of(
                new Scenario("conta PENDING", () -> when(businessAccountRepository.findById(accountId))
                        .thenReturn(Optional.of(account(VerificationStatus.PENDING))), "a conta comercial não está aprovada"),
                new Scenario("conta REJECTED", () -> when(businessAccountRepository.findById(accountId))
                        .thenReturn(Optional.of(account(VerificationStatus.REJECTED))), "a conta comercial não está aprovada"),
                new Scenario("administrador SUSPENDED", () -> when(userRepository.findById(businessAdmin))
                        .thenReturn(Optional.of(administrator(AccountStatus.SUSPENDED))),
                        "o administrador da conta comercial não está operacional"),
                new Scenario("administrador DEACTIVATED", () -> when(userRepository.findById(businessAdmin))
                        .thenReturn(Optional.of(administrator(AccountStatus.DEACTIVATED))),
                        "o administrador da conta comercial não está operacional"),
                new Scenario("administrador inexistente", () -> when(userRepository.findById(businessAdmin))
                        .thenReturn(Optional.empty()), "o administrador da conta comercial não está operacional"),
                new Scenario("local CLOSED", () -> when(placeRepository.findById(placeId))
                        .thenReturn(Optional.of(place(accountId, "CLOSED"))),
                        "o local não está ativo ou não está vinculado à conta comercial"),
                new Scenario("local de outra empresa", () -> when(placeRepository.findById(placeId))
                        .thenReturn(Optional.of(place(otherBusiness, "ACTIVE"))),
                        "o local não está ativo ou não está vinculado à conta comercial"),
                new Scenario("local sem vínculo", () -> when(placeRepository.findById(placeId))
                        .thenReturn(Optional.of(place(null, "ACTIVE"))),
                        "o local não está ativo ou não está vinculado à conta comercial"));

        for (Scenario scenario : scenarios) {
            setUp();
            scenario.arrange().run();
            BusinessResponse removed = active(null);
            removed.removeByModeration(NOW);
            stored(removed);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> useCase.execute(command(removed.getId(), BusinessResponseModerationAction.RESTORE_RESPONSE)),
                    scenario.name());
            assertEquals(HttpStatus.CONFLICT, ex.getStatus(), scenario.name());
            assertEquals("BUSINESS_RESPONSE_NOT_RESTORABLE", ex.getErrorCode(), scenario.name());
            assertTrue(ex.getMessage().contains(scenario.expectedDetail()), scenario.name() + ": " + ex.getMessage());
            assertFalse(ex.getMessage().contains("SUSPENDED") || ex.getMessage().contains("suspens"), scenario.name());
        }
        verify(businessResponseRepository, never()).findActive(any(), any(), any());
        verify(businessResponseRepository, never()).save(any());
        verify(placeRepository, never()).findByIdForUpdate(any());
        verifyNoInteractions(auditLogRepository);
    }

    @Test
    @DisplayName("Restaurar com vários motivos: um único 409 que lista todos, na ordem dos critérios")
    void restoreBlockedByCombinedReasons() {
        when(businessAccountRepository.findById(accountId)).thenReturn(Optional.of(account(VerificationStatus.PENDING)));
        when(userRepository.findById(businessAdmin)).thenReturn(Optional.of(administrator(AccountStatus.SUSPENDED)));
        when(placeRepository.findById(placeId)).thenReturn(Optional.of(place(accountId, "INACTIVE")));
        BusinessResponse removed = active(null);
        removed.removeByModeration(NOW);
        stored(removed);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> useCase.execute(command(removed.getId(), BusinessResponseModerationAction.RESTORE_RESPONSE)));

        assertEquals("BUSINESS_RESPONSE_NOT_RESTORABLE", ex.getErrorCode());
        assertEquals("A representação comercial da resposta não está elegível (a conta comercial não está aprovada; "
                + "o administrador da conta comercial não está operacional; o local não está ativo ou não está vinculado "
                + "à conta comercial); a resposta removida não pode ser restaurada", ex.getMessage());
        verify(businessResponseRepository, never()).save(any());
        verifyNoInteractions(auditLogRepository);
    }

    @Test
    @DisplayName("Ordem na restauração: estado do domínio, depois elegibilidade sem lock, depois unicidade")
    void restoreChecksStateThenEligibilityThenUniqueness() {
        // Removida pela empresa e com representação inelegível: vale o erro do domínio, sem avaliar a representação
        lenient().when(placeRepository.findById(placeId)).thenReturn(Optional.of(place(accountId, "CLOSED")));
        BusinessResponse withdrawn = active(null);
        withdrawn.removeByBusiness(NOW);
        stored(withdrawn);
        BusinessException domain = assertThrows(BusinessException.class,
                () -> useCase.execute(command(withdrawn.getId(), BusinessResponseModerationAction.RESTORE_RESPONSE)));
        assertEquals("Só uma resposta removida pela moderação pode ser restaurada", domain.getMessage());
        verifyNoInteractions(placeRepository);

        // Elegível: a representação é lida sem lock antes da checagem de unicidade
        when(placeRepository.findById(placeId)).thenReturn(Optional.of(place(accountId, "ACTIVE")));
        BusinessResponse removed = active(null);
        removed.removeByModeration(NOW);
        stored(removed);
        useCase.execute(command(removed.getId(), BusinessResponseModerationAction.RESTORE_RESPONSE));

        InOrder order = inOrder(businessResponseRepository, placeRepository, auditLogRepository);
        order.verify(businessResponseRepository).findByIdForUpdate(removed.getId());
        order.verify(placeRepository).findById(placeId);
        order.verify(businessResponseRepository).findActive(reviewId, null, accountId);
        order.verify(businessResponseRepository).save(any());
        order.verify(auditLogRepository).save(any());
        verify(placeRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("Remover não depende da elegibilidade: a moderação retira resposta de representação inelegível")
    void removeIgnoresEligibility() {
        when(businessAccountRepository.findById(accountId)).thenReturn(Optional.of(account(VerificationStatus.PENDING)));
        BusinessResponse response = stored(active(null));

        BusinessResponseModerationResult result = useCase.execute(command(response.getId(),
                BusinessResponseModerationAction.REMOVE_RESPONSE));

        assertEquals(BusinessResponseStatus.REMOVED, result.newStatus());
        verifyNoInteractions(placeRepository);
    }

    @Test
    @DisplayName("Corrida na restauração: só os índices de resposta ativa viram 409; outra violação é relançada; sem auditoria")
    void restoreConflictByIndex() {
        for (String index : new String[] {"uq_business_responses_review_active", "uq_business_responses_discussion_active"}) {
            BusinessResponse removed = active(null);
            removed.removeByModeration(NOW);
            stored(removed);
            doThrow(integrityViolation("duplicate key value violates unique constraint \"" + index + "\""))
                    .when(businessResponseRepository).save(any());

            assertEquals("BUSINESS_RESPONSE_ALREADY_EXISTS", assertThrows(BusinessException.class,
                    () -> useCase.execute(command(removed.getId(), BusinessResponseModerationAction.RESTORE_RESPONSE)))
                    .getErrorCode());
        }

        BusinessResponse removed = active(null);
        removed.removeByModeration(NOW);
        stored(removed);
        DataIntegrityViolationException other = integrityViolation("violates check constraint \"chk_business_responses_removal\"");
        doThrow(other).when(businessResponseRepository).save(any());
        assertSame(other, assertThrows(DataIntegrityViolationException.class,
                () -> useCase.execute(command(removed.getId(), BusinessResponseModerationAction.RESTORE_RESPONSE))));

        verifyNoInteractions(auditLogRepository);
    }

    @Test
    @DisplayName("Conflito de interesse: administrador da conta autora ou autor da resposta não a modera")
    void selfModerationForbidden() {
        BusinessResponse response = stored(active(null));
        CurrentRoleStubs.currentRole(userRepository, businessAdmin, Role.MODERATOR);
        BusinessException ex = assertThrows(BusinessException.class, () -> useCase.execute(new ModerateBusinessResponseCommand(
                response.getId(), businessAdmin, BusinessResponseModerationAction.REMOVE_RESPONSE, "SPAM", JUSTIFICATION, LATER)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("SELF_MODERATION_FORBIDDEN", ex.getErrorCode());

        // Autor pessoal que não administra mais a conta
        BusinessResponse byModerator = stored(BusinessResponse.publish(reviewId, null, accountId, placeId, moderator,
                "Resposta", NOW));
        assertEquals("SELF_MODERATION_FORBIDDEN", assertThrows(BusinessException.class,
                () -> useCase.execute(command(byModerator.getId(), BusinessResponseModerationAction.REMOVE_RESPONSE)))
                .getErrorCode());

        verify(businessResponseRepository, never()).save(any());
        verifyNoInteractions(auditLogRepository);
    }

    @Test
    @DisplayName("Comando inválido: ator ausente 401; motivo e justificativa validados antes do cargo")
    void validation() {
        UUID id = UUID.randomUUID();
        assertEquals("UNAUTHORIZED", assertThrows(BusinessException.class, () -> useCase.execute(new ModerateBusinessResponseCommand(
                id, null, BusinessResponseModerationAction.REMOVE_RESPONSE, "SPAM", JUSTIFICATION, LATER))).getErrorCode());
        assertEquals("MISSING_MODERATION_ACTION", assertThrows(BusinessException.class, () -> useCase.execute(
                new ModerateBusinessResponseCommand(id, moderator, null, "SPAM", JUSTIFICATION, LATER))).getErrorCode());
        assertEquals("INVALID_REASON_CODE_LENGTH", assertThrows(BusinessException.class, () -> useCase.execute(
                new ModerateBusinessResponseCommand(id, moderator, BusinessResponseModerationAction.REMOVE_RESPONSE,
                        "X".repeat(65), JUSTIFICATION, LATER))).getErrorCode());
        assertEquals("INVALID_JUSTIFICATION_LENGTH", assertThrows(BusinessException.class, () -> useCase.execute(
                new ModerateBusinessResponseCommand(id, moderator, BusinessResponseModerationAction.REMOVE_RESPONSE,
                        "SPAM", "curta", LATER))).getErrorCode());
        verifyNoInteractions(userRepository, businessResponseRepository, auditLogRepository);
    }

    // ---------------------------------------------------------------- apoio

    private ModerateBusinessResponseCommand command(UUID responseId, BusinessResponseModerationAction action) {
        return new ModerateBusinessResponseCommand(responseId, moderator, action, " SPAM ", JUSTIFICATION, LATER);
    }

    private BusinessAccount account(VerificationStatus status) {
        return new BusinessAccount(accountId, businessAdmin, "Padaria Central LTDA", "12345678000190", status, "FREE",
                NOW, NOW);
    }

    private User administrator(AccountStatus status) {
        return User.rehydrate(businessAdmin, "adm@rewit.test", "hash", AuthProvider.LOCAL, null, status, true,
                Role.USER, null, NOW, NOW);
    }

    private Place place(UUID claimedBy, String status) {
        return new Place(placeId, "Unidade Centro", "slug-" + placeId, "CAFE", null, "Rua A", "Curitiba", "PR", "BR",
                -25.42, -49.27, 50, "USER", false, claimedBy, status);
    }

    private BusinessResponse active(UUID discussionId) {
        return BusinessResponse.publish(reviewId, discussionId, accountId, placeId, businessAdmin, "Resposta", NOW);
    }

    /** O repositório devolve uma cópia, como a releitura com lock. */
    private BusinessResponse stored(BusinessResponse response) {
        lenient().when(businessResponseRepository.findByIdForUpdate(response.getId())).thenReturn(Optional.of(copy(response)));
        return response;
    }

    private static BusinessResponse copy(BusinessResponse r) {
        return new BusinessResponse(r.getId(), r.getReviewId(), r.getDiscussionId(), r.getBusinessAccountId(),
                r.getPlaceId(), r.getAuthorUserId(), r.getContent(), r.getStatus(), r.getRemovedBy(), r.getRemovedAt(),
                r.getCreatedAt(), r.getUpdatedAt());
    }

    private static DataIntegrityViolationException integrityViolation(String message) {
        return new DataIntegrityViolationException("could not execute statement", new SQLException(message));
    }
}
