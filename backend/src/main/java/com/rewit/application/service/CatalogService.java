package com.rewit.application.service;

import com.rewit.application.dto.catalog.CatalogDtos.AddProductIdentifierCommand;
import com.rewit.application.dto.catalog.CatalogDtos.AssociateProductPresenceCommand;
import com.rewit.application.dto.catalog.CatalogDtos.CreatePlaceCommand;
import com.rewit.application.dto.catalog.CatalogDtos.CreateProductCommand;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProductIdentifierRepository;
import com.rewit.application.port.ProductPresenceRepository;
import com.rewit.application.port.ProductRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.VerificationStatus;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Product;
import com.rewit.domain.model.ProductIdentifier;
import com.rewit.domain.model.ProductPresence;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Serviço de aplicação para operações fundamentais de catálogo (Lugares, Produtos, Identificadores e Presenças).
 */
@Service
public class CatalogService {

    private final PlaceRepository placeRepository;
    private final ProductRepository productRepository;
    private final ProductIdentifierRepository productIdentifierRepository;
    private final ProductPresenceRepository productPresenceRepository;

    public CatalogService(PlaceRepository placeRepository,
                          ProductRepository productRepository,
                          ProductIdentifierRepository productIdentifierRepository,
                          ProductPresenceRepository productPresenceRepository) {
        this.placeRepository = Objects.requireNonNull(placeRepository, "placeRepository must not be null");
        this.productRepository = Objects.requireNonNull(productRepository, "productRepository must not be null");
        this.productIdentifierRepository = Objects.requireNonNull(productIdentifierRepository, "productIdentifierRepository must not be null");
        this.productPresenceRepository = Objects.requireNonNull(productPresenceRepository, "productPresenceRepository must not be null");
    }

    @Transactional
    public Place createPlace(CreatePlaceCommand cmd) {
        Objects.requireNonNull(cmd, "CreatePlaceCommand cannot be null");

        String normalizedSlug = cmd.slug() != null ? cmd.slug().trim().toLowerCase() : "";
        if (placeRepository.existsBySlug(normalizedSlug)) {
            throw new BusinessException("Slug do local já está em uso", HttpStatus.CONFLICT, "PLACE_SLUG_ALREADY_EXISTS");
        }

        Place place = new Place(
                null,
                cmd.name(),
                normalizedSlug,
                cmd.category(),
                cmd.description(),
                cmd.addressText(),
                cmd.streetNumber(),
                cmd.neighborhood(),
                cmd.city(),
                cmd.state(),
                cmd.country(),
                cmd.latitude(),
                cmd.longitude(),
                cmd.validationRadiusMeters() != null ? cmd.validationRadiusMeters() : 50,
                cmd.origin(),
                false,
                cmd.claimedByBusinessId(),
                "ACTIVE"
        );

        return placeRepository.save(place);
    }

    @Transactional(readOnly = true)
    public Place getPlaceById(UUID id) {
        if (id == null) {
            throw new BusinessException("Identificador de local inválido", HttpStatus.BAD_REQUEST, "INVALID_PLACE_ID");
        }
        return placeRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));
    }

    @Transactional(readOnly = true)
    public Place getPlaceBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new BusinessException("Slug de local inválido", HttpStatus.BAD_REQUEST, "INVALID_PLACE_SLUG");
        }
        return placeRepository.findBySlug(slug)
                .orElseThrow(() -> new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));
    }

    @Transactional(readOnly = true)
    public List<Place> findNearbyPlaces(double latitude, double longitude, double radiusMeters) {
        if (radiusMeters <= 0) {
            throw new BusinessException("Raio de busca deve ser estritamente positivo", HttpStatus.BAD_REQUEST, "INVALID_SEARCH_RADIUS");
        }
        return placeRepository.findNearby(latitude, longitude, radiusMeters);
    }

    @Transactional
    public Product createProduct(CreateProductCommand cmd) {
        Objects.requireNonNull(cmd, "CreateProductCommand cannot be null");

        Product product = new Product(
                null,
                cmd.name(),
                cmd.brand(),
                cmd.model(),
                cmd.description(),
                cmd.category(),
                cmd.imageUrl(),
                "ACTIVE"
        );

        return productRepository.save(product);
    }

    @Transactional(readOnly = true)
    public Product getProductById(UUID id) {
        if (id == null) {
            throw new BusinessException("Identificador de produto inválido", HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_ID");
        }
        return productRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Produto não encontrado", HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
    }

    @Transactional
    public ProductIdentifier addProductIdentifier(AddProductIdentifierCommand cmd) {
        Objects.requireNonNull(cmd, "AddProductIdentifierCommand cannot be null");

        // Verifica existência do produto
        if (!productRepository.findById(cmd.productId()).isPresent()) {
            throw new BusinessException("Produto não encontrado para vinculação do código", HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
        }

        String type = cmd.identifierType() != null ? cmd.identifierType().trim().toUpperCase() : "";
        String value = cmd.identifierValue() != null ? cmd.identifierValue().trim() : "";

        if (productIdentifierRepository.existsByTypeAndValue(type, value)) {
            throw new BusinessException("Identificador de produto já cadastrado", HttpStatus.CONFLICT, "IDENTIFIER_ALREADY_EXISTS");
        }

        ProductIdentifier identifier = new ProductIdentifier(
                null,
                cmd.productId(),
                type,
                value
        );

        return productIdentifierRepository.save(identifier);
    }

    @Transactional(readOnly = true)
    public Optional<Product> findProductByIdentifier(String identifierType, String identifierValue) {
        if (identifierType == null || identifierValue == null) {
            return Optional.empty();
        }
        return productIdentifierRepository.findByTypeAndValue(identifierType, identifierValue)
                .flatMap(identifier -> productRepository.findById(identifier.getProductId()));
    }

    @Transactional
    public ProductPresence associateProductToPlace(AssociateProductPresenceCommand cmd) {
        Objects.requireNonNull(cmd, "AssociateProductPresenceCommand cannot be null");

        if (!productRepository.findById(cmd.productId()).isPresent()) {
            throw new BusinessException("Produto não encontrado", HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
        }
        if (!placeRepository.findById(cmd.placeId()).isPresent()) {
            throw new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND");
        }

        // Se já existe a presença, atualiza confirmação
        return productPresenceRepository.findByProductIdAndPlaceId(cmd.productId(), cmd.placeId())
                .map(existing -> {
                    productPresenceRepository.save(existing);
                    return existing;
                })
                .orElseGet(() -> {
                    ProductPresence presence = new ProductPresence(
                            null,
                            cmd.productId(),
                            cmd.placeId(),
                            cmd.reportedByUserId(),
                            VerificationStatus.UNCONFIRMED,
                            "AVAILABLE"
                    );
                    return productPresenceRepository.save(presence);
                });
    }

    @Transactional(readOnly = true)
    public List<ProductPresence> getProductsInPlace(UUID placeId) {
        if (placeId == null) {
            return List.of();
        }
        return productPresenceRepository.findByPlaceId(placeId);
    }
}
