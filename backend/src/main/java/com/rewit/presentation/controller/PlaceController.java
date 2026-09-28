package com.rewit.presentation.controller;

import com.rewit.application.dto.catalog.CatalogDtos.CreatePlaceCommand;
import com.rewit.application.service.CatalogService;
import com.rewit.domain.model.Place;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.CreatePlaceRequest;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.PlaceResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.UUID;

/**
 * Controlador REST para gerenciamento e consulta de locais físicos (Place) no catálogo (Step 7).
 */
@RestController
@RequestMapping("/api/v1/places")
public class PlaceController {

    private final CatalogService catalogService;

    public PlaceController(CatalogService catalogService) {
        this.catalogService = Objects.requireNonNull(catalogService, "CatalogService must not be null");
    }

    @PostMapping
    public ResponseEntity<PlaceResponse> createPlace(@Valid @RequestBody CreatePlaceRequest request) {
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

        Place created = catalogService.createPlace(cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlaceResponse> getPlaceById(@PathVariable UUID id) {
        Place place = catalogService.getPlaceById(id);
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
}
