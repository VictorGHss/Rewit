package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.business.BusinessDtos.BusinessAccountView;
import com.rewit.application.dto.business.BusinessDtos.PlaceClaimView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.usecase.CreateBusinessAccountUseCase;
import com.rewit.application.usecase.ListBusinessPlaceClaimsUseCase;
import com.rewit.application.usecase.ListMyBusinessAccountsUseCase;
import com.rewit.application.usecase.RequestPlaceClaimUseCase;
import com.rewit.common.exception.GlobalExceptionHandler;
import com.rewit.domain.enums.PlaceClaimStatus;
import com.rewit.domain.enums.VerificationStatus;
import com.rewit.presentation.dto.business.CreateBusinessAccountRequest;
import com.rewit.presentation.dto.business.RequestPlaceClaimRequest;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: BusinessAccountController (C9)")
class BusinessAccountControllerUnitTest {

    @Mock
    private CreateBusinessAccountUseCase createBusinessAccountUseCase;

    @Mock
    private ListMyBusinessAccountsUseCase listMyBusinessAccountsUseCase;

    @Mock
    private RequestPlaceClaimUseCase requestPlaceClaimUseCase;

    @Mock
    private ListBusinessPlaceClaimsUseCase listBusinessPlaceClaimsUseCase;

    @InjectMocks
    private BusinessAccountController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private UUID userId;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        userId = UUID.randomUUID();
        authentication = new UsernamePasswordAuthenticationToken(userId.toString(), "password");
    }

    @Test
    @DisplayName("POST /api/v1/business-accounts - Deve criar conta comercial com sucesso (201)")
    void shouldCreateBusinessAccountSuccessfully() throws Exception {
        CreateBusinessAccountRequest request = new CreateBusinessAccountRequest("Restaurante Rewit LTDA", "12.345.678/0001-90");
        UUID accountId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-09T12:00:00Z");

        BusinessAccountView view = new BusinessAccountView(
                accountId, "Restaurante Rewit LTDA", "12.345.678/0001-90",
                VerificationStatus.PENDING, "FREE", now, now
        );

        when(createBusinessAccountUseCase.execute(userId, "Restaurante Rewit LTDA", "12.345.678/0001-90"))
                .thenReturn(view);

        mockMvc.perform(post("/api/v1/business-accounts")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(accountId.toString()))
                .andExpect(jsonPath("$.corporateName").value("Restaurante Rewit LTDA"))
                .andExpect(jsonPath("$.taxId").value("12.345.678/0001-90"))
                .andExpect(jsonPath("$.verificationStatus").value("PENDING"))
                .andExpect(jsonPath("$.planTier").value("FREE"))
                .andExpect(jsonPath("$.userId").doesNotExist());

        verify(createBusinessAccountUseCase).execute(userId, "Restaurante Rewit LTDA", "12.345.678/0001-90");
    }

    @Test
    @DisplayName("POST /api/v1/business-accounts - Deve rejeitar razão social ou documento em branco (400)")
    void shouldRejectBlankCorporateNameOrTaxId() throws Exception {
        CreateBusinessAccountRequest invalidRequest = new CreateBusinessAccountRequest("", "");

        mockMvc.perform(post("/api/v1/business-accounts")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/business-accounts/mine - Deve listar contas comerciais do usuario autenticado (200)")
    void shouldListMyBusinessAccounts() throws Exception {
        UUID accountId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-09T12:00:00Z");

        BusinessAccountView view = new BusinessAccountView(
                accountId, "Empresa Alpha", "11.222.333/0001-44",
                VerificationStatus.APPROVED, "FREE", now, now
        );

        when(listMyBusinessAccountsUseCase.execute(userId)).thenReturn(List.of(view));

        mockMvc.perform(get("/api/v1/business-accounts/mine")
                        .principal(authentication)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(accountId.toString()))
                .andExpect(jsonPath("$[0].corporateName").value("Empresa Alpha"))
                .andExpect(jsonPath("$[0].verificationStatus").value("APPROVED"))
                .andExpect(jsonPath("$[0].userId").doesNotExist());

        verify(listMyBusinessAccountsUseCase).execute(userId);
    }

    @Test
    @DisplayName("POST /api/v1/business-accounts/{id}/place-claims - Deve solicitar reivindicacao com sucesso (201)")
    void shouldRequestPlaceClaimSuccessfully() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        String evidence = "Sou o proprietario legal conforme contrato social em anexo no registro da empresa.";
        RequestPlaceClaimRequest request = new RequestPlaceClaimRequest(placeId, evidence);
        Instant now = Instant.parse("2026-10-09T12:00:00Z");

        PlaceClaimView view = new PlaceClaimView(
                claimId, accountId, "Restaurante Rewit", "12.345.678/0001-90",
                placeId, "Café Central", "São Paulo", "SP",
                PlaceClaimStatus.PENDING, evidence, now, null, null
        );

        when(requestPlaceClaimUseCase.execute(userId, accountId, placeId, evidence))
                .thenReturn(view);

        mockMvc.perform(post("/api/v1/business-accounts/{businessAccountId}/place-claims", accountId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(claimId.toString()))
                .andExpect(jsonPath("$.businessAccountId").value(accountId.toString()))
                .andExpect(jsonPath("$.corporateName").value("Restaurante Rewit"))
                .andExpect(jsonPath("$.placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.placeName").value("Café Central"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.evidenceDescription").value(evidence))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.decidedByUserId").doesNotExist());

        verify(requestPlaceClaimUseCase).execute(userId, accountId, placeId, evidence);
    }

    @Test
    @DisplayName("POST /api/v1/business-accounts/{id}/place-claims - Deve rejeitar evidencia menor que 20 caracteres (400)")
    void shouldRejectEvidenceDescriptionShorterThan20Chars() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        RequestPlaceClaimRequest invalidRequest = new RequestPlaceClaimRequest(placeId, "curto");

        mockMvc.perform(post("/api/v1/business-accounts/{businessAccountId}/place-claims", accountId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/business-accounts/{id}/place-claims - Deve listar historico paginado de reivindicacoes (200)")
    void shouldListBusinessPlaceClaimsPaged() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-09T12:00:00Z");

        PlaceClaimView view = new PlaceClaimView(
                claimId, accountId, "Restaurante Rewit", "12.345.678/0001-90",
                placeId, "Café Central", "São Paulo", "SP",
                PlaceClaimStatus.PENDING, "Evidencia valida com mais de vinte caracteres.", now, null, null
        );

        PageResult<PlaceClaimView> pageResult = new PageResult<>(List.of(view), 0, 20, 1, 1, true);
        when(listBusinessPlaceClaimsUseCase.execute(userId, accountId, PlaceClaimStatus.PENDING, 0, 20))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/v1/business-accounts/{businessAccountId}/place-claims", accountId)
                        .principal(authentication)
                        .param("status", "PENDING")
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(claimId.toString()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(20));

        verify(listBusinessPlaceClaimsUseCase).execute(userId, accountId, PlaceClaimStatus.PENDING, 0, 20);
    }
}
