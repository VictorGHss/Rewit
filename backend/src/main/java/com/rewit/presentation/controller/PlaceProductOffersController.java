package com.rewit.presentation.controller;

import com.rewit.application.dto.catalog.PlaceProductOfferDtos.DeletePlaceProductOfferCommand;
import com.rewit.application.dto.catalog.PlaceProductOfferDtos.PlaceProductOfferView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.usecase.DeletePlaceProductOfferUseCase;
import com.rewit.application.usecase.ListPlaceProductOffersUseCase;
import com.rewit.application.usecase.UpsertPlaceProductOfferUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.catalog.PlaceProductOfferPresentationDtos.PlaceProductOfferResponse;
import com.rewit.presentation.dto.catalog.PlaceProductOfferPresentationDtos.UpsertPlaceProductOfferRequest;
import com.rewit.presentation.dto.common.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Ofertas comerciais de produtos por estabelecimento (F2.3): vitrine e gestão pela empresa titular.
 */
@RestController
@RequestMapping("/api/v1/places/{placeId}/offers")
@Tag(name = "Catálogo - Ofertas Comerciais", description = "Ofertas de produtos por estabelecimento (F2.3)")
public class PlaceProductOffersController {

    private final ListPlaceProductOffersUseCase listOffersUseCase;
    private final UpsertPlaceProductOfferUseCase upsertOfferUseCase;
    private final DeletePlaceProductOfferUseCase deleteOfferUseCase;

    public PlaceProductOffersController(ListPlaceProductOffersUseCase listOffersUseCase,
                                        UpsertPlaceProductOfferUseCase upsertOfferUseCase,
                                        DeletePlaceProductOfferUseCase deleteOfferUseCase) {
        this.listOffersUseCase = Objects.requireNonNull(listOffersUseCase,
                "ListPlaceProductOffersUseCase must not be null");
        this.upsertOfferUseCase = Objects.requireNonNull(upsertOfferUseCase,
                "UpsertPlaceProductOfferUseCase must not be null");
        this.deleteOfferUseCase = Objects.requireNonNull(deleteOfferUseCase,
                "DeletePlaceProductOfferUseCase must not be null");
    }

    /** GET /api/v1/places/{placeId}/offers — ofertas visíveis, paginadas por nome do produto. */
    @GetMapping
    @Operation(summary = "Listar ofertas comerciais do local", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PagedResponse<PlaceProductOfferResponse>> listOffers(
            @PathVariable("placeId") UUID placeId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication) {
        extractAuthenticatedUserId(authentication);
        PageResult<PlaceProductOfferView> result = listOffersUseCase.execute(placeId, page, size);
        return ResponseEntity.ok(new PagedResponse<>(
                result.content().stream().map(PlaceProductOfferResponse::fromView).toList(),
                result.pageNumber(),
                result.pageSize(),
                result.totalElements(),
                result.totalPages(),
                result.isLast()));
    }

    /** PUT /api/v1/places/{placeId}/offers/{productId} — cria ou atualiza a oferta do produto (idempotente). */
    @PutMapping("/{productId}")
    @Operation(summary = "Criar ou atualizar a oferta de um produto", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PlaceProductOfferResponse> upsertOffer(@PathVariable("placeId") UUID placeId,
                                                                 @PathVariable("productId") UUID productId,
                                                                 @Valid @RequestBody UpsertPlaceProductOfferRequest request,
                                                                 Authentication authentication) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        return ResponseEntity.ok(PlaceProductOfferResponse.fromView(
                upsertOfferUseCase.execute(request.toCommand(actorUserId, placeId, productId))));
    }

    /** DELETE /api/v1/places/{placeId}/offers/{productId}?businessAccountId= — retira a oferta da vitrine. */
    @DeleteMapping("/{productId}")
    @Operation(summary = "Retirar a oferta de um produto", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> deleteOffer(@PathVariable("placeId") UUID placeId,
                                            @PathVariable("productId") UUID productId,
                                            @RequestParam("businessAccountId") UUID businessAccountId,
                                            Authentication authentication) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        deleteOfferUseCase.execute(new DeletePlaceProductOfferCommand(actorUserId, placeId, productId,
                businessAccountId));
        return ResponseEntity.noContent().build();
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
