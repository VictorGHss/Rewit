package com.rewit.application.usecase;

import com.rewit.application.dto.catalog.PlaceProductOfferDtos.DeletePlaceProductOfferCommand;
import com.rewit.application.dto.catalog.PlaceProductOfferDtos.PlaceProductOfferView;
import com.rewit.application.dto.catalog.PlaceProductOfferDtos.UpsertPlaceProductOfferCommand;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.PlaceProductOfferRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProductRepository;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.BusinessRepresentationIneligibility;
import com.rewit.domain.enums.OfferAvailability;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceProductOffer;
import com.rewit.domain.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Casos de uso: ofertas comerciais de produtos (F2.3)")
class PlaceProductOfferUseCasesTest {

    @Mock private BusinessRepresentationPolicy representationPolicy;
    @Mock private PlaceRepository placeRepository;
    @Mock private ProductRepository productRepository;
    @Mock private PlaceProductOfferRepository offerRepository;

    private UpsertPlaceProductOfferUseCase upsert;
    private DeletePlaceProductOfferUseCase delete;
    private ListPlaceProductOffersUseCase list;

    private final UUID actorId = UUID.randomUUID();
    private final UUID placeId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        upsert = new UpsertPlaceProductOfferUseCase(representationPolicy, placeRepository, productRepository,
                offerRepository);
        delete = new DeletePlaceProductOfferUseCase(representationPolicy, placeRepository, offerRepository);
        list = new ListPlaceProductOffersUseCase(placeRepository, offerRepository, representationPolicy);
    }

    @Test
    @DisplayName("PUT inválido (preço, moeda, disponibilidade, ids) é recusado antes de qualquer I/O")
    void invalidUpsertIsRejectedBeforeAnyIo() {
        assertCode("INVALID_OFFER_PRICE", command("AVAILABLE", new BigDecimal("-1"), null));
        assertCode("INVALID_OFFER_PRICE", command("AVAILABLE", new BigDecimal("1.001"), null));
        assertCode("UNSUPPORTED_CURRENCY", command("AVAILABLE", null, "EUR"));
        assertCode("INVALID_OFFER_AVAILABILITY", command("SOLD_OUT", null, null));
        assertCode("UNAUTHORIZED", new UpsertPlaceProductOfferCommand(null, placeId, productId, accountId,
                "AVAILABLE", null, null));
        assertCode("MISSING_PRODUCT_ID", new UpsertPlaceProductOfferCommand(actorId, placeId, null, accountId,
                "AVAILABLE", null, null));
        verifyNoInteractions(representationPolicy, placeRepository, productRepository, offerRepository);
    }

    @Test
    @DisplayName("PUT autoriza a conta, trava o local, confere produto e só então grava a nova oferta")
    void upsertFollowsPolicyOrderAndCreates() {
        BusinessAccount account = account();
        when(representationPolicy.requireApprovedAccount(actorId, accountId)).thenReturn(account);
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(place("ACTIVE", accountId)));
        when(productRepository.findById(productId)).thenReturn(Optional.of(product("ACTIVE")));
        when(offerRepository.findByPlaceIdAndProductId(placeId, productId)).thenReturn(Optional.empty());
        when(offerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PlaceProductOfferView view = upsert.execute(command("AVAILABLE", new BigDecimal("3.5"), null));

        InOrder order = inOrder(representationPolicy, placeRepository, productRepository, offerRepository);
        order.verify(representationPolicy).requireApprovedAccount(actorId, accountId);
        order.verify(placeRepository).findByIdForUpdate(placeId);
        order.verify(productRepository).findById(productId);
        order.verify(offerRepository).save(any());
        assertEquals(new BigDecimal("3.50"), view.price());
        assertEquals(OfferAvailability.AVAILABLE, view.availability());
        assertEquals(productId, view.product().id());
    }

    @Test
    @DisplayName("PUT com os mesmos termos não grava (idempotente)")
    void upsertWithSameTermsDoesNotWrite() {
        BusinessAccount account = account();
        when(representationPolicy.requireApprovedAccount(actorId, accountId)).thenReturn(account);
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(place("ACTIVE", accountId)));
        when(productRepository.findById(productId)).thenReturn(Optional.of(product("ACTIVE")));
        PlaceProductOffer stored = PlaceProductOffer.create(placeId, productId, accountId, actorId,
                OfferAvailability.OUT_OF_STOCK, new BigDecimal("2.00"), "BRL", Instant.now());
        when(offerRepository.findByPlaceIdAndProductId(placeId, productId)).thenReturn(Optional.of(stored));

        PlaceProductOfferView view = upsert.execute(command("OUT_OF_STOCK", new BigDecimal("2"), "BRL"));

        verify(offerRepository, never()).save(any());
        assertEquals(new BigDecimal("2.00"), view.price());
    }

    @Test
    @DisplayName("PUT: local de outra conta (403), local inativo (404) e produto inativo (404) não gravam")
    void upsertRefusals() {
        BusinessAccount account = account();
        when(representationPolicy.requireApprovedAccount(actorId, accountId)).thenReturn(account);
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(
                Optional.of(place("ACTIVE", UUID.randomUUID())),
                Optional.of(place("INACTIVE", accountId)),
                Optional.of(place("ACTIVE", accountId)));
        when(productRepository.findById(productId)).thenReturn(Optional.of(product("DISCONTINUED")));

        assertCode("BUSINESS_NOT_RESPONSIBLE", command("AVAILABLE", null, null));
        assertCode("PLACE_NOT_FOUND", command("AVAILABLE", null, null));
        assertCode("PRODUCT_NOT_FOUND", command("AVAILABLE", null, null));
        verify(offerRepository, never()).save(any());
    }

    @Test
    @DisplayName("DELETE: recusa da Política B para antes da trava; oferta ausente responde 404")
    void deleteChecks() {
        BusinessAccount account = account();
        when(representationPolicy.requireApprovedAccount(actorId, accountId))
                .thenThrow(new BusinessException("Conta comercial não aprovada",
                        org.springframework.http.HttpStatus.CONFLICT, "BUSINESS_ACCOUNT_NOT_APPROVED"))
                .thenReturn(account);
        DeletePlaceProductOfferCommand command = new DeletePlaceProductOfferCommand(actorId, placeId, productId,
                accountId);
        assertEquals("BUSINESS_ACCOUNT_NOT_APPROVED",
                assertThrows(BusinessException.class, () -> delete.execute(command)).getErrorCode());
        verifyNoInteractions(placeRepository, offerRepository);

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(place("ACTIVE", accountId)));
        when(offerRepository.delete(placeId, productId, accountId)).thenReturn(false);
        assertEquals("PLACE_PRODUCT_OFFER_NOT_FOUND",
                assertThrows(BusinessException.class, () -> delete.execute(command)).getErrorCode());
    }

    @Test
    @DisplayName("GET: titular inelegível ou ausente devolve página vazia sem consultar ofertas")
    void listHidesIneligibleOwner() {
        when(placeRepository.findById(placeId)).thenReturn(
                Optional.of(place("ACTIVE", accountId)), Optional.of(place("ACTIVE", null)));
        when(representationPolicy.currentIneligibility(accountId, placeId))
                .thenReturn(Set.of(BusinessRepresentationIneligibility.ADMINISTRATOR_NOT_OPERATIONAL));

        assertTrue(list.execute(placeId, 0, 20).content().isEmpty());
        assertEquals(0, list.execute(placeId, 0, 20).totalElements());
        verifyNoInteractions(offerRepository);
    }

    @Test
    @DisplayName("GET: titular elegível consulta só as ofertas dessa conta")
    void listQueriesCurrentOwner() {
        PageResult<PlaceProductOfferView> page = PageResult.of(List.of(), 0, 20, 0L);
        when(placeRepository.findById(placeId)).thenReturn(Optional.of(place("ACTIVE", accountId)));
        when(representationPolicy.currentIneligibility(accountId, placeId)).thenReturn(Set.of());
        when(offerRepository.findListedByPlace(placeId, accountId, 0, 20)).thenReturn(page);

        assertSame(page, list.execute(placeId, 0, 20));
    }

    @Test
    @DisplayName("GET: página negativa, tamanho fora de 1..50 e local inativo")
    void listValidation() {
        assertEquals("INVALID_PAGE", assertThrows(BusinessException.class,
                () -> list.execute(placeId, -1, 20)).getErrorCode());
        assertEquals("INVALID_PAGE_SIZE", assertThrows(BusinessException.class,
                () -> list.execute(placeId, 0, 51)).getErrorCode());
        when(placeRepository.findById(placeId)).thenReturn(Optional.of(place("CLOSED", accountId)));
        assertEquals("PLACE_NOT_FOUND", assertThrows(BusinessException.class,
                () -> list.execute(placeId, 0, 20)).getErrorCode());
    }

    private void assertCode(String code, UpsertPlaceProductOfferCommand command) {
        assertEquals(code, assertThrows(BusinessException.class, () -> upsert.execute(command)).getErrorCode());
    }

    private UpsertPlaceProductOfferCommand command(String availability, BigDecimal price, String currency) {
        return new UpsertPlaceProductOfferCommand(actorId, placeId, productId, accountId, availability, price,
                currency);
    }

    private BusinessAccount account() {
        BusinessAccount account = mock(BusinessAccount.class);
        when(account.getId()).thenReturn(accountId);
        return account;
    }

    private Place place(String status, UUID claimedBy) {
        return new Place(placeId, "Local", "local", "MERCADO", "Desc", "Rua A", "Curitiba", "PR", "BR",
                -25.4, -49.2, 50, "USER", false, claimedBy, status);
    }

    private Product product(String status) {
        return new Product(productId, "Produto", "Marca", null, null, "BEBIDA", null, status);
    }
}
