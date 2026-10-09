package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.business.BusinessDtos.PlaceClaimView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.usecase.DecidePlaceClaimUseCase;
import com.rewit.application.usecase.QueryAdminPlaceClaimsUseCase;
import com.rewit.common.exception.GlobalExceptionHandler;
import com.rewit.domain.enums.PlaceClaimDecision;
import com.rewit.domain.enums.PlaceClaimStatus;
import com.rewit.presentation.dto.business.DecidePlaceClaimRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: AdminPlaceClaimController (C9)")
class AdminPlaceClaimControllerUnitTest {

    @Mock
    private QueryAdminPlaceClaimsUseCase queryAdminPlaceClaimsUseCase;

    @Mock
    private DecidePlaceClaimUseCase decidePlaceClaimUseCase;

    @InjectMocks
    private AdminPlaceClaimController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private UUID moderatorId;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        moderatorId = UUID.randomUUID();
        authentication = new UsernamePasswordAuthenticationToken(moderatorId.toString(), "password");
    }

    @Test
    @DisplayName("GET /api/v1/admin/place-claims - Deve retornar fila administrativa paginada (200)")
    void shouldReturnAdminClaimsQueuePaged() throws Exception {
        UUID claimId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-09T12:00:00Z");

        PlaceClaimView view = new PlaceClaimView(
                claimId, accountId, "Empresa Alpha", "11.222.333/0001-44",
                placeId, "Loja Alpha", "Curitiba", "PR",
                PlaceClaimStatus.PENDING, "Contrato social verificado em cartorio.",
                now, null, null
        );

        PageResult<PlaceClaimView> pageResult = new PageResult<>(List.of(view), 0, 20, 1, 1, true);
        when(queryAdminPlaceClaimsUseCase.execute(moderatorId, 0, 20, PlaceClaimStatus.PENDING)).thenReturn(pageResult);

        mockMvc.perform(get("/api/v1/admin/place-claims")
                        .principal(authentication)
                        .param("status", "PENDING")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(claimId.toString()))
                .andExpect(jsonPath("$.content[0].corporateName").value("Empresa Alpha"))
                .andExpect(jsonPath("$.content[0].placeName").value("Loja Alpha"))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(20));

        verify(queryAdminPlaceClaimsUseCase).execute(moderatorId, 0, 20, PlaceClaimStatus.PENDING);
    }

    @Test
    @DisplayName("POST /api/v1/admin/place-claims/{claimId}/decision - Deve aprovar reivindicacao (200)")
    void shouldApproveClaimSuccessfully() throws Exception {
        UUID claimId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-09T12:00:00Z");
        Instant decidedAt = Instant.parse("2026-10-09T13:00:00Z");
        String justification = "Documentacao societaria validada com sucesso.";

        DecidePlaceClaimRequest request = new DecidePlaceClaimRequest(PlaceClaimDecision.APPROVE, justification);

        PlaceClaimView view = new PlaceClaimView(
                claimId, accountId, "Empresa Alpha", "11.222.333/0001-44",
                placeId, "Loja Alpha", "Curitiba", "PR",
                PlaceClaimStatus.APPROVED, "Contrato social verificado em cartorio.",
                createdAt, decidedAt, justification
        );

        when(decidePlaceClaimUseCase.execute(eq(moderatorId), eq(claimId), eq(PlaceClaimDecision.APPROVE), eq(justification), any()))
                .thenReturn(view);

        mockMvc.perform(post("/api/v1/admin/place-claims/{claimId}/decision", claimId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(claimId.toString()))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decisionReason").value(justification))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.decidedByUserId").doesNotExist());

        verify(decidePlaceClaimUseCase).execute(eq(moderatorId), eq(claimId), eq(PlaceClaimDecision.APPROVE), eq(justification), any());
    }

    @Test
    @DisplayName("POST /api/v1/admin/place-claims/{claimId}/decision - Deve rejeitar justificativa menor que 15 caracteres (400)")
    void shouldRejectJustificationShorterThan15Chars() throws Exception {
        UUID claimId = UUID.randomUUID();
        DecidePlaceClaimRequest invalidRequest = new DecidePlaceClaimRequest(PlaceClaimDecision.REJECT, "muito curto");

        mockMvc.perform(post("/api/v1/admin/place-claims/{claimId}/decision", claimId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/admin/place-claims/{claimId}/decision - Deve rejeitar payload sem decisao (400)")
    void shouldRejectPayloadWithoutDecision() throws Exception {
        UUID claimId = UUID.randomUUID();
        DecidePlaceClaimRequest invalidRequest = new DecidePlaceClaimRequest(null, "Justificativa valida com mais de quinze caracteres.");

        mockMvc.perform(post("/api/v1/admin/place-claims/{claimId}/decision", claimId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }
}
