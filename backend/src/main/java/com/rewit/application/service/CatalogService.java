package com.rewit.application.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.rewit.application.dto.catalog.CatalogDtos.AddProductIdentifierCommand;
import com.rewit.application.dto.catalog.CatalogDtos.AssociateProductPresenceCommand;
import com.rewit.application.dto.catalog.CatalogDtos.CreatePlaceCommand;
import com.rewit.application.dto.catalog.CatalogDtos.CreateProductCommand;
import com.rewit.application.dto.catalog.CatalogDtos.NearbyPlaceResult;
import com.rewit.application.dto.catalog.CatalogDtos.PlaceAdoptionResult;
import com.rewit.application.port.PlaceExternalReferenceRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProductIdentifierRepository;
import com.rewit.application.port.ProductPresenceRepository;
import com.rewit.application.port.ProductRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.VerificationStatus;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceExternalReference;
import com.rewit.domain.model.Product;
import com.rewit.domain.model.ProductIdentifier;
import com.rewit.domain.model.ProductPresence;

/**
 * Serviço de aplicação para operações fundamentais de catálogo (Lugares, Produtos, Identificadores e Presenças).
 */
@Service
public class CatalogService {

    /** Único status de place/product exposto em leituras públicas (busca e detalhe). */
    public static final String PUBLIC_CATALOG_STATUS = "ACTIVE";

    private final PlaceRepository placeRepository;
    private final ProductRepository productRepository;
    private final ProductIdentifierRepository productIdentifierRepository;
    private final ProductPresenceRepository productPresenceRepository;
    private final PlaceExternalReferenceRepository placeExternalReferenceRepository;
    private final TransactionOperations transactionOperations;
    private final AccountStatusPolicy accountStatusPolicy;
    private final UserRepository userRepository;

    @Autowired
    public CatalogService(PlaceRepository placeRepository,
                          ProductRepository productRepository,
                          ProductIdentifierRepository productIdentifierRepository,
                          ProductPresenceRepository productPresenceRepository,
                          PlaceExternalReferenceRepository placeExternalReferenceRepository,
                          PlatformTransactionManager transactionManager,
                          AccountStatusPolicy accountStatusPolicy,
                          UserRepository userRepository) {
        this(
                placeRepository,
                productRepository,
                productIdentifierRepository,
                productPresenceRepository,
                placeExternalReferenceRepository,
                createTransactionOperations(transactionManager),
                accountStatusPolicy,
                userRepository
        );
    }

    private static TransactionOperations createTransactionOperations(PlatformTransactionManager transactionManager) {
        if (transactionManager == null) {
            return TransactionOperations.withoutTransaction();
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    public CatalogService(PlaceRepository placeRepository,
                          ProductRepository productRepository,
                          ProductIdentifierRepository productIdentifierRepository,
                          ProductPresenceRepository productPresenceRepository,
                          PlaceExternalReferenceRepository placeExternalReferenceRepository,
                          TransactionOperations transactionOperations,
                          AccountStatusPolicy accountStatusPolicy,
                          UserRepository userRepository) {
        this.placeRepository = Objects.requireNonNull(placeRepository, "placeRepository must not be null");
        this.productRepository = Objects.requireNonNull(productRepository, "productRepository must not be null");
        this.productIdentifierRepository = Objects.requireNonNull(productIdentifierRepository, "productIdentifierRepository must not be null");
        this.productPresenceRepository = Objects.requireNonNull(productPresenceRepository, "productPresenceRepository must not be null");
        this.placeExternalReferenceRepository = Objects.requireNonNull(placeExternalReferenceRepository, "placeExternalReferenceRepository must not be null");
        this.transactionOperations = transactionOperations != null ? transactionOperations : TransactionOperations.withoutTransaction();
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "accountStatusPolicy must not be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
    }

    public CatalogService(PlaceRepository placeRepository,
                          ProductRepository productRepository,
                          ProductIdentifierRepository productIdentifierRepository,
                          ProductPresenceRepository productPresenceRepository,
                          PlaceExternalReferenceRepository placeExternalReferenceRepository,
                          AccountStatusPolicy accountStatusPolicy,
                          UserRepository userRepository) {
        this(
                placeRepository,
                productRepository,
                productIdentifierRepository,
                productPresenceRepository,
                placeExternalReferenceRepository,
                TransactionOperations.withoutTransaction(),
                accountStatusPolicy,
                userRepository
        );
    }

    // ---------------------------------------------------------------------------------------------------------
    // Mutações iniciadas por um usuário. A conta do ator precisa estar operacional (C2): o access token é stateless
    // e pode ser anterior a uma desativação, suspensão ou exclusão. As variantes sem ator são o núcleo dessas
    // operações e ficam package-private, para que nenhuma camada externa as chame sem essa verificação.
    // ---------------------------------------------------------------------------------------------------------

    public Place createPlace(UUID actorUserId, CreatePlaceCommand cmd) {
        requireOperationalActor(actorUserId);
        return createPlace(cmd);
    }

    /** A adoção controla as próprias transações (REQUIRES_NEW por tentativa); a verificação vem antes de todas. */
    public PlaceAdoptionResult adoptPlace(UUID actorUserId, CreatePlaceCommand cmd) {
        requireOperationalActor(actorUserId);
        return adoptPlace(cmd);
    }

    @Transactional
    public Product createProduct(UUID actorUserId, CreateProductCommand cmd) {
        requireOperationalActor(actorUserId);
        return createProduct(cmd);
    }

    @Transactional
    public ProductIdentifier addProductIdentifier(UUID actorUserId, AddProductIdentifierCommand cmd) {
        requireOperationalActor(actorUserId);
        return addProductIdentifier(cmd);
    }

    /**
     * A presença é atribuída sempre ao ator, nunca a um usuário informado no comando. Se a presença já existia, a
     * resposta traz quem a relatou primeiro; quando essa conta está excluída, o relato sai sem autor (C2: a
     * identidade de uma conta DELETED não aparece em leituras públicas), sem alterar o que está gravado.
     */
    @Transactional
    public ProductPresence associateProductToPlace(UUID actorUserId, AssociateProductPresenceCommand cmd) {
        requireOperationalActor(actorUserId);
        Objects.requireNonNull(cmd, "AssociateProductPresenceCommand cannot be null");
        ProductPresence presence = associateProductToPlace(
                new AssociateProductPresenceCommand(cmd.productId(), cmd.placeId(), actorUserId));
        return withoutDeletedReporter(presence);
    }

    private ProductPresence withoutDeletedReporter(ProductPresence presence) {
        UUID reporter = presence.getReportedByUserId();
        if (reporter == null || !userRepository.findDeletedUserIds(Set.of(reporter)).contains(reporter)) {
            return presence;
        }
        // Projeção para a resposta, não persistida
        return new ProductPresence(presence.getId(), presence.getProductId(), presence.getPlaceId(), null,
                presence.getVerificationStatus(), presence.getStatus());
    }

    private void requireOperationalActor(UUID actorUserId) {
        if (actorUserId == null) {
            throw AccountStatusPolicy.accountDisabled();
        }
        accountStatusPolicy.requireOperational(actorUserId);
    }

    Place createPlace(CreatePlaceCommand cmd) {
        return adoptPlace(cmd).place();
    }

    PlaceAdoptionResult adoptPlace(CreatePlaceCommand cmd) {
        Objects.requireNonNull(cmd, "CreatePlaceCommand cannot be null");

        if (cmd.name() == null || cmd.name().isBlank()) {
            throw new BusinessException("O nome do local é obrigatório", HttpStatus.BAD_REQUEST, "INVALID_PLACE_NAME");
        }

        boolean isExplicitSlug = cmd.slug() != null && !cmd.slug().isBlank();
        boolean hasExternalRef = cmd.externalReference() != null;

        String provider = null;
        String externalId = null;

        if (hasExternalRef) {
            provider = cmd.externalReference().provider();
            externalId = cmd.externalReference().externalId();

            if (provider == null || provider.isBlank() || externalId == null || externalId.isBlank()) {
                throw new BusinessException("Provedor e identificador externo são obrigatórios na referência externa",
                        HttpStatus.BAD_REQUEST, "INVALID_EXTERNAL_REFERENCE");
            }

            // Checagem prévia de idempotência
            Optional<PlaceExternalReference> existingRef = placeExternalReferenceRepository.findByProviderAndExternalId(provider, externalId);
            if (existingRef.isPresent()) {
                Place existingPlace = placeRepository.findById(existingRef.get().getPlaceId())
                        .orElseThrow(() -> new BusinessException("Local associado à referência externa não encontrado",
                                HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));
                return new PlaceAdoptionResult(existingPlace, false);
            }
        }

        final String finalProvider = provider;
        final String finalExternalId = externalId;

        // 1. Caso com Slug Explícito fornecido pelo usuário/cliente
        if (isExplicitSlug) {
            String normalizedRequestedSlug = generateSlug(cmd.slug());
            if (placeRepository.existsBySlug(normalizedRequestedSlug)) {
                throw new BusinessException("Slug do local já está em uso", HttpStatus.CONFLICT, "PLACE_SLUG_ALREADY_EXISTS");
            }

            try {
                Place saved = transactionOperations.execute(status ->
                        executePersistPlace(cmd, normalizedRequestedSlug, finalProvider, finalExternalId));
                return new PlaceAdoptionResult(saved, true);
            } catch (DataIntegrityViolationException ex) {
                // A transação física anterior sofreu rollback completo.
                // 1.1 Concorrência na referência externa:
                // Se a referência externa foi adotada e comitada simultaneamente por outra thread vencedora,
                // recupera o Place vencedor e converge com sucesso.
                if (hasExternalRef) {
                    Optional<PlaceExternalReference> recoveredRef = placeExternalReferenceRepository.findByProviderAndExternalId(finalProvider, finalExternalId);
                    if (recoveredRef.isPresent()) {
                        Place winnerPlace = placeRepository.findById(recoveredRef.get().getPlaceId())
                                .orElseThrow(() -> new BusinessException("Local associado à referência externa não encontrado",
                                        HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));
                        return new PlaceAdoptionResult(winnerPlace, false);
                    }
                }

                // 1.2 Concorrência no slug explícito (uq_places_slug):
                // Duas requisições simultâneas com o mesmo slug explícito para locais diferentes.
                // A perdedora não deve auto-gerar sufixo, mas retornar 409 Conflict (PLACE_SLUG_ALREADY_EXISTS).
                if (isConstraintViolation(ex, "uq_places_slug")) {
                    throw new BusinessException("Slug do local já está em uso", HttpStatus.CONFLICT, "PLACE_SLUG_ALREADY_EXISTS");
                }

                // 1.3 Qualquer outra violação de integridade não deve ser mascarada
                throw ex;
            }
        }

        // 2. Caso com Slug Automático (slug omitido ou vazio)
        String baseSlug = generateSlug(cmd.name());
        String currentCandidateSlug = baseSlug;
        int suffixCounter = 1;

        // Pré-avança pelo catálogo existente para evitar tentativas em slugs já conhecidamente ocupados
        while (placeRepository.existsBySlug(currentCandidateSlug)) {
            suffixCounter++;
            currentCandidateSlug = baseSlug + "-" + suffixCounter;
        }

        int maxAttempts = 15;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            final String slugToTry = currentCandidateSlug;

            try {
                Place saved = transactionOperations.execute(status ->
                        executePersistPlace(cmd, slugToTry, finalProvider, finalExternalId));
                return new PlaceAdoptionResult(saved, true);
            } catch (DataIntegrityViolationException ex) {
                // A transação física anterior que sofreu colisão sofreu rollback completo.
                // 2.1 Concorrência na referência externa:
                if (hasExternalRef) {
                    Optional<PlaceExternalReference> recoveredRef = placeExternalReferenceRepository.findByProviderAndExternalId(finalProvider, finalExternalId);
                    if (recoveredRef.isPresent()) {
                        Place winnerPlace = placeRepository.findById(recoveredRef.get().getPlaceId())
                                .orElseThrow(() -> new BusinessException("Local associado à referência externa não encontrado",
                                        HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));
                        return new PlaceAdoptionResult(winnerPlace, false);
                    }
                }

                // 2.2 Concorrência no slug gerado (uq_places_slug):
                // Outra transação comitou o mesmo slug simultaneamente para um local diferente.
                // Avança deterministamente para o próximo sufixo (-2, -3, etc.) e tenta em nova transação física.
                if (isConstraintViolation(ex, "uq_places_slug")) {
                    suffixCounter++;
                    currentCandidateSlug = baseSlug + "-" + suffixCounter;
                    while (placeRepository.existsBySlug(currentCandidateSlug)) {
                        suffixCounter++;
                        currentCandidateSlug = baseSlug + "-" + suffixCounter;
                    }
                    continue;
                }

                // 2.3 Qualquer outra violação de integridade não deve ser mascarada
                throw ex;
            }
        }

        throw new BusinessException("Não foi possível gerar um slug único para o local após múltiplas tentativas",
                HttpStatus.CONFLICT, "PLACE_SLUG_GENERATION_FAILED");
    }

    @Transactional(readOnly = true)
    public Place getPlaceByExternalReference(String provider, String externalId) {
        if (provider == null || provider.isBlank() || externalId == null || externalId.isBlank()) {
            throw new BusinessException("Provedor e identificador externo são obrigatórios",
                    HttpStatus.BAD_REQUEST, "INVALID_EXTERNAL_REFERENCE");
        }
        PlaceExternalReference ref = placeExternalReferenceRepository.findByProviderAndExternalId(provider, externalId)
                .orElseThrow(() -> new BusinessException("Referência externa de local não encontrada",
                        HttpStatus.NOT_FOUND, "PLACE_EXTERNAL_REFERENCE_NOT_FOUND"));
        return placeRepository.findById(ref.getPlaceId())
                .orElseThrow(() -> new BusinessException("Local associado à referência externa não encontrado",
                        HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));
    }

    @Transactional(readOnly = true)
    public List<NearbyPlaceResult> findNearbyPlaces(Double latitude, Double longitude, Double radiusMeters, Integer limit) {
        if (latitude == null || Double.isNaN(latitude) || Double.isInfinite(latitude) || latitude < -90.0 || latitude > 90.0) {
            throw new BusinessException("Latitude deve estar entre -90.0 e 90.0", HttpStatus.BAD_REQUEST, "INVALID_NEARBY_COORDINATES");
        }
        if (longitude == null || Double.isNaN(longitude) || Double.isInfinite(longitude) || longitude < -180.0 || longitude > 180.0) {
            throw new BusinessException("Longitude deve estar entre -180.0 e 180.0", HttpStatus.BAD_REQUEST, "INVALID_NEARBY_COORDINATES");
        }
        if (radiusMeters == null || Double.isNaN(radiusMeters) || Double.isInfinite(radiusMeters) || radiusMeters <= 0.0 || radiusMeters > 50000.0) {
            throw new BusinessException("O raio de busca deve ser maior que zero e até 50.000 metros", HttpStatus.BAD_REQUEST, "INVALID_NEARBY_RADIUS");
        }
        int effectiveLimit = limit != null ? limit : 20;
        if (effectiveLimit < 1 || effectiveLimit > 100) {
            throw new BusinessException("O limite de resultados deve ser entre 1 e 100", HttpStatus.BAD_REQUEST, "INVALID_NEARBY_LIMIT");
        }

        return placeRepository.findNearbyWithDistance(latitude, longitude, radiusMeters, effectiveLimit);
    }

    private Place executePersistPlace(CreatePlaceCommand cmd, String slug, String provider, String externalId) {
        Integer validationRadiusValue = cmd.validationRadiusMeters();
        int validationRadiusMeters = validationRadiusValue != null ? validationRadiusValue : 50;

        Place place = new Place(
                null,
                cmd.name(),
                slug,
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
                validationRadiusMeters,
                "USER",
                false,
                cmd.claimedByBusinessId(),
                "ACTIVE"
        );

        Place savedPlace = placeRepository.save(place);

        if (provider != null && externalId != null) {
            PlaceExternalReference externalReference = new PlaceExternalReference(
                    null,
                    savedPlace.getId(),
                    provider,
                    externalId,
                    null
            );
            placeExternalReferenceRepository.save(externalReference);
        }

        return savedPlace;
    }

    private boolean isConstraintViolation(Throwable ex, String constraintName) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ConstraintViolationException cve) {
                if (cve.getConstraintName() != null && cve.getConstraintName().equalsIgnoreCase(constraintName)) {
                    return true;
                }
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(constraintName.toLowerCase(Locale.ROOT))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static String generateSlug(String text) {
        if (text == null || text.isBlank()) {
            return UUID.randomUUID().toString().substring(0, 8);
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        String withoutAccents = normalized.replaceAll("\\p{M}", "");
        String slug = withoutAccents.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("[\\s-]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? UUID.randomUUID().toString().substring(0, 8) : slug;
    }

    @Transactional(readOnly = true)
    public Place getPlaceById(UUID id) {
        if (id == null) {
            throw new BusinessException("Identificador de local inválido", HttpStatus.BAD_REQUEST, "INVALID_PLACE_ID");
        }
        // Detalhe público só de ACTIVE, como a busca: indisponível e inexistente são o mesmo 404
        return placeRepository.findById(id)
                .filter(place -> PUBLIC_CATALOG_STATUS.equals(place.getStatus()))
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
    Product createProduct(CreateProductCommand cmd) {
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
        // Detalhe público só de ACTIVE, como a busca: indisponível e inexistente são o mesmo 404
        return productRepository.findById(id)
                .filter(product -> PUBLIC_CATALOG_STATUS.equals(product.getStatus()))
                .orElseThrow(() -> new BusinessException("Produto não encontrado", HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
    }

    @Transactional
    ProductIdentifier addProductIdentifier(AddProductIdentifierCommand cmd) {
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
    ProductPresence associateProductToPlace(AssociateProductPresenceCommand cmd) {
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
