package com.rewit.presentation.controller;

import com.rewit.application.dto.catalog.CatalogDtos.CreatePlaceCommand;
import com.rewit.application.dto.catalog.CatalogDtos.ExternalReferenceInput;
import com.rewit.application.dto.catalog.CatalogDtos.NearbyPlaceResult;
import com.rewit.application.dto.catalog.CatalogDtos.PlaceAdoptionResult;
import com.rewit.application.service.CatalogService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.Place;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.AdoptPlaceRequest;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.CreatePlaceRequest;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.NearbyPlaceItemResponse;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.NearbyPlacesResponse;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.PlaceResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Controlador REST para gerenciamento e consulta de locais físicos (Place) no catálogo (Steps 7 e 9.4).
 */
@RestController
@RequestMapping("/api/v1/places")
public class PlaceController {

    private final CatalogService catalogService;

    public PlaceController(CatalogService catalogService) {
        this.catalogService = Objects.requireNonNull(catalogService, "CatalogService must not be null");
    }

    @PostMapping
    public ResponseEntity<PlaceResponse> createPlace(
            @Valid @RequestBody CreatePlaceRequest request,
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        CreatePlaceCommand cmd = new CreatePlaceCommand(
                request.name(),
                request.slug(),
                request.category(),
                request.description(),
                request.addressText(),
                request.streetNumber(),
                request.neighborhood(),
                request.city(),
                request.state(),
                request.country() != null ? request.country() : "BR",
                request.latitude(),
                request.longitude(),
                request.validationRadiusMeters(),
                "USER",
                null
        );

        Place created = catalogService.createPlace(actorUserId, cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @PostMapping("/adopt")
    public ResponseEntity<PlaceResponse> adoptPlace(
            @Valid @RequestBody AdoptPlaceRequest request,
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);

        ExternalReferenceInput extInput = null;
        if (request.externalReference() != null) {
            extInput = new ExternalReferenceInput(
                    request.externalReference().provider(),
                    request.externalReference().externalId()
            );
        }

        CreatePlaceCommand cmd = new CreatePlaceCommand(
                request.name(),
                request.slug(),
                request.category(),
                request.description(),
                request.addressText(),
                request.streetNumber(),
                request.neighborhood(),
                request.city(),
                request.state(),
                request.country() != null ? request.country() : "BR",
                request.latitude(),
                request.longitude(),
                request.validationRadiusMeters(),
                "USER",
                null,
                extInput
        );

        PlaceAdoptionResult result = catalogService.adoptPlace(actorUserId, cmd);
        PlaceResponse response = toResponse(result.place());

        if (result.newlyCreated()) {
            URI location = URI.create("/api/v1/places/" + result.place().getId());
            return ResponseEntity.created(location).body(response);
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/nearby")
    public ResponseEntity<NearbyPlacesResponse> getNearbyPlaces(
            @RequestParam(name = "latitude", required = false) Double latitude,
            @RequestParam(name = "longitude", required = false) Double longitude,
            @RequestParam(name = "radiusMeters", required = false) Double radiusMeters,
            @RequestParam(name = "limit", required = false, defaultValue = "20") Integer limit
    ) {
        int effectiveLimit = limit != null ? limit : 20;
        List<NearbyPlaceResult> results = catalogService.findNearbyPlaces(
                latitude,
                longitude,
                radiusMeters,
                effectiveLimit
        );

        List<NearbyPlaceItemResponse> items = results.stream()
                .map(r -> new NearbyPlaceItemResponse(
                        r.place().getId(),
                        r.place().getName(),
                        r.place().getSlug(),
                        r.place().getCategory(),
                        r.place().getDescription(),
                        r.place().getAddressText(),
                        r.place().getStreetNumber(),
                        r.place().getNeighborhood(),
                        r.place().getCity(),
                        r.place().getState(),
                        r.place().getCountry(),
                        r.place().getLatitude(),
                        r.place().getLongitude(),
                        r.place().getValidationRadiusMeters(),
                        r.place().getOrigin(),
                        r.place().isVerified(),
                        r.place().getStatus(),
                        r.distanceMeters()
                ))
                .toList();

        return ResponseEntity.ok(new NearbyPlacesResponse(items, effectiveLimit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlaceResponse> getPlaceById(@PathVariable UUID id) {
        Place place = catalogService.getPlaceById(id);
        return ResponseEntity.ok(toResponse(place));
    }

    @GetMapping("/external/{provider}/{externalId}")
    public ResponseEntity<PlaceResponse> getPlaceByExternalReference(
            @PathVariable String provider,
            @PathVariable String externalId
    ) {
        Place place = catalogService.getPlaceByExternalReference(provider, externalId);
        return ResponseEntity.ok(toResponse(place));
    }

    private PlaceResponse toResponse(Place place) {
        return new PlaceResponse(
                place.getId(),
                place.getName(),
                place.getSlug(),
                place.getCategory(),
                place.getDescription(),
                place.getAddressText(),
                place.getStreetNumber(),
                place.getNeighborhood(),
                place.getCity(),
                place.getState(),
                place.getCountry(),
                place.getLatitude(),
                place.getLongitude(),
                place.getValidationRadiusMeters(),
                place.getOrigin(),
                place.isVerified(),
                place.getStatus()
        );
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return UUID.fromString(authentication.getName());
    }
}
