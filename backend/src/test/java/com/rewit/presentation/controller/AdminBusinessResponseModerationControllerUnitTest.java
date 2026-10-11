package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseModerationResult;
import com.rewit.application.dto.business.BusinessResponseDtos.ModerateBusinessResponseCommand;
import com.rewit.application.usecase.ModerateBusinessResponseUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.common.exception.GlobalExceptionHandler;
import com.rewit.domain.enums.BusinessResponseModerationAction;
import com.rewit.domain.enums.BusinessResponseRemovalSource;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: AdminBusinessResponseModerationController (C11)")
class AdminBusinessResponseModerationControllerUnitTest {

    @Mock
    private ModerateBusinessResponseUseCase moderateBusinessResponseUseCase;

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
}
