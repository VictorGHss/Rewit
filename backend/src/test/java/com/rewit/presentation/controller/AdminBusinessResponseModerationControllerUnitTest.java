package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.business.BusinessResponseDtos.AdminRemovedBusinessResponseView;
import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseModerationResult;
import com.rewit.application.dto.business.BusinessResponseDtos.ModerateBusinessResponseCommand;
import com.rewit.application.dto.business.BusinessResponseDtos.RemovedBusinessResponseFilter;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.usecase.ModerateBusinessResponseUseCase;
import com.rewit.application.usecase.QueryAdminRemovedBusinessResponsesUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.common.exception.GlobalExceptionHandler;
import com.rewit.domain.enums.BusinessResponseModerationAction;
import com.rewit.domain.enums.BusinessResponseRemovalSource;
import com.rewit.domain.enums.BusinessResponseRestorationBlocker;
import com.rewit.domain.enums.BusinessResponseStatus;
import com.rewit.presentation.dto.admin.AdminBusinessResponseModerationDtos.ModerateBusinessResponseRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: AdminBusinessResponseModerationController (C11)")
class AdminBusinessResponseModerationControllerUnitTest {

    @Mock
    private ModerateBusinessResponseUseCase moderateBusinessResponseUseCase;

    @Mock
    private QueryAdminRemovedBusinessResponsesUseCase queryAdminRemovedBusinessResponsesUseCase;

    @InjectMocks
    private AdminBusinessResponseModerationController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private UUID moderatorUserId;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        moderatorUserId = UUID.randomUUID();
        authentication = new UsernamePasswordAuthenticationToken(moderatorUserId.toString(), "password");
    }

    @Test
    @DisplayName("POST /api/v1/admin/business-responses/{responseId}/moderate - Remove resposta com sucesso (200)")
    void shouldModerateRemoveResponseSuccessfully() throws Exception {
        UUID responseId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        UUID auditLogId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-11T15:00:00Z");

        ModerateBusinessResponseRequest request = new ModerateBusinessResponseRequest(
                BusinessResponseModerationAction.REMOVE_RESPONSE,
                "TERMS_VIOLATION",
                "Resposta contém linguagem inadequada e promocional excessiva."
        );

        BusinessResponseModerationResult result = new BusinessResponseModerationResult(
                auditLogId, responseId, reviewId, null, accountId, placeId,
                BusinessResponseModerationAction.REMOVE_RESPONSE, "TERMS_VIOLATION",
                request.justification(), BusinessResponseStatus.ACTIVE,
                BusinessResponseStatus.REMOVED, BusinessResponseRemovalSource.MODERATION, now
        );

        when(moderateBusinessResponseUseCase.execute(any(ModerateBusinessResponseCommand.class)))
                .thenReturn(result);

        mockMvc.perform(post("/api/v1/admin/business-responses/{responseId}/moderate", responseId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditLogId").value(auditLogId.toString()))
                .andExpect(jsonPath("$.responseId").value(responseId.toString()))
                .andExpect(jsonPath("$.reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.discussionId").doesNotExist())
                .andExpect(jsonPath("$.businessAccountId").value(accountId.toString()))
                .andExpect(jsonPath("$.placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.action").value("REMOVE_RESPONSE"))
                .andExpect(jsonPath("$.reasonCode").value("TERMS_VIOLATION"))
                .andExpect(jsonPath("$.justification").value(request.justification()))
                .andExpect(jsonPath("$.previousStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.newStatus").value("REMOVED"))
                .andExpect(jsonPath("$.removedBy").value("MODERATION"))
                .andExpect(jsonPath("$.moderatedAt").value("2026-10-11T15:00:00Z"))
                // Verificação de segurança: não expõe authorUserId nem taxId
                .andExpect(jsonPath("$.authorUserId").doesNotExist())
                .andExpect(jsonPath("$.taxId").doesNotExist());

        ArgumentCaptor<ModerateBusinessResponseCommand> captor =
                ArgumentCaptor.forClass(ModerateBusinessResponseCommand.class);
        verify(moderateBusinessResponseUseCase).execute(captor.capture());
        ModerateBusinessResponseCommand cmd = captor.getValue();
        assertEquals(responseId, cmd.responseId());
        assertEquals(moderatorUserId, cmd.moderatorUserId());
        assertEquals(BusinessResponseModerationAction.REMOVE_RESPONSE, cmd.action());
        assertEquals("TERMS_VIOLATION", cmd.reasonCode());
        assertEquals(request.justification(), cmd.justification());
    }

    @Test
    @DisplayName("POST /api/v1/admin/business-responses/{responseId}/moderate - Restaura resposta com sucesso (200)")
    void shouldModerateRestoreResponseSuccessfully() throws Exception {
        UUID responseId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        UUID auditLogId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-11T15:30:00Z");

        ModerateBusinessResponseRequest request = new ModerateBusinessResponseRequest(
                BusinessResponseModerationAction.RESTORE_RESPONSE,
                "APPEAL_ACCEPTED",
                "Recurso aceito após conferência com as diretrizes da comunidade."
        );

        BusinessResponseModerationResult result = new BusinessResponseModerationResult(
                auditLogId, responseId, reviewId, null, accountId, placeId,
                BusinessResponseModerationAction.RESTORE_RESPONSE, "APPEAL_ACCEPTED",
                request.justification(), BusinessResponseStatus.REMOVED,
                BusinessResponseStatus.ACTIVE, null, now
        );

        when(moderateBusinessResponseUseCase.execute(any(ModerateBusinessResponseCommand.class)))
                .thenReturn(result);

        mockMvc.perform(post("/api/v1/admin/business-responses/{responseId}/moderate", responseId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("RESTORE_RESPONSE"))
                .andExpect(jsonPath("$.previousStatus").value("REMOVED"))
                .andExpect(jsonPath("$.newStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.removedBy").doesNotExist());

        verify(moderateBusinessResponseUseCase).execute(any(ModerateBusinessResponseCommand.class));
    }

    @Test
    @DisplayName("POST /api/v1/admin/business-responses/{responseId}/moderate - Rejeita justificativa curta (< 15 caracteres) (400)")
    void shouldRejectShortJustification() throws Exception {
        UUID responseId = UUID.randomUUID();
        ModerateBusinessResponseRequest request = new ModerateBusinessResponseRequest(
                BusinessResponseModerationAction.REMOVE_RESPONSE,
                "TERMS_VIOLATION",
                "Muito curta"
        );

        mockMvc.perform(post("/api/v1/admin/business-responses/{responseId}/moderate", responseId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/admin/business-responses/{responseId}/moderate - Rejeita reasonCode em branco (400)")
    void shouldRejectBlankReasonCode() throws Exception {
        UUID responseId = UUID.randomUUID();
        ModerateBusinessResponseRequest request = new ModerateBusinessResponseRequest(
                BusinessResponseModerationAction.REMOVE_RESPONSE,
                "   ",
                "Justificativa válida com mais de quinze caracteres."
        );

        mockMvc.perform(post("/api/v1/admin/business-responses/{responseId}/moderate", responseId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/admin/business-responses/{responseId}/moderate - Rejeita sem autenticação (401)")
    void shouldRejectUnauthenticatedModeration() throws Exception {
        UUID responseId = UUID.randomUUID();
        ModerateBusinessResponseRequest request = new ModerateBusinessResponseRequest(
                BusinessResponseModerationAction.REMOVE_RESPONSE,
                "TERMS_VIOLATION",
                "Justificativa válida com mais de quinze caracteres."
        );

        mockMvc.perform(post("/api/v1/admin/business-responses/{responseId}/moderate", responseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/admin/business-responses/{responseId}/moderate - Propaga 403 em conflito de interesse (SELF_MODERATION_FORBIDDEN)")
    void shouldPropagateSelfModerationForbidden() throws Exception {
        UUID responseId = UUID.randomUUID();
        ModerateBusinessResponseRequest request = new ModerateBusinessResponseRequest(
                BusinessResponseModerationAction.REMOVE_RESPONSE,
                "TERMS_VIOLATION",
                "Justificativa válida com mais de quinze caracteres."
        );

        when(moderateBusinessResponseUseCase.execute(any(ModerateBusinessResponseCommand.class)))
                .thenThrow(new BusinessException("O moderador não pode moderar uma resposta da própria empresa",
                        HttpStatus.FORBIDDEN, "SELF_MODERATION_FORBIDDEN"));

        mockMvc.perform(post("/api/v1/admin/business-responses/{responseId}/moderate", responseId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_MODERATION_FORBIDDEN"));
    }

    @Test
    @DisplayName("GET /api/v1/admin/business-responses/removed - Retorna 200 com paginação e itens mapeados")
    void shouldListRemovedBusinessResponsesSuccessfully() throws Exception {
        UUID responseId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID discussionId = UUID.randomUUID();
        UUID businessAccountId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-10T12:00:00Z");
        Instant removedAt = Instant.parse("2026-10-11T14:00:00Z");
        Instant moderatedAt = Instant.parse("2026-10-11T14:00:00Z");

        AdminRemovedBusinessResponseView view = new AdminRemovedBusinessResponseView(
                responseId,
                reviewId,
                discussionId,
                businessAccountId,
                "Restaurante Sabor & Arte",
                placeId,
                "Unidade Centro",
                "Resposta oficial removida para análise.",
                createdAt,
                BusinessResponseRemovalSource.MODERATION,
                removedAt,
                true,
                false,
                "INAPPROPRIATE_CONTENT",
                "Linguagem incompatível com diretrizes.",
                moderatedAt,
                List.of()
        );

        PageResult<AdminRemovedBusinessResponseView> pageResult = PageResult.of(
                List.of(view), 1, 10, 25
        );

        when(queryAdminRemovedBusinessResponsesUseCase.execute(
                eq(moderatorUserId),
                any(RemovedBusinessResponseFilter.class),
                eq(1),
                eq(10)
        )).thenReturn(pageResult);

        mockMvc.perform(get("/api/v1/admin/business-responses/removed")
                        .principal(authentication)
                        .param("reviewId", reviewId.toString())
                        .param("discussionId", discussionId.toString())
                        .param("businessAccountId", businessAccountId.toString())
                        .param("placeId", placeId.toString())
                        .param("removedBy", "MODERATION")
                        .param("page", "1")
                        .param("size", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(responseId.toString()))
                .andExpect(jsonPath("$.content[0].reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.content[0].discussionId").value(discussionId.toString()))
                .andExpect(jsonPath("$.content[0].businessAccountId").value(businessAccountId.toString()))
                .andExpect(jsonPath("$.content[0].corporateName").value("Restaurante Sabor & Arte"))
                .andExpect(jsonPath("$.content[0].placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.content[0].placeName").value("Unidade Centro"))
                .andExpect(jsonPath("$.content[0].content").value("Resposta oficial removida para análise."))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-10-10T12:00:00Z"))
                .andExpect(jsonPath("$.content[0].removedBy").value("MODERATION"))
                .andExpect(jsonPath("$.content[0].removedAt").value("2026-10-11T14:00:00Z"))
                .andExpect(jsonPath("$.content[0].restorable").value(true))
                .andExpect(jsonPath("$.content[0].restorationBlockers").isArray())
                .andExpect(jsonPath("$.content[0].restorationBlockers.length()").value(0))
                .andExpect(jsonPath("$.content[0].otherActiveResponse").value(false))
                .andExpect(jsonPath("$.content[0].moderationReasonCode").value("INAPPROPRIATE_CONTENT"))
                .andExpect(jsonPath("$.content[0].moderationJustification").value("Linguagem incompatível com diretrizes."))
                .andExpect(jsonPath("$.content[0].moderatedAt").value("2026-10-11T14:00:00Z"))
                .andExpect(jsonPath("$.pageNumber").value(1))
                .andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.isLast").value(false))
                // Verificação de segurança: não expõe authorUserId, taxId ou moderatorUserId
                .andExpect(jsonPath("$.content[0].authorUserId").doesNotExist())
                .andExpect(jsonPath("$.content[0].taxId").doesNotExist())
                .andExpect(jsonPath("$.content[0].moderatorUserId").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/admin/business-responses/removed - Propaga ator autenticado e todos os filtros ao caso de uso")
    void shouldForwardActorUserIdAndFiltersToUseCase() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID discussionId = UUID.randomUUID();
        UUID businessAccountId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();

        when(queryAdminRemovedBusinessResponsesUseCase.execute(
                any(UUID.class),
                any(RemovedBusinessResponseFilter.class),
                any(int.class),
                any(int.class)
        )).thenReturn(PageResult.of(List.of(), 2, 15, 0));

        mockMvc.perform(get("/api/v1/admin/business-responses/removed")
                        .principal(authentication)
                        .param("reviewId", reviewId.toString())
                        .param("discussionId", discussionId.toString())
                        .param("businessAccountId", businessAccountId.toString())
                        .param("placeId", placeId.toString())
                        .param("removedBy", "BUSINESS")
                        .param("page", "2")
                        .param("size", "15")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        ArgumentCaptor<UUID> actorCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<RemovedBusinessResponseFilter> filterCaptor =
                ArgumentCaptor.forClass(RemovedBusinessResponseFilter.class);
        ArgumentCaptor<Integer> pageCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> sizeCaptor = ArgumentCaptor.forClass(Integer.class);

        verify(queryAdminRemovedBusinessResponsesUseCase).execute(
                actorCaptor.capture(),
                filterCaptor.capture(),
                pageCaptor.capture(),
                sizeCaptor.capture()
        );

        assertEquals(moderatorUserId, actorCaptor.getValue());
        assertEquals(reviewId, filterCaptor.getValue().reviewId());
        assertEquals(discussionId, filterCaptor.getValue().discussionId());
        assertEquals(businessAccountId, filterCaptor.getValue().businessAccountId());
        assertEquals(placeId, filterCaptor.getValue().placeId());
        assertEquals(BusinessResponseRemovalSource.BUSINESS, filterCaptor.getValue().removedBy());
        assertEquals(2, pageCaptor.getValue());
        assertEquals(15, sizeCaptor.getValue());
    }

    @Test
    @DisplayName("GET /api/v1/admin/business-responses/removed - Aplica valores padrão para page (0) e size (20)")
    void shouldApplyDefaultPageAndSizeWhenOmitted() throws Exception {
        when(queryAdminRemovedBusinessResponsesUseCase.execute(
                any(UUID.class),
                any(RemovedBusinessResponseFilter.class),
                any(int.class),
                any(int.class)
        )).thenReturn(PageResult.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/admin/business-responses/removed")
                        .principal(authentication)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(20));

        ArgumentCaptor<RemovedBusinessResponseFilter> filterCaptor =
                ArgumentCaptor.forClass(RemovedBusinessResponseFilter.class);
        ArgumentCaptor<Integer> pageCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> sizeCaptor = ArgumentCaptor.forClass(Integer.class);

        verify(queryAdminRemovedBusinessResponsesUseCase).execute(
                eq(moderatorUserId),
                filterCaptor.capture(),
                pageCaptor.capture(),
                sizeCaptor.capture()
        );

        assertEquals(0, pageCaptor.getValue());
        assertEquals(20, sizeCaptor.getValue());
        assertNull(filterCaptor.getValue().reviewId());
        assertNull(filterCaptor.getValue().discussionId());
        assertNull(filterCaptor.getValue().businessAccountId());
        assertNull(filterCaptor.getValue().placeId());
        assertNull(filterCaptor.getValue().removedBy());
    }

    @Test
    @DisplayName("GET /api/v1/admin/business-responses/removed - Rejeita UUID inválido com erro 400 TYPE_MISMATCH")
    void shouldRejectInvalidUuid() throws Exception {
        mockMvc.perform(get("/api/v1/admin/business-responses/removed")
                        .principal(authentication)
                        .param("reviewId", "nao-e-um-uuid-valido")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"));
    }

    @Test
    @DisplayName("GET /api/v1/admin/business-responses/removed - Rejeita enum inválido com erro 400 TYPE_MISMATCH")
    void shouldRejectInvalidEnum() throws Exception {
        mockMvc.perform(get("/api/v1/admin/business-responses/removed")
                        .principal(authentication)
                        .param("removedBy", "ORIGEM_INEXISTENTE")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"));
    }

    @Test
    @DisplayName("GET /api/v1/admin/business-responses/removed - Rejeita page negativa com 400 INVALID_PAGE")
    void shouldRejectNegativePage() throws Exception {
        when(queryAdminRemovedBusinessResponsesUseCase.execute(
                eq(moderatorUserId),
                any(RemovedBusinessResponseFilter.class),
                eq(-1),
                eq(20)
        )).thenThrow(new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST, "INVALID_PAGE"));

        mockMvc.perform(get("/api/v1/admin/business-responses/removed")
                        .principal(authentication)
                        .param("page", "-1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
    }

    @Test
    @DisplayName("GET /api/v1/admin/business-responses/removed - Rejeita size fora dos limites com 400 INVALID_PAGE_SIZE")
    void shouldRejectInvalidPageSize() throws Exception {
        when(queryAdminRemovedBusinessResponsesUseCase.execute(
                eq(moderatorUserId),
                any(RemovedBusinessResponseFilter.class),
                eq(0),
                eq(51)
        )).thenThrow(new BusinessException("O tamanho da página deve ser entre 1 e 50", HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE"));

        mockMvc.perform(get("/api/v1/admin/business-responses/removed")
                        .principal(authentication)
                        .param("size", "51")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"));
    }

    @Test
    @DisplayName("GET /api/v1/admin/business-responses/removed - Preserva contexto de auditoria e indicador restorable")
    void shouldPreserveAuditContextAndRestorableIndicator() throws Exception {
        Instant moderatedAt = Instant.parse("2026-10-12T09:30:00Z");
        AdminRemovedBusinessResponseView view = new AdminRemovedBusinessResponseView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                "Empresa Teste",
                UUID.randomUUID(),
                "Local Teste",
                "Conteúdo",
                Instant.parse("2026-10-10T10:00:00Z"),
                BusinessResponseRemovalSource.MODERATION,
                moderatedAt,
                true,
                false,
                "TERMS_OF_SERVICE",
                "Justificativa completa da moderação de remoção.",
                moderatedAt,
                List.of()
        );

        when(queryAdminRemovedBusinessResponsesUseCase.execute(
                eq(moderatorUserId),
                any(RemovedBusinessResponseFilter.class),
                eq(0),
                eq(20)
        )).thenReturn(PageResult.of(List.of(view), 0, 20, 1));

        mockMvc.perform(get("/api/v1/admin/business-responses/removed")
                        .principal(authentication)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].restorable").value(true))
                .andExpect(jsonPath("$.content[0].otherActiveResponse").value(false))
                .andExpect(jsonPath("$.content[0].removedBy").value("MODERATION"))
                .andExpect(jsonPath("$.content[0].moderationReasonCode").value("TERMS_OF_SERVICE"))
                .andExpect(jsonPath("$.content[0].moderationJustification").value("Justificativa completa da moderação de remoção."))
                .andExpect(jsonPath("$.content[0].moderatedAt").value("2026-10-12T09:30:00Z"));
    }

    @Test
    @DisplayName("GET /api/v1/admin/business-responses/removed - restorationBlockers serializa os motivos estáveis, na ordem da view")
    void shouldSerializeRestorationBlockers() throws Exception {
        AdminRemovedBusinessResponseView view = new AdminRemovedBusinessResponseView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                "Empresa Teste",
                UUID.randomUUID(),
                "Local Teste",
                "Conteúdo",
                Instant.parse("2026-10-10T10:00:00Z"),
                BusinessResponseRemovalSource.MODERATION,
                Instant.parse("2026-10-11T10:00:00Z"),
                false,
                true,
                "SPAM",
                "Justificativa completa da moderação de remoção.",
                Instant.parse("2026-10-11T10:00:00Z"),
                List.of(BusinessResponseRestorationBlocker.OTHER_ACTIVE_RESPONSE,
                        BusinessResponseRestorationBlocker.ADMINISTRATOR_NOT_OPERATIONAL,
                        BusinessResponseRestorationBlocker.PLACE_NOT_REPRESENTED)
        );
        when(queryAdminRemovedBusinessResponsesUseCase.execute(
                eq(moderatorUserId),
                any(RemovedBusinessResponseFilter.class),
                eq(0),
                eq(20)
        )).thenReturn(PageResult.of(List.of(view), 0, 20, 1));

        mockMvc.perform(get("/api/v1/admin/business-responses/removed")
                        .principal(authentication)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].restorable").value(false))
                .andExpect(jsonPath("$.content[0].restorationBlockers.length()").value(3))
                .andExpect(jsonPath("$.content[0].restorationBlockers[0]").value("OTHER_ACTIVE_RESPONSE"))
                .andExpect(jsonPath("$.content[0].restorationBlockers[1]").value("ADMINISTRATOR_NOT_OPERATIONAL"))
                .andExpect(jsonPath("$.content[0].restorationBlockers[2]").value("PLACE_NOT_REPRESENTED"));
    }

    @Test
    @DisplayName("GET /api/v1/admin/business-responses/removed - Rejeita requisição sem autenticação com 401")
    void shouldRejectUnauthenticatedQuery() throws Exception {
        mockMvc.perform(get("/api/v1/admin/business-responses/removed")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
