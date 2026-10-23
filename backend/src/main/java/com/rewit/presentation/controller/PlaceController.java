package com.rewit.presentation.controller;

import com.rewit.application.dto.catalog.CatalogDtos.CreatePlaceCommand;
import com.rewit.application.dto.catalog.CatalogDtos.ExternalReferenceInput;
import com.rewit.application.dto.catalog.CatalogDtos.NearbyPlaceResult;
import com.rewit.application.dto.catalog.CatalogDtos.PlaceAdoptionResult;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.media.MediaDtos.MediaDownloadResult;
import com.rewit.application.dto.media.MediaDtos.PlaceMediaView;
import com.rewit.application.dto.media.MediaDtos.UploadPlaceMediaCommand;
import com.rewit.application.service.CatalogService;
import com.rewit.application.service.PlaceMediaService;
import com.rewit.application.service.ProductDiscoveryService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceMedia;
import com.rewit.domain.model.Product;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.AdoptPlaceRequest;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.CreatePlaceMediaReportRequest;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.CreatePlaceRequest;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.NearbyPlaceItemResponse;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.NearbyPlacesResponse;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.PlaceMediaResponse;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.PlaceResponse;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.ProductResponse;
import com.rewit.presentation.dto.common.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
    private final ProductDiscoveryService productDiscoveryService;
    private final PlaceMediaService placeMediaService;

    public PlaceController(CatalogService catalogService, ProductDiscoveryService productDiscoveryService) {
        this(catalogService, productDiscoveryService, null);
    }

    @Autowired
    public PlaceController(CatalogService catalogService,
                           ProductDiscoveryService productDiscoveryService,
                           PlaceMediaService placeMediaService) {
        this.catalogService = Objects.requireNonNull(catalogService, "CatalogService must not be null");
        this.productDiscoveryService = Objects.requireNonNull(productDiscoveryService,
                "ProductDiscoveryService must not be null");
        this.placeMediaService = placeMediaService;
    }

    @PostMapping
    public ResponseEntity<PlaceResponse> createPlace(
            @Valid @RequestBody CreatePlaceRequest request,
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        CreatePlaceCommand cmd = new CreatePlaceCommand(
                request.name(),
                request.slug() != null && !request.slug().isBlank() ? request.slug().trim() : null,
                request.category() != null && !request.category().isBlank() ? request.category().trim() : "GERAL",
                request.description(),
                request.addressText(),
                request.streetNumber(),
                request.neighborhood(),
                request.city() != null && !request.city().isBlank() ? request.city().trim() : null,
                request.state() != null && !request.state().isBlank() ? request.state().trim() : null,
                request.country() != null && !request.country().isBlank() ? request.country().trim() : "BR",
                request.latitude(),
                request.longitude(),
                request.validationRadiusMeters(),
                "USER",
                null,
                actorUserId,
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

    @PostMapping(value = "/{id}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PlaceMediaResponse> uploadPlaceMedia(
            @PathVariable("id") UUID placeId,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        if (placeMediaService == null) {
            throw new BusinessException("Serviço de mídia de local indisponível", HttpStatus.INTERNAL_SERVER_ERROR, "SERVICE_UNAVAILABLE");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("O arquivo de mídia não pode ser vazio", HttpStatus.BAD_REQUEST, "EMPTY_MEDIA_FILE");
        }
        if (file.getSize() > PlaceMedia.MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(
                    "O tamanho do arquivo excede o limite máximo permitido de 10 MB",
                    HttpStatus.CONTENT_TOO_LARGE,
                    "MEDIA_SIZE_EXCEEDED"
            );
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("Falha ao ler os bytes do arquivo enviado", HttpStatus.BAD_REQUEST, "FILE_READ_ERROR");
        }

        UploadPlaceMediaCommand command = new UploadPlaceMediaCommand(
                placeId,
                actorUserId,
                bytes,
                file.getOriginalFilename(),
                idempotencyKey
        );

        PlaceMediaView view = placeMediaService.uploadMedia(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(PlaceMediaResponse.fromView(view));
    }

    @PutMapping("/{id}/media/{mediaId}/primary")
    public ResponseEntity<PlaceMediaResponse> setPrimaryPlaceMedia(
            @PathVariable("id") UUID placeId,
            @PathVariable("mediaId") UUID mediaId,
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        if (placeMediaService == null) {
            throw new BusinessException("Serviço de mídia de local indisponível", HttpStatus.INTERNAL_SERVER_ERROR, "SERVICE_UNAVAILABLE");
        }
        PlaceMediaView view = placeMediaService.setPrimaryMedia(actorUserId, placeId, mediaId);
        return ResponseEntity.ok(PlaceMediaResponse.fromView(view));
    }

    @DeleteMapping("/{id}/media/{mediaId}")
    public ResponseEntity<Void> deletePlaceMedia(
            @PathVariable("id") UUID placeId,
            @PathVariable("mediaId") UUID mediaId,
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        if (placeMediaService == null) {
            throw new BusinessException("Serviço de mídia de local indisponível", HttpStatus.INTERNAL_SERVER_ERROR, "SERVICE_UNAVAILABLE");
        }
        placeMediaService.deleteMedia(actorUserId, placeId, mediaId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/media/{mediaId}/reports")
    public ResponseEntity<Void> reportPlaceMedia(
            @PathVariable("id") UUID placeId,
            @PathVariable("mediaId") UUID mediaId,
            @Valid @RequestBody CreatePlaceMediaReportRequest request,
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        if (placeMediaService == null) {
            throw new BusinessException("Serviço de mídia de local indisponível", HttpStatus.INTERNAL_SERVER_ERROR, "SERVICE_UNAVAILABLE");
        }
        placeMediaService.reportMedia(actorUserId, placeId, mediaId, request.reason(), request.detail());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/{id}/media")
    public ResponseEntity<List<PlaceMediaResponse>> getPlaceMedia(@PathVariable("id") UUID placeId) {
        if (placeMediaService == null) {
            return ResponseEntity.ok(List.of());
        }
        List<PlaceMediaResponse> responses = placeMediaService.getMediaByPlaceId(placeId)
                .stream()
                .map(PlaceMediaResponse::fromView)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{id}/media/{mediaId}")
    public ResponseEntity<byte[]> downloadPlaceMedia(
            @PathVariable("id") UUID placeId,
            @PathVariable("mediaId") UUID mediaId
    ) {
        if (placeMediaService == null) {
            throw new BusinessException("Fotografia não encontrada", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND");
        }
        MediaDownloadResult result = placeMediaService.downloadMedia(placeId, mediaId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.mimeType()))
                .contentLength(result.bytes().length)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-cache, no-store, must-revalidate")
                .body(result.bytes());
    }

    @GetMapping("/{id}/products")
    public ResponseEntity<PagedResponse<ProductResponse>> getProductsInPlace(
            @PathVariable UUID id,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        PageResult<Product> result = productDiscoveryService.getProductsInPlace(id, page, size);
        return ResponseEntity.ok(new PagedResponse<>(
                result.content().stream().map(ProductResponse::fromDomain).toList(),
                result.pageNumber(),
                result.pageSize(),
                result.totalElements(),
                result.totalPages(),
                result.isLast()
        ));
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
        String photoUrl = placeMediaService != null ? placeMediaService.findPrimaryPhotoUrl(place.getId()) : null;
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
                place.getStatus(),
                photoUrl
        );
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || "anonymousUser".equalsIgnoreCase(authentication.getName())) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Identificador de usuário inválido", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }
}
