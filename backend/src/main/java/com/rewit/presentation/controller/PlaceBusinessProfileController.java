package com.rewit.presentation.controller;

import com.rewit.application.service.PlaceMediaService;
import com.rewit.application.usecase.UpdatePlaceBusinessProfileUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.Place;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.PlaceResponse;
import com.rewit.presentation.dto.catalog.PlaceBusinessProfilePresentationDtos.UpdatePlaceBusinessProfileRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Perfil comercial de estabelecimentos reivindicados: descrição e contatos públicos, editados pela empresa titular.
 * A leitura é a do próprio local ({@code GET /api/v1/places/{id}}).
 */
@RestController
@RequestMapping("/api/v1/places")
@Tag(name = "Catálogo - Perfil Comercial", description = "Descrição e contatos públicos de estabelecimentos")
public class PlaceBusinessProfileController {

    private final UpdatePlaceBusinessProfileUseCase updateBusinessProfileUseCase;
    private final PlaceMediaService placeMediaService;

    public PlaceBusinessProfileController(UpdatePlaceBusinessProfileUseCase updateBusinessProfileUseCase,
                                          PlaceMediaService placeMediaService) {
        this.updateBusinessProfileUseCase = Objects.requireNonNull(updateBusinessProfileUseCase,
                "UpdatePlaceBusinessProfileUseCase must not be null");
        this.placeMediaService = Objects.requireNonNull(placeMediaService, "PlaceMediaService must not be null");
    }

    /** PUT /api/v1/places/{placeId}/business-profile — substitui descrição e contatos do local reivindicado. */
    @PutMapping("/{placeId}/business-profile")
    @Operation(summary = "Atualizar descrição e contatos do estabelecimento",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PlaceResponse> updateBusinessProfile(
            @PathVariable("placeId") UUID placeId,
            @Valid @RequestBody UpdatePlaceBusinessProfileRequest request,
            Authentication authentication) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        Place updated = updateBusinessProfileUseCase.execute(request.toCommand(actorUserId, placeId));
        return ResponseEntity.ok(PlaceResponse.of(updated, placeMediaService.findPrimaryPhotoUrl(updated.getId())));
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Identificador de usuário inválido na sessão", HttpStatus.UNAUTHORIZED,
                    "UNAUTHORIZED");
        }
    }
}
