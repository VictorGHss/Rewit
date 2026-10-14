package com.rewit.presentation.controller;

import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.PlaceOpeningHoursView;
import com.rewit.application.usecase.GetPlaceOpeningHoursUseCase;
import com.rewit.application.usecase.UpdatePlaceOpeningHoursUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.catalog.PlaceOpeningHoursPresentationDtos.PlaceOpeningHoursResponse;
import com.rewit.presentation.dto.catalog.PlaceOpeningHoursPresentationDtos.UpdatePlaceOpeningHoursRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.UUID;

/**
 * Controller RESTful para consulta e configuração de horários de funcionamento de estabelecimentos (F2.1).
 */
@RestController
@RequestMapping("/api/v1/places/{placeId}/opening-hours")
@Tag(name = "Catálogo - Horários de Funcionamento", description = "Endpoints para consulta e gestão de horários de funcionamento (F2.1)")
public class PlaceOpeningHoursController {

    private final GetPlaceOpeningHoursUseCase getPlaceOpeningHoursUseCase;
    private final UpdatePlaceOpeningHoursUseCase updatePlaceOpeningHoursUseCase;

    public PlaceOpeningHoursController(
            GetPlaceOpeningHoursUseCase getPlaceOpeningHoursUseCase,
            UpdatePlaceOpeningHoursUseCase updatePlaceOpeningHoursUseCase
    ) {
        this.getPlaceOpeningHoursUseCase = Objects.requireNonNull(getPlaceOpeningHoursUseCase,
                "GetPlaceOpeningHoursUseCase must not be null");
        this.updatePlaceOpeningHoursUseCase = Objects.requireNonNull(updatePlaceOpeningHoursUseCase,
                "UpdatePlaceOpeningHoursUseCase must not be null");
    }

    /**
     * GET /api/v1/places/{placeId}/opening-hours
     * Consulta pública dos horários de funcionamento de um local ativo no catálogo.
     */
    @GetMapping
    @Operation(summary = "Consultar horários de funcionamento de um local", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PlaceOpeningHoursResponse> getOpeningHours(
            @PathVariable("placeId") UUID placeId,
            Authentication authentication
    ) {
        extractAuthenticatedUserId(authentication);
        PlaceOpeningHoursView view = getPlaceOpeningHoursUseCase.execute(placeId);
        return ResponseEntity.ok(PlaceOpeningHoursResponse.fromView(view));
    }

    /**
     * PUT /api/v1/places/{placeId}/opening-hours
     * Configura ou atualiza a grade semanal de horários de funcionamento de um local reivindicado.
     */
    @PutMapping
    @Operation(summary = "Configurar horários de funcionamento do estabelecimento", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PlaceOpeningHoursResponse> updateOpeningHours(
            @PathVariable("placeId") UUID placeId,
            @Valid @RequestBody UpdatePlaceOpeningHoursRequest request,
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        PlaceOpeningHoursView view = updatePlaceOpeningHoursUseCase.execute(
                request.toCommand(actorUserId, placeId)
        );
        return ResponseEntity.ok(PlaceOpeningHoursResponse.fromView(view));
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
