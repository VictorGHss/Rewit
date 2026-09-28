package com.rewit.presentation.controller;

import com.rewit.application.dto.catalog.CatalogDtos.AddProductIdentifierCommand;
import com.rewit.application.dto.catalog.CatalogDtos.AssociateProductPresenceCommand;
import com.rewit.application.dto.catalog.CatalogDtos.CreateProductCommand;
import com.rewit.application.service.CatalogService;
import com.rewit.domain.model.Product;
import com.rewit.domain.model.ProductIdentifier;
import com.rewit.domain.model.ProductPresence;
import com.rewit.presentation.dto.catalog.CatalogPresentationDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.UUID;

/**
 * Controlador REST para gerenciamento e consulta de produtos globais, identificadores e presenças (Step 7).
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final CatalogService catalogService;

    public ProductController(CatalogService catalogService) {
        this.catalogService = Objects.requireNonNull(catalogService, "CatalogService must not be null");
    }

    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request) {
        CreateProductCommand cmd = new CreateProductCommand(
                request.name(),
                request.brand(),
                request.model(),
                request.description(),
                request.category(),
                request.imageUrl()
        );

        Product created = catalogService.createProduct(cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProductById(@PathVariable UUID id) {
        Product product = catalogService.getProductById(id);
        return ResponseEntity.ok(toResponse(product));
    }

    @PostMapping("/{id}/identifiers")
    public ResponseEntity<ProductIdentifierResponse> addProductIdentifier(
            @PathVariable UUID id,
            @Valid @RequestBody AddProductIdentifierRequest request
    ) {
        AddProductIdentifierCommand cmd = new AddProductIdentifierCommand(
                id,
                request.identifierType(),
                request.identifierValue()
        );

        ProductIdentifier identifier = catalogService.addProductIdentifier(cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ProductIdentifierResponse(
                identifier.getId(),
                identifier.getProductId(),
                identifier.getIdentifierType(),
                identifier.getIdentifierValue()
        ));
    }

    @PostMapping("/{id}/presence")
    public ResponseEntity<ProductPresenceResponse> associateProductPresence(
            @PathVariable UUID id,
            @Valid @RequestBody AssociateProductPresenceRequest request,
            Authentication authentication
    ) {
        UUID currentUserId = null;
        if (authentication != null && authentication.getName() != null) {
            try {
                currentUserId = UUID.fromString(authentication.getName());
            } catch (IllegalArgumentException ignored) {}
        }

        AssociateProductPresenceCommand cmd = new AssociateProductPresenceCommand(
                id,
                request.placeId(),
                currentUserId
        );

        ProductPresence presence = catalogService.associateProductToPlace(cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ProductPresenceResponse(
                presence.getId(),
                presence.getProductId(),
                presence.getPlaceId(),
                presence.getReportedByUserId(),
                presence.getVerificationStatus().name(),
                presence.getStatus()
        ));
    }

    private ProductResponse toResponse(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getBrand(),
                product.getModel(),
                product.getDescription(),
                product.getCategory(),
                product.getImageUrl(),
                product.getStatus()
        );
    }
}
