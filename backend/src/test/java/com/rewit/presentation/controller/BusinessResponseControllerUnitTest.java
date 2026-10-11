package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseEligibilityView;
import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseView;
import com.rewit.application.dto.business.BusinessResponseDtos.EligibleBusinessAccount;
import com.rewit.application.dto.business.BusinessResponseDtos.EligiblePlace;
import com.rewit.application.dto.business.BusinessResponseDtos.PublishDiscussionBusinessResponseCommand;
import com.rewit.application.dto.business.BusinessResponseDtos.PublishReviewBusinessResponseCommand;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.usecase.GetBusinessResponseEligibilityUseCase;
import com.rewit.application.usecase.ListDiscussionBusinessResponsesUseCase;
import com.rewit.application.usecase.ListReviewBusinessResponsesUseCase;
import com.rewit.application.usecase.PublishDiscussionBusinessResponseUseCase;
import com.rewit.application.usecase.PublishReviewBusinessResponseUseCase;
import com.rewit.application.usecase.RemoveBusinessResponseUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.common.exception.GlobalExceptionHandler;
import com.rewit.presentation.dto.business.PublishBusinessResponseRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: BusinessResponseController (C11)")
class BusinessResponseControllerUnitTest {

    @Mock
    private PublishReviewBusinessResponseUseCase publishReviewBusinessResponseUseCase;

    @Mock
    private ListReviewBusinessResponsesUseCase listReviewBusinessResponsesUseCase;

    @Mock
    private GetBusinessResponseEligibilityUseCase getBusinessResponseEligibilityUseCase;

    @Mock
    private PublishDiscussionBusinessResponseUseCase publishDiscussionBusinessResponseUseCase;

    @Mock
    private ListDiscussionBusinessResponsesUseCase listDiscussionBusinessResponsesUseCase;

    @Mock
    private RemoveBusinessResponseUseCase removeBusinessResponseUseCase;

    @InjectMocks
    private BusinessResponseController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private UUID actorUserId;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        actorUserId = UUID.randomUUID();
        authentication = new UsernamePasswordAuthenticationToken(actorUserId.toString(), "password");
    }

    // ---------------------------------------------------------------------------------------------------------
    // POST /api/v1/reviews/{reviewId}/business-responses
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/v1/reviews/{reviewId}/business-responses - Publica com sucesso (201)")
    void shouldPublishReviewBusinessResponseSuccessfully() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        UUID responseId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-11T14:00:00Z");

        PublishBusinessResponseRequest request = new PublishBusinessResponseRequest(
                accountId, placeId, "Agradecemos o feedback e lamentamos o ocorrido. Já alinhamos com a equipe."
        );

        BusinessResponseView view = new BusinessResponseView(
                responseId, reviewId, null, accountId, "Restaurante Exemplo LTDA",
                placeId, "Filial Centro", request.content(), now
        );

        when(publishReviewBusinessResponseUseCase.execute(any(PublishReviewBusinessResponseCommand.class)))
                .thenReturn(view);

        mockMvc.perform(post("/api/v1/reviews/{reviewId}/business-responses", reviewId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(responseId.toString()))
                .andExpect(jsonPath("$.reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.discussionId").doesNotExist())
                .andExpect(jsonPath("$.businessAccountId").value(accountId.toString()))
                .andExpect(jsonPath("$.corporateName").value("Restaurante Exemplo LTDA"))
                .andExpect(jsonPath("$.placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.placeName").value("Filial Centro"))
                .andExpect(jsonPath("$.content").value(request.content()))
                .andExpect(jsonPath("$.createdAt").value("2026-10-11T14:00:00Z"))
                // Verificação estrita de que não expõe dados sensíveis
                .andExpect(jsonPath("$.authorUserId").doesNotExist())
                .andExpect(jsonPath("$.taxId").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.removedBy").doesNotExist())
                .andExpect(jsonPath("$.removedAt").doesNotExist());

        ArgumentCaptor<PublishReviewBusinessResponseCommand> captor =
                ArgumentCaptor.forClass(PublishReviewBusinessResponseCommand.class);
        verify(publishReviewBusinessResponseUseCase).execute(captor.capture());
        PublishReviewBusinessResponseCommand cmd = captor.getValue();
        assertEquals(actorUserId, cmd.actorUserId());
        assertEquals(accountId, cmd.businessAccountId());
        assertEquals(reviewId, cmd.reviewId());
        assertEquals(placeId, cmd.placeId());
        assertEquals(request.content(), cmd.content());
    }

    @Test
    @DisplayName("POST /api/v1/reviews/{reviewId}/business-responses - Rejeita conteúdo em branco ou nulo (400)")
    void shouldRejectBlankContentOnReviewResponse() throws Exception {
        UUID reviewId = UUID.randomUUID();
        PublishBusinessResponseRequest request = new PublishBusinessResponseRequest(
                UUID.randomUUID(), UUID.randomUUID(), "   "
        );

        mockMvc.perform(post("/api/v1/reviews/{reviewId}/business-responses", reviewId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/reviews/{reviewId}/business-responses - Rejeita campos obrigatórios nulos (400)")
    void shouldRejectNullAccountOrPlaceOnReviewResponse() throws Exception {
        UUID reviewId = UUID.randomUUID();
        PublishBusinessResponseRequest request = new PublishBusinessResponseRequest(
                null, null, "Conteúdo da resposta"
        );

        mockMvc.perform(post("/api/v1/reviews/{reviewId}/business-responses", reviewId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/reviews/{reviewId}/business-responses - Rejeita sem autenticação (401)")
    void shouldRejectUnauthenticatedReviewResponsePublish() throws Exception {
        UUID reviewId = UUID.randomUUID();
        PublishBusinessResponseRequest request = new PublishBusinessResponseRequest(
                UUID.randomUUID(), UUID.randomUUID(), "Conteúdo da resposta"
        );

        mockMvc.perform(post("/api/v1/reviews/{reviewId}/business-responses", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/reviews/{reviewId}/business-responses - Preserva erro 429 de rate limit com Retry-After")
    void shouldPreserveRateLimitExceededOnReviewResponsePublish() throws Exception {
        UUID reviewId = UUID.randomUUID();
        PublishBusinessResponseRequest request = new PublishBusinessResponseRequest(
                UUID.randomUUID(), UUID.randomUUID(), "Conteúdo da resposta"
        );

        when(publishReviewBusinessResponseUseCase.execute(any(PublishReviewBusinessResponseCommand.class)))
                .thenThrow(new BusinessException("Limite de respostas oficiais excedido. Tente novamente mais tarde.",
                        HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", Duration.ofSeconds(45)));

        mockMvc.perform(post("/api/v1/reviews/{reviewId}/business-responses", reviewId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "45"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // GET /api/v1/reviews/{reviewId}/business-responses
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/v1/reviews/{reviewId}/business-responses - Lista respostas ativas (200)")
    void shouldListReviewBusinessResponsesSuccessfully() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID responseId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-11T14:00:00Z");

        BusinessResponseView view = new BusinessResponseView(
                responseId, reviewId, null, UUID.randomUUID(), "Empresa XYZ",
                UUID.randomUUID(), "Local XYZ", "Resposta oficial", now
        );
        PageResult<BusinessResponseView> pageResult = new PageResult<>(List.of(view), 0, 20, 1, 1, true);

        when(listReviewBusinessResponsesUseCase.execute(eq(actorUserId), eq(reviewId), eq(0), eq(20)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/v1/reviews/{reviewId}/business-responses", reviewId)
                        .principal(authentication)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(responseId.toString()))
                .andExpect(jsonPath("$.content[0].reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.content[0].corporateName").value("Empresa XYZ"))
                .andExpect(jsonPath("$.content[0].authorUserId").doesNotExist())
                .andExpect(jsonPath("$.content[0].taxId").doesNotExist())
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(listReviewBusinessResponsesUseCase).execute(actorUserId, reviewId, 0, 20);
    }

    @Test
    @DisplayName("GET /api/v1/reviews/{reviewId}/business-responses - Rejeita sem autenticação (401)")
    void shouldRejectUnauthenticatedListReviewBusinessResponses() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/{reviewId}/business-responses", UUID.randomUUID())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------------------------------------------
    // GET /api/v1/reviews/{reviewId}/business-responses/eligibility
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/v1/reviews/{reviewId}/business-responses/eligibility - Consulta elegibilidade com sucesso (200)")
    void shouldGetBusinessResponseEligibilitySuccessfully() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();

        EligiblePlace place = new EligiblePlace(placeId, "Café Central");
        EligibleBusinessAccount account = new EligibleBusinessAccount(
                accountId, "Cafeteria Rewit LTDA", List.of(place), null, true
        );
        BusinessResponseEligibilityView view = new BusinessResponseEligibilityView(
                reviewId, true, List.of(account)
        );

        when(getBusinessResponseEligibilityUseCase.execute(actorUserId, reviewId))
                .thenReturn(view);

        mockMvc.perform(get("/api/v1/reviews/{reviewId}/business-responses/eligibility", reviewId)
                        .principal(authentication)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.respondable").value(true))
                .andExpect(jsonPath("$.accounts.length()").value(1))
                .andExpect(jsonPath("$.accounts[0].businessAccountId").value(accountId.toString()))
                .andExpect(jsonPath("$.accounts[0].corporateName").value("Cafeteria Rewit LTDA"))
                .andExpect(jsonPath("$.accounts[0].canPublish").value(true))
                .andExpect(jsonPath("$.accounts[0].places.length()").value(1))
                .andExpect(jsonPath("$.accounts[0].places[0].placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.accounts[0].places[0].placeName").value("Café Central"))
                .andExpect(jsonPath("$.accounts[0].taxId").doesNotExist());

        verify(getBusinessResponseEligibilityUseCase).execute(actorUserId, reviewId);
    }

    // ---------------------------------------------------------------------------------------------------------
    // POST /api/v1/discussions/{discussionId}/business-responses
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/v1/discussions/{discussionId}/business-responses - Publica com sucesso (201)")
    void shouldPublishDiscussionBusinessResponseSuccessfully() throws Exception {
        UUID discussionId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        UUID responseId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-11T14:30:00Z");

        PublishBusinessResponseRequest request = new PublishBusinessResponseRequest(
                accountId, placeId, "Esclarecimento oficial sobre o comentário do cliente."
        );

        BusinessResponseView view = new BusinessResponseView(
                responseId, reviewId, discussionId, accountId, "Hotel Conforto LTDA",
                placeId, "Hotel Conforto Centro", request.content(), now
        );

        when(publishDiscussionBusinessResponseUseCase.execute(any(PublishDiscussionBusinessResponseCommand.class)))
                .thenReturn(view);

        mockMvc.perform(post("/api/v1/discussions/{discussionId}/business-responses", discussionId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(responseId.toString()))
                .andExpect(jsonPath("$.reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.discussionId").value(discussionId.toString()))
                .andExpect(jsonPath("$.businessAccountId").value(accountId.toString()))
                .andExpect(jsonPath("$.corporateName").value("Hotel Conforto LTDA"))
                .andExpect(jsonPath("$.placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.content").value(request.content()))
                .andExpect(jsonPath("$.authorUserId").doesNotExist())
                .andExpect(jsonPath("$.taxId").doesNotExist());

        ArgumentCaptor<PublishDiscussionBusinessResponseCommand> captor =
                ArgumentCaptor.forClass(PublishDiscussionBusinessResponseCommand.class);
        verify(publishDiscussionBusinessResponseUseCase).execute(captor.capture());
        PublishDiscussionBusinessResponseCommand cmd = captor.getValue();
        assertEquals(actorUserId, cmd.actorUserId());
        assertEquals(discussionId, cmd.discussionId());
        assertEquals(accountId, cmd.businessAccountId());
        assertEquals(placeId, cmd.placeId());
        assertEquals(request.content(), cmd.content());
    }

    // ---------------------------------------------------------------------------------------------------------
    // GET /api/v1/discussions/{discussionId}/business-responses
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/v1/discussions/{discussionId}/business-responses - Lista respostas ativas do comentário (200)")
    void shouldListDiscussionBusinessResponsesSuccessfully() throws Exception {
        UUID discussionId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID responseId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-11T14:30:00Z");

        BusinessResponseView view = new BusinessResponseView(
                responseId, reviewId, discussionId, UUID.randomUUID(), "Livraria Saber",
                UUID.randomUUID(), "Livraria Saber - Matriz", "Resposta oficial ao comentário", now
        );
        PageResult<BusinessResponseView> pageResult = new PageResult<>(List.of(view), 1, 10, 1, 1, true);

        when(listDiscussionBusinessResponsesUseCase.execute(eq(actorUserId), eq(discussionId), eq(1), eq(10)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/v1/discussions/{discussionId}/business-responses", discussionId)
                        .param("page", "1")
                        .param("size", "10")
                        .principal(authentication)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(responseId.toString()))
                .andExpect(jsonPath("$.content[0].discussionId").value(discussionId.toString()))
                .andExpect(jsonPath("$.pageNumber").value(1))
                .andExpect(jsonPath("$.pageSize").value(10));

        verify(listDiscussionBusinessResponsesUseCase).execute(actorUserId, discussionId, 1, 10);
    }

    // ---------------------------------------------------------------------------------------------------------
    // DELETE /api/v1/business-responses/{responseId}
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("DELETE /api/v1/business-responses/{responseId} - Remove resposta com sucesso (204 No Content)")
    void shouldRemoveBusinessResponseSuccessfully() throws Exception {
        UUID responseId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/business-responses/{responseId}", responseId)
                        .principal(authentication))
                .andExpect(status().isNoContent());

        verify(removeBusinessResponseUseCase).execute(actorUserId, responseId);
    }

    @Test
    @DisplayName("DELETE /api/v1/business-responses/{responseId} - Rejeita sem autenticação (401)")
    void shouldRejectUnauthenticatedRemoveBusinessResponse() throws Exception {
        mockMvc.perform(delete("/api/v1/business-responses/{responseId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /api/v1/business-responses/{responseId} - Propaga 403 quando não é administrador da conta autora")
    void shouldPropagateForbiddenWhenNotResponsible() throws Exception {
        UUID responseId = UUID.randomUUID();

        doThrow(new BusinessException("Usuário não administra a empresa autora da resposta",
                HttpStatus.FORBIDDEN, "BUSINESS_NOT_RESPONSIBLE"))
                .when(removeBusinessResponseUseCase).execute(actorUserId, responseId);

        mockMvc.perform(delete("/api/v1/business-responses/{responseId}", responseId)
                        .principal(authentication))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BUSINESS_NOT_RESPONSIBLE"));
    }
}
