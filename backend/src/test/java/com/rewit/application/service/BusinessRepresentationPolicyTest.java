package com.rewit.application.service;

import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AccountStatus;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.BusinessRepresentationIneligibility;
import com.rewit.domain.enums.Role;
import com.rewit.domain.enums.VerificationStatus;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceContactInfo;
import com.rewit.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BusinessRepresentationPolicy (C11): quem responde em nome de uma empresa, revalidado no banco")
class BusinessRepresentationPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private BusinessAccountRepository businessAccountRepository;

    @Mock
    private PlaceRepository placeRepository;

    private BusinessRepresentationPolicy policy;

    private final UUID actor = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        policy = new BusinessRepresentationPolicy(new AccountStatusPolicy(userRepository), businessAccountRepository,
                placeRepository);
        lenient().when(userRepository.findById(actor)).thenReturn(Optional.of(user(actor, AccountStatus.ACTIVE, Role.USER)));
    }

    static User user(UUID id, AccountStatus status, Role role) {
        return User.rehydrate(id, "ator@rewit.test", "hash", AuthProvider.LOCAL, null, status, true, role, null, NOW, NOW);
    }

    static BusinessAccount account(UUID id, UUID adminUserId, VerificationStatus status) {
        return new BusinessAccount(id, adminUserId, "Padaria Central LTDA", "12345678000190", status, "FREE", NOW, NOW);
    }

    static Place place(UUID id, String name, UUID claimedBy, String status) {
        return new Place(id, name, "slug-" + id, "CAFE", null, "Rua A", "Curitiba", "PR", "BR", -25.42, -49.27, 50,
                "USER", false, claimedBy, status);
    }

    @Test
    @DisplayName("Ator ausente: 401 UNAUTHORIZED sem tocar em nenhum repositório")
    void missingActor() {
        BusinessException ex = assertThrows(BusinessException.class, () -> policy.requireApprovedAccount(null, accountId));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        assertEquals("UNAUTHORIZED", ex.getErrorCode());
        verifyNoInteractions(userRepository, businessAccountRepository, placeRepository);
    }

    @Test
    @DisplayName("Conta pessoal inexistente ou inoperante: 401 ACCOUNT_DISABLED antes de consultar a conta comercial")
    void inoperativeActor() {
        UUID ghost = UUID.randomUUID();
        when(userRepository.findById(ghost)).thenReturn(Optional.empty());
        UUID suspended = UUID.randomUUID();
        when(userRepository.findById(suspended)).thenReturn(Optional.of(user(suspended, AccountStatus.SUSPENDED, Role.USER)));

        for (UUID candidate : new UUID[] {ghost, suspended}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> policy.requireAdministeredAccount(candidate, accountId));
            assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
            assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
        }
        verifyNoInteractions(businessAccountRepository, placeRepository);
    }

    @Test
    @DisplayName("Conta comercial inexistente e conta de outro usuário respondem o mesmo 404 (privacidade do C9)")
    void accountNotFoundOrNotAdministered() {
        UUID foreignId = UUID.randomUUID();
        when(businessAccountRepository.findById(accountId)).thenReturn(Optional.empty());
        when(businessAccountRepository.findById(foreignId))
                .thenReturn(Optional.of(account(foreignId, UUID.randomUUID(), VerificationStatus.APPROVED)));

        BusinessException missing = assertThrows(BusinessException.class,
                () -> policy.requireApprovedAccount(actor, accountId));
        BusinessException foreign = assertThrows(BusinessException.class,
                () -> policy.requireApprovedAccount(actor, foreignId));

        for (BusinessException ex : new BusinessException[] {missing, foreign}) {
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            assertEquals("BUSINESS_ACCOUNT_NOT_FOUND", ex.getErrorCode());
        }
        assertEquals(missing.getMessage(), foreign.getMessage());
        verifyNoInteractions(placeRepository);
    }

    @Test
    @DisplayName("Conta PENDING ou REJECTED não publica (409); administrar basta para operações sem nova manifestação")
    void approvalRequiredOnlyForNewManifestations() {
        for (VerificationStatus status : new VerificationStatus[] {VerificationStatus.PENDING, VerificationStatus.REJECTED}) {
            when(businessAccountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, actor, status)));

            BusinessException ex = assertThrows(BusinessException.class, () -> policy.requireApprovedAccount(actor, accountId));
            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertEquals("BUSINESS_ACCOUNT_NOT_APPROVED", ex.getErrorCode());

            assertEquals(status, policy.requireAdministeredAccount(actor, accountId).getVerificationStatus());
        }
    }

    @Test
    @DisplayName("Role de plataforma é irrelevante: USER e MODERATOR administradores passam do mesmo jeito")
    void platformRoleIsIrrelevant() {
        UUID moderator = UUID.randomUUID();
        UUID moderatorAccount = UUID.randomUUID();
        when(userRepository.findById(moderator)).thenReturn(Optional.of(user(moderator, AccountStatus.ACTIVE, Role.MODERATOR)));
        when(businessAccountRepository.findById(moderatorAccount))
                .thenReturn(Optional.of(account(moderatorAccount, moderator, VerificationStatus.APPROVED)));
        when(businessAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account(accountId, actor, VerificationStatus.APPROVED)));

        assertEquals(moderatorAccount, policy.requireApprovedAccount(moderator, moderatorAccount).getId());
        assertEquals(accountId, policy.requireApprovedAccount(actor, accountId).getId());

        // Ser moderador não dá acesso à conta de outra pessoa
        assertEquals("BUSINESS_ACCOUNT_NOT_FOUND", assertThrows(BusinessException.class,
                () -> policy.requireApprovedAccount(moderator, accountId)).getErrorCode());
    }

    @Test
    @DisplayName("Local fora da avaliação: 403 sem nem travar o local")
    void placeNotAssociatedWithReview() {
        BusinessAccount account = account(accountId, actor, VerificationStatus.APPROVED);
        UUID placeId = UUID.randomUUID();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> policy.lockRepresentedPlace(account, placeId, Set.of(UUID.randomUUID())));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("BUSINESS_NOT_RESPONSIBLE", ex.getErrorCode());
        verifyNoInteractions(placeRepository);
    }

    @Test
    @DisplayName("Local inexistente, inativo, sem vínculo ou de outra empresa: o mesmo 403, lido com o local travado")
    void placeNotRepresented() {
        BusinessAccount account = account(accountId, actor, VerificationStatus.APPROVED);
        UUID missing = UUID.randomUUID();
        UUID inactive = UUID.randomUUID();
        UUID unclaimed = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        when(placeRepository.findByIdForUpdate(missing)).thenReturn(Optional.empty());
        when(placeRepository.findByIdForUpdate(inactive)).thenReturn(Optional.of(place(inactive, "A", accountId, "INACTIVE")));
        when(placeRepository.findByIdForUpdate(unclaimed)).thenReturn(Optional.of(place(unclaimed, "B", null, "ACTIVE")));
        when(placeRepository.findByIdForUpdate(foreign))
                .thenReturn(Optional.of(place(foreign, "C", UUID.randomUUID(), "ACTIVE")));
        Set<UUID> reviewPlaces = Set.of(missing, inactive, unclaimed, foreign);

        for (UUID placeId : new UUID[] {missing, inactive, unclaimed, foreign}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> policy.lockRepresentedPlace(account, placeId, reviewPlaces));
            assertEquals("BUSINESS_NOT_RESPONSIBLE", ex.getErrorCode());
        }
        verify(placeRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Vários locais elegíveis: trava e devolve exatamente o local escolhido, sem escolher outro")
    void explicitPlaceAmongSeveral() {
        BusinessAccount account = account(accountId, actor, VerificationStatus.APPROVED);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Place chosen = place(second, "Filial", accountId, "ACTIVE");
        when(placeRepository.findByIdForUpdate(second)).thenReturn(Optional.of(chosen));

        assertSame(chosen, policy.lockRepresentedPlace(account, second, Set.of(first, second)));
        verify(placeRepository, never()).findByIdForUpdate(first);

        assertEquals("MISSING_PLACE_ID", assertThrows(BusinessException.class,
                () -> policy.lockRepresentedPlace(account, null, Set.of(first, second))).getErrorCode());
    }

    @Test
    @DisplayName("represents: local ACTIVE vinculado à conta, e nada além disso")
    void representsPredicate() {
        BusinessAccount account = account(accountId, actor, VerificationStatus.APPROVED);
        UUID id = UUID.randomUUID();

        assertTrue(BusinessRepresentationPolicy.represents(account, place(id, "A", accountId, "ACTIVE")));
        assertFalse(BusinessRepresentationPolicy.represents(account, place(id, "A", accountId, "INACTIVE")));
        assertFalse(BusinessRepresentationPolicy.represents(account, place(id, "A", null, "ACTIVE")));
        assertFalse(BusinessRepresentationPolicy.represents(account, place(id, "A", UUID.randomUUID(), "ACTIVE")));
        assertFalse(BusinessRepresentationPolicy.represents(account, null));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Elegibilidade da representação (decisão B): sem ator, para restauração e leitura pública
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("evaluate: cada critério falso é um motivo, os três são independentes e só o conjunto vazio é elegível")
    void evaluateCriteriaMatrix() {
        for (boolean approved : new boolean[] {true, false}) {
            for (boolean operational : new boolean[] {true, false}) {
                for (boolean represented : new boolean[] {true, false}) {
                    Set<BusinessRepresentationIneligibility> expected = EnumSet.noneOf(BusinessRepresentationIneligibility.class);
                    if (!approved) {
                        expected.add(BusinessRepresentationIneligibility.ACCOUNT_NOT_APPROVED);
                    }
                    if (!operational) {
                        expected.add(BusinessRepresentationIneligibility.ADMINISTRATOR_NOT_OPERATIONAL);
                    }
                    if (!represented) {
                        expected.add(BusinessRepresentationIneligibility.PLACE_NOT_REPRESENTED);
                    }
                    assertEquals(expected, BusinessRepresentationPolicy.evaluate(approved, operational, represented));
                }
            }
        }
    }

    @Test
    @DisplayName("evaluate com objetos: só APPROVED é aprovada; local segue represents; conta ausente não representa")
    void evaluateLoadedObjects() {
        UUID placeId = UUID.randomUUID();
        Place represented = place(placeId, "A", accountId, "ACTIVE");
        for (VerificationStatus status : VerificationStatus.values()) {
            BusinessAccount account = account(accountId, actor, status);
            boolean approved = status == VerificationStatus.APPROVED;
            assertEquals(approved, BusinessRepresentationPolicy.isApproved(account));
            assertEquals(approved, BusinessRepresentationPolicy.evaluate(account, true, represented).isEmpty());
        }
        BusinessAccount approved = account(accountId, actor, VerificationStatus.APPROVED);
        assertEquals(Set.of(BusinessRepresentationIneligibility.PLACE_NOT_REPRESENTED),
                BusinessRepresentationPolicy.evaluate(approved, true, place(placeId, "A", accountId, "CLOSED")));
        assertEquals(Set.of(BusinessRepresentationIneligibility.PLACE_NOT_REPRESENTED),
                BusinessRepresentationPolicy.evaluate(approved, true, place(placeId, "A", UUID.randomUUID(), "ACTIVE")));
        assertEquals(EnumSet.allOf(BusinessRepresentationIneligibility.class),
                BusinessRepresentationPolicy.evaluate(null, false, represented));
    }

    @Test
    @DisplayName("currentIneligibility: avalia o administrador atual da conta, não um ator, e lê o local sem lock")
    void currentIneligibilityUsesAccountAdministratorWithoutLocks() {
        UUID administrator = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        when(businessAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account(accountId, administrator, VerificationStatus.APPROVED)));
        when(placeRepository.findById(placeId)).thenReturn(Optional.of(place(placeId, "A", accountId, "ACTIVE")));
        User deleted = User.rehydrate(administrator, "adm@rewit.test", "hash", AuthProvider.LOCAL, null,
                AccountStatus.DELETED, true, Role.USER, NOW, NOW, NOW);

        when(userRepository.findById(administrator)).thenReturn(Optional.of(user(administrator, AccountStatus.ACTIVE, Role.USER)));
        assertTrue(policy.currentIneligibility(accountId, placeId).isEmpty());

        for (Optional<User> inoperative : List.of(
                Optional.of(user(administrator, AccountStatus.DEACTIVATED, Role.USER)),
                Optional.of(user(administrator, AccountStatus.SUSPENDED, Role.USER)),
                Optional.of(deleted),
                Optional.<User>empty())) {
            when(userRepository.findById(administrator)).thenReturn(inoperative);
            assertEquals(Set.of(BusinessRepresentationIneligibility.ADMINISTRATOR_NOT_OPERATIONAL),
                    policy.currentIneligibility(accountId, placeId));
        }

        verify(userRepository, never()).findById(actor);
        verify(placeRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("currentIneligibility: conta inexistente reúne os três motivos; local inexistente não é representado")
    void currentIneligibilityWithMissingEntities() {
        UUID placeId = UUID.randomUUID();
        when(businessAccountRepository.findById(accountId)).thenReturn(Optional.empty());
        when(placeRepository.findById(placeId)).thenReturn(Optional.of(place(placeId, "A", accountId, "ACTIVE")));

        assertEquals(EnumSet.allOf(BusinessRepresentationIneligibility.class),
                policy.currentIneligibility(accountId, placeId));
        verify(userRepository, never()).findById(any());

        UUID missingPlace = UUID.randomUUID();
        when(businessAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account(accountId, actor, VerificationStatus.APPROVED)));
        when(placeRepository.findById(missingPlace)).thenReturn(Optional.empty());
        assertEquals(Set.of(BusinessRepresentationIneligibility.PLACE_NOT_REPRESENTED),
                policy.currentIneligibility(accountId, missingPlace));
    }

    // ------------------------------------------------------------------------------------- contatos na leitura pública

    private static final PlaceContactInfo CONTACT = new PlaceContactInfo("+55 (41) 3333-4444",
            "https://www.exemplo.com.br", null, null);

    private static Place placeWithContact(UUID id, UUID claimedBy, String status, PlaceContactInfo contact) {
        return new Place(id, "Local", "slug-" + id, "CAFE", "Descrição", "Rua A", null, null, "Curitiba", "PR", "BR",
                -25.42, -49.27, 50, "USER", false, claimedBy, null, status, contact);
    }

    @Test
    @DisplayName("Contatos públicos: representação atual elegível expõe os contatos gravados, sem alterá-los")
    void publicContactsOfEligibleRepresentation() {
        Place place = placeWithContact(UUID.randomUUID(), accountId, "ACTIVE", CONTACT);
        when(businessAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account(accountId, actor, VerificationStatus.APPROVED)));

        assertSame(CONTACT, policy.publicContactInfo(place));
        assertSame(CONTACT, place.getContactInfo());
    }

    @Test
    @DisplayName("Contatos públicos: conta pendente ou rejeitada, administrador inoperante, conta inexistente ou local "
            + "inativo ocultam os contatos")
    void publicContactsHiddenWhileRepresentationIsIneligible() {
        UUID placeId = UUID.randomUUID();
        Place active = placeWithContact(placeId, accountId, "ACTIVE", CONTACT);

        for (VerificationStatus status : new VerificationStatus[]{VerificationStatus.PENDING, VerificationStatus.REJECTED}) {
            when(businessAccountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, actor, status)));
            assertEquals(PlaceContactInfo.EMPTY, policy.publicContactInfo(active), status.name());
        }

        when(businessAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account(accountId, actor, VerificationStatus.APPROVED)));
        when(userRepository.findById(actor)).thenReturn(Optional.of(user(actor, AccountStatus.SUSPENDED, Role.USER)));
        assertEquals(PlaceContactInfo.EMPTY, policy.publicContactInfo(active));

        when(userRepository.findById(actor)).thenReturn(Optional.of(user(actor, AccountStatus.ACTIVE, Role.USER)));
        assertEquals(PlaceContactInfo.EMPTY, policy.publicContactInfo(placeWithContact(placeId, accountId, "INACTIVE", CONTACT)));

        when(businessAccountRepository.findById(accountId)).thenReturn(Optional.empty());
        assertEquals(PlaceContactInfo.EMPTY, policy.publicContactInfo(active));
    }

    @Test
    @DisplayName("Contatos públicos: sem contatos gravados ou sem titular não consulta conta nem administrador")
    void publicContactsWithoutContactOrOwnerSkipLookups() {
        assertEquals(PlaceContactInfo.EMPTY,
                policy.publicContactInfo(placeWithContact(UUID.randomUUID(), accountId, "ACTIVE", PlaceContactInfo.EMPTY)));
        assertEquals(PlaceContactInfo.EMPTY,
                policy.publicContactInfo(placeWithContact(UUID.randomUUID(), null, "ACTIVE", CONTACT)));
        assertEquals(PlaceContactInfo.EMPTY, policy.publicContactInfo(null));
        verifyNoInteractions(businessAccountRepository, placeRepository);
        verify(userRepository, never()).findById(any());
    }
}
