package com.rewit.application.service;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.ProductIdentifierRepository;
import com.rewit.application.port.ProductRepository;
import com.rewit.application.port.RateLimiter;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.Product;
import com.rewit.domain.model.ProductIdentifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Leituras públicas de descoberta de produtos (C5.3): identificadores do produto, lookup por código e produtos de
 * um local. Mesma política de status do detalhe (C5.2), aplicada pelo {@link CatalogService}: só place/product
 * {@code ACTIVE}; indisponível responde o mesmo 404 de inexistente.
 */
@Service
public class ProductDiscoveryService {

    /** Tamanho da coluna identifier_value (V1): valor maior não existe gravado. */
    static final int MAX_IDENTIFIER_VALUE_LENGTH = 128;

    /** Códigos comerciais: dígitos, letras (o "X" do ISBN) e hífen. */
    private static final Pattern IDENTIFIER_VALUE = Pattern.compile("^[0-9A-Za-z-]+$");

    private final CatalogService catalogService;
    private final ProductRepository productRepository;
    private final ProductIdentifierRepository productIdentifierRepository;
    private final RateLimiter rateLimiter;

    public ProductDiscoveryService(CatalogService catalogService,
                                   ProductRepository productRepository,
                                   ProductIdentifierRepository productIdentifierRepository,
                                   RateLimiter rateLimiter) {
        this.catalogService = Objects.requireNonNull(catalogService, "CatalogService must not be null");
        this.productRepository = Objects.requireNonNull(productRepository, "ProductRepository must not be null");
        this.productIdentifierRepository = Objects.requireNonNull(productIdentifierRepository,
                "ProductIdentifierRepository must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "RateLimiter must not be null");
    }

    /** Identificadores públicos (EAN, UPC, GTIN, ISBN) de um produto ACTIVE, por tipo e valor. */
    @Transactional(readOnly = true)
    public List<ProductIdentifier> getPublicIdentifiers(UUID productId) {
        Product product = catalogService.getProductById(productId);
        return productIdentifierRepository.findByProductIdAndTypes(product.getId(), ProductIdentifier.PUBLIC_TYPES);
    }

    /**
     * Produto ACTIVE pelo código. Código inexistente e produto indisponível são o mesmo 404. Consome o limite de
     * buscas do usuário (SEARCH): um lookup por código é uma consulta ao catálogo e permitiria enumerar códigos.
     */
    @Transactional(readOnly = true)
    public Product findByIdentifier(UUID requesterUserId, String identifierType, String identifierValue) {
        Objects.requireNonNull(requesterUserId, "requesterUserId must not be null");
        String type = identifierType == null ? "" : identifierType.trim().toUpperCase(Locale.ROOT);
        String value = identifierValue == null ? "" : identifierValue.trim();
        if (!ProductIdentifier.PUBLIC_TYPES.contains(type)) {
            throw new BusinessException("Tipo de identificador não suportado (EAN, UPC, GTIN, ISBN)",
                    HttpStatus.BAD_REQUEST, "INVALID_IDENTIFIER_TYPE");
        }
        if (value.isEmpty() || value.length() > MAX_IDENTIFIER_VALUE_LENGTH || !IDENTIFIER_VALUE.matcher(value).matches()) {
            throw new BusinessException("Código de identificador inválido", HttpStatus.BAD_REQUEST, "INVALID_IDENTIFIER_VALUE");
        }

        rateLimiter.acquireOrThrow(RateLimitedAction.SEARCH, RateLimitSubject.ofUser(requesterUserId));

        return catalogService.findProductByIdentifier(type, value)
                .filter(product -> CatalogService.PUBLIC_CATALOG_STATUS.equals(product.getStatus()))
                .orElseThrow(() -> new BusinessException("Produto não encontrado", HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
    }

    /** Produtos ACTIVE com presença no local ACTIVE, paginados por nome e id; sem dados da presença. */
    @Transactional(readOnly = true)
    public PageResult<Product> getProductsInPlace(UUID placeId, int page, int size) {
        if (page < 0) {
            throw new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        if (size < 1 || size > 50) {
            throw new BusinessException("O tamanho da página deve estar entre 1 e 50", HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE");
        }
        catalogService.getPlaceById(placeId);
        return productRepository.findActiveByPlace(placeId, page, size);
    }
}
