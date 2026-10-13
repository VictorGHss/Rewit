package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessResponseDtos.AdminRemovedBusinessResponseView;
import com.rewit.application.dto.business.BusinessResponseDtos.RemovedBusinessResponseFilter;
import com.rewit.application.dto.business.BusinessResponseDtos.RemovedBusinessResponseRecord;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.BusinessResponseRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.AccountStatusPolicy;
import com.rewit.application.service.ModeratorRolePolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AccountStatus;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.BusinessResponseRemovalSource;
import com.rewit.domain.enums.Role;
import com.rewit.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueryAdminRemovedBusinessResponsesUseCase (C11): respostas removidas para a moderação")
class QueryAdminRemovedBusinessResponsesUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    @Mock
    private UserRepository userRepository;
    @Mock
    private BusinessResponseRepository businessResponseRepository;

    private QueryAdminRemovedBusinessResponsesUseCase useCase;

    private final UUID actor = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        useCase = new QueryAdminRemovedBusinessResponsesUseCase(businessResponseRepository,
                new ModeratorRolePolicy(new AccountStatusPolicy(userRepository), userRepository));
        CurrentRoleStubs.defaultRole(userRepository, actor, Role.MODERATOR);
    }

    @Test
    @DisplayName("MODERATOR e ADMIN atuais consultam; filtros e paginação chegam intactos ao repositório")
    void moderatorsQuery() {
        UUID reviewId = UUID.randomUUID();
        RemovedBusinessResponseFilter filter = new RemovedBusinessResponseFilter(reviewId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BusinessResponseRemovalSource.MODERATION);
        when(businessResponseRepository.findRemovedForModeration(filter, 2, 10))
                .thenReturn(PageResult.of(List.of(record(BusinessResponseRemovalSource.MODERATION, false)), 2, 10, 21));

        PageResult<AdminRemovedBusinessResponseView> page = useCase.execute(actor, filter, 2, 10);
        assertEquals(1, page.content().size());
        assertEquals(2, page.pageNumber());
        assertEquals(21, page.totalElements());

        CurrentRoleStubs.currentRole(userRepository, actor, Role.ADMIN);
        assertEquals(1, useCase.execute(actor, filter, 2, 10).content().size());
    }

    @Test
    @DisplayName("Sem filtros: consulta com o filtro vazio, nunca nulo")
    void noFilters() {
        when(businessResponseRepository.findRemovedForModeration(RemovedBusinessResponseFilter.none(), 0, 20))
                .thenReturn(PageResult.of(List.of(), 0, 20, 0));

        assertTrue(useCase.execute(actor, null, 0, 20).content().isEmpty());
    }

    @Test
    @DisplayName("USER: 403 FORBIDDEN sem consultar respostas")
    void userForbidden() {
        CurrentRoleStubs.currentRole(userRepository, actor, Role.USER);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> useCase.execute(actor, RemovedBusinessResponseFilter.none(), 0, 20));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("FORBIDDEN", ex.getErrorCode());
        verifyNoInteractions(businessResponseRepository);
    }

    @Test
    @DisplayName("Moderador inoperante: 401 ACCOUNT_DISABLED; ator ausente: 401 UNAUTHORIZED; nada é consultado")
    void unauthenticatedOrInoperative() {
        when(userRepository.findById(actor)).thenReturn(Optional.of(User.rehydrate(actor, "mod@rewit.test", "hash",
                AuthProvider.LOCAL, null, AccountStatus.DEACTIVATED, true, Role.MODERATOR, null, NOW, NOW)));

        BusinessException disabled = assertThrows(BusinessException.class,
                () -> useCase.execute(actor, RemovedBusinessResponseFilter.none(), 0, 20));
        assertEquals(HttpStatus.UNAUTHORIZED, disabled.getStatus());
        assertEquals("ACCOUNT_DISABLED", disabled.getErrorCode());

        assertEquals("UNAUTHORIZED", assertThrows(BusinessException.class,
                () -> useCase.execute(null, RemovedBusinessResponseFilter.none(), 0, 20)).getErrorCode());
        verifyNoInteractions(businessResponseRepository);
    }

    @Test
    @DisplayName("Paginação inválida: 400 antes da política e de qualquer consulta")
    void invalidPagination() {
        assertEquals("INVALID_PAGE", assertThrows(BusinessException.class,
                () -> useCase.execute(actor, null, -1, 20)).getErrorCode());
        assertEquals("INVALID_PAGE_SIZE", assertThrows(BusinessException.class,
                () -> useCase.execute(actor, null, 0, 51)).getErrorCode());
        verifyNoInteractions(userRepository);
        verify(businessResponseRepository, never()).findRemovedForModeration(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Restorabilidade: só a remoção da moderação sem outra resposta ativa; a da empresa nunca")
    void restorability() {
        when(businessResponseRepository.findRemovedForModeration(RemovedBusinessResponseFilter.none(), 0, 20))
                .thenReturn(PageResult.of(List.of(
                        record(BusinessResponseRemovalSource.MODERATION, false),
                        record(BusinessResponseRemovalSource.MODERATION, true),
                        record(BusinessResponseRemovalSource.BUSINESS, false),
                        record(BusinessResponseRemovalSource.BUSINESS, true)), 0, 20, 4));

        List<AdminRemovedBusinessResponseView> views = useCase.execute(actor, null, 0, 20).content();

        assertTrue(views.get(0).restorable());
        assertFalse(views.get(1).restorable());
        assertTrue(views.get(1).otherActiveResponse());
        assertFalse(views.get(2).restorable());
        assertFalse(views.get(3).restorable());
        assertEquals("SPAM", views.get(0).moderationReasonCode());
        assertNull(views.get(2).moderationReasonCode());
    }

    @Test
    @DisplayName("Projeção administrativa: sem autor pessoal nem documento da empresa; dados preservados na ordem recebida")
    void projection() {
        RemovedBusinessResponseRecord first = record(BusinessResponseRemovalSource.MODERATION, false);
        RemovedBusinessResponseRecord second = record(BusinessResponseRemovalSource.BUSINESS, false);
        when(businessResponseRepository.findRemovedForModeration(RemovedBusinessResponseFilter.none(), 0, 20))
                .thenReturn(PageResult.of(List.of(first, second), 0, 20, 2));

        List<AdminRemovedBusinessResponseView> views = useCase.execute(actor, null, 0, 20).content();

        assertEquals(List.of(first.id(), second.id()), views.stream().map(v -> v.id()).toList());
        AdminRemovedBusinessResponseView view = views.get(0);
        assertEquals(first.reviewId(), view.reviewId());
        assertEquals(first.discussionId(), view.discussionId());
        assertEquals("Padaria Central LTDA", view.corporateName());
        assertEquals("Centro", view.placeName());
        assertEquals("Resposta", view.content());
        assertEquals(NOW, view.createdAt());
        assertEquals(NOW.plusSeconds(60), view.removedAt());
        assertEquals(NOW.plusSeconds(60), view.moderatedAt());
        assertFalse(Arrays.stream(AdminRemovedBusinessResponseView.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("authorUserId")
                        || component.getName().equals("taxId")
                        || component.getName().equals("moderatorUserId")));
    }

    private static RemovedBusinessResponseRecord record(BusinessResponseRemovalSource removedBy, boolean otherActive) {
        boolean moderated = removedBy == BusinessResponseRemovalSource.MODERATION;
        return new RemovedBusinessResponseRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "Padaria Central LTDA", UUID.randomUUID(), "Centro", "Resposta", NOW, removedBy,
                NOW.plusSeconds(60), otherActive, moderated ? "SPAM" : null,
                moderated ? "Conteúdo promocional fora das regras" : null, moderated ? NOW.plusSeconds(60) : null);
    }
}
