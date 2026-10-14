package com.rewit.presentation.controller;

import com.rewit.application.dto.catalog.PlaceAmenityDtos.AmenityView;
import com.rewit.application.usecase.GetPlaceAmenitiesUseCase;
import com.rewit.application.usecase.UpdatePlaceAmenitiesUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.catalog.PlaceAmenityPresentationDtos.AmenityCatalogResponse;
import com.rewit.presentation.dto.catalog.PlaceAmenityPresentationDtos.PlaceAmenitiesResponse;
import com.rewit.presentation.dto.catalog.PlaceAmenityPresentationDtos.UpdatePlaceAmenitiesRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Comodidades de estabelecimentos (F2.2): catálogo controlado, consulta e configuração pela empresa titular.
 */
@RestController
@RequestMapping("/api/v1/places")
@Tag(name = "Catálogo - Comodidades", description = "Catálogo e gestão de comodidades de estabelecimentos (F2.2)")
public class PlaceAmenitiesController {

    private final GetPlaceAmenitiesUseCase getPlaceAmenitiesUseCase;
    private final UpdatePlaceAmenitiesUseCase updatePlaceAmenitiesUseCase;

    public PlaceAmenitiesController(GetPlaceAmenitiesUseCase getPlaceAmenitiesUseCase,
                                    UpdatePlaceAmenitiesUseCase updatePlaceAmenitiesUseCase) {
        this.getPlaceAmenitiesUseCase = Objects.requireNonNull(getPlaceAmenitiesUseCase,
                "GetPlaceAmenitiesUseCase must not be null");
        this.updatePlaceAmenitiesUseCase = Objects.requireNonNull(updatePlaceAmenitiesUseCase,
                "UpdatePlaceAmenitiesUseCase must not be null");
    }

    /** GET /api/v1/places/amenity-options — códigos e rótulos aceitos pelo PUT. */
    @GetMapping("/amenity-options")
    @Operation(summary = "Listar o catálogo de comodidades", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<AmenityCatalogResponse> listAmenityOptions(Authentication authentication) {
        extractAuthenticatedUserId(authentication);
        return ResponseEntity.ok(AmenityCatalogResponse.fromViews(AmenityView.catalog()));
    }

    /** GET /api/v1/places/{placeId}/amenities — comodidades visíveis do local. */
    @GetMapping("/{placeId}/amenities")
    @Operation(summary = "Consultar comodidades de um local", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PlaceAmenitiesResponse> getAmenities(@PathVariable("placeId") UUID placeId,
                                                               Authentication authentication) {
        extractAuthenticatedUserId(authentication);
        return ResponseEntity.ok(PlaceAmenitiesResponse.fromView(getPlaceAmenitiesUseCase.execute(placeId)));
    }

    /** PUT /api/v1/places/{placeId}/amenities — substitui integralmente a seleção do local reivindicado. */
    @PutMapping("/{placeId}/amenities")
    @Operation(summary = "Configurar comodidades do estabelecimento", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PlaceAmenitiesResponse> updateAmenities(@PathVariable("placeId") UUID placeId,
                                                                  @Valid @RequestBody UpdatePlaceAmenitiesRequest request,
                                                                  Authentication authentication) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        return ResponseEntity.ok(PlaceAmenitiesResponse.fromView(
                updatePlaceAmenitiesUseCase.execute(request.toCommand(actorUserId, placeId))));
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
