package com.rewit.presentation.controller;

import com.rewit.application.service.PlaceDiscoveryService;
import com.rewit.domain.model.PlaceCandidate;
import com.rewit.presentation.dto.discovery.PlaceCandidateResponse;
import com.rewit.presentation.dto.discovery.PlaceDiscoverySearchRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

/**
 * Controlador REST para descoberta externa de locais (Google Places New API) - Step 8.
 * Endpoints protegidos por JWT. A descoberta é apenas informativa e não persiste candidatos.
 */
@RestController
@RequestMapping("/api/v1/places/discovery")
public class PlaceDiscoveryController {

    private final PlaceDiscoveryService placeDiscoveryService;

    public PlaceDiscoveryController(PlaceDiscoveryService placeDiscoveryService) {
        this.placeDiscoveryService = Objects.requireNonNull(placeDiscoveryService, "PlaceDiscoveryService must not be null");
    }

    /**
     * Endpoint protegido de busca textual de candidatos externos.
     * GET /api/v1/places/discovery/search?query=...&latitude=...&longitude=...&radius=...&limit=...
     */
    @GetMapping("/search")
    public ResponseEntity<List<PlaceCandidateResponse>> searchCandidates(
            @Valid @ModelAttribute PlaceDiscoverySearchRequest request
    ) {
        List<PlaceCandidate> candidates = placeDiscoveryService.searchCandidates(
                request.query(),
                request.latitude(),
                request.longitude(),
                request.radius(),
                request.limit()
        );

        List<PlaceCandidateResponse> responseList = candidates.stream()
                .map(PlaceCandidateResponse::fromDomain)
                .toList();

        return ResponseEntity.ok(responseList);
    }

    /**
     * Endpoint protegido de consulta de detalhes de candidato externo por provedor e externalId.
     * GET /api/v1/places/discovery/{provider}/{externalId}
     */
    @GetMapping("/{provider}/{externalId}")
    public ResponseEntity<PlaceCandidateResponse> getCandidateDetails(
            @PathVariable String provider,
            @PathVariable String externalId
    ) {
        PlaceCandidate candidate = placeDiscoveryService.getCandidateDetails(provider, externalId);
        return ResponseEntity.ok(PlaceCandidateResponse.fromDomain(candidate));
    }
}
