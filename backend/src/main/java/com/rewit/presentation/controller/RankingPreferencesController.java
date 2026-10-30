package com.rewit.presentation.controller;

import com.rewit.application.dto.ranking.RankingPreferencesDto;
import com.rewit.application.usecase.GetRankingPreferencesUseCase;
import com.rewit.application.usecase.UpdateRankingPreferencesUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.ranking.RankingPresentationDtos.RankingPreferencesResponse;
import com.rewit.presentation.dto.ranking.RankingPresentationDtos.UpdateRankingPreferencesRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Controlador REST para preferências de participação nos rankings (ADR-016).
 * Segue o modelo de consentimento explícito (opt-in): falso por padrão para novas e antigas contas.
 */
@RestController
@RequestMapping("/api/v1/me/ranking-preferences")
@Tag(name = "Preferências de Rankings", description = "Gerenciamento de opt-in de participação nos rankings")
public class RankingPreferencesController {

    private final GetRankingPreferencesUseCase getRankingPreferencesUseCase;
    private final UpdateRankingPreferencesUseCase updateRankingPreferencesUseCase;

    public RankingPreferencesController(
            GetRankingPreferencesUseCase getRankingPreferencesUseCase,
            UpdateRankingPreferencesUseCase updateRankingPreferencesUseCase
    ) {
        this.getRankingPreferencesUseCase = Objects.requireNonNull(getRankingPreferencesUseCase,
                "GetRankingPreferencesUseCase must not be null");
        this.updateRankingPreferencesUseCase = Objects.requireNonNull(updateRankingPreferencesUseCase,
                "UpdateRankingPreferencesUseCase must not be null");
    }

    @GetMapping
    @Operation(summary = "Obter preferências de participação em rankings do usuário autenticado",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<RankingPreferencesResponse> getPreferences(Authentication authentication) {
        UUID userId = extractAuthenticatedUserId(authentication);
        RankingPreferencesDto dto = getRankingPreferencesUseCase.execute(userId);
        return ResponseEntity.ok(RankingPreferencesResponse.fromDto(dto));
    }

    @PutMapping
    @Operation(summary = "Atualizar opt-in de participação em rankings do usuário autenticado",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<RankingPreferencesResponse> updatePreferences(
            @Valid @RequestBody UpdateRankingPreferencesRequest request,
            Authentication authentication
    ) {
        UUID userId = extractAuthenticatedUserId(authentication);
        RankingPreferencesDto dto = updateRankingPreferencesUseCase.execute(userId, request.showInRankings());
        return ResponseEntity.ok(RankingPreferencesResponse.fromDto(dto));
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Identificador de usuário inválido na sessão", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }
}
