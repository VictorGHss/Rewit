package com.rewit.application.usecase;

import com.rewit.application.dto.catalog.PlaceAmenityDtos.AmenityView;
import com.rewit.application.dto.catalog.PlaceAmenityDtos.PlaceAmenitiesView;
import com.rewit.application.dto.catalog.PlaceAmenityDtos.UpdatePlaceAmenitiesCommand;
import com.rewit.application.port.PlaceAmenityRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.BusinessRepresentationIneligibility;
import com.rewit.domain.enums.PlaceAmenity;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceAmenityConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
@DisplayName("Casos de uso: comodidades de estabelecimentos (F2.2)")
class PlaceAmenitiesUseCaseTest {

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private PlaceAmenityRepository placeAmenityRepository;

    @Mock
    private BusinessRepresentationPolicy representationPolicy;

    private GetPlaceAmenitiesUseCase getUseCase;
    private UpdatePlaceAmenitiesUseCase updateUseCase;

    private final UUID actorId = UUID.randomUUID();
    private final UUID placeId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        getUseCase = new GetPlaceAmenitiesUseCase(placeRepository, placeAmenityRepository, representationPolicy);
        updateUseCase = new UpdatePlaceAmenitiesUseCase(representationPolicy, placeRepository, placeAmenityRepository);
    }

    // --- Leitura ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Leitura sem configuração não consulta a elegibilidade")
    void readWithoutConfigurationIsUnconfigured() {
        when(placeRepository.findById(placeId)).thenReturn(Optional.of(place("ACTIVE", accountId)));
        when(placeAmenityRepository.findByPlaceId(placeId)).thenReturn(Optional.empty());

        PlaceAmenitiesView view = getUseCase.execute(placeId);

        assertFalse(view.configured());
        assertTrue(view.amenities().isEmpty());
        verifyNoInteractions(representationPolicy);
    }

    @Test
    @DisplayName("Leitura avalia a representação que gravou, não a titular atual, e oculta se inelegível")
    void readHidesConfigurationOfIneligibleRepresentation() {
        UUID storedAccount = UUID.randomUUID();
        when(placeRepository.findById(placeId)).thenReturn(Optional.of(place("ACTIVE", accountId)));
        when(placeAmenityRepository.findByPlaceId(placeId)).thenReturn(Optional.of(configuration(storedAccount,
                PlaceAmenity.WIFI)));
        when(representationPolicy.currentIneligibility(storedAccount, placeId))
                .thenReturn(Set.of(BusinessRepresentationIneligibility.PLACE_NOT_REPRESENTED));

        PlaceAmenitiesView view = getUseCase.execute(placeId);

        assertEquals(PlaceAmenitiesView.unconfigured(placeId), view);
    }

    @Test
    @DisplayName("Leitura de representação elegível devolve código e rótulo")
    void readReturnsEligibleConfiguration() {
        when(placeRepository.findById(placeId)).thenReturn(Optional.of(place("ACTIVE", accountId)));
        when(placeAmenityRepository.findByPlaceId(placeId)).thenReturn(Optional.of(configuration(accountId,
                PlaceAmenity.PARKING, PlaceAmenity.WIFI)));
        when(representationPolicy.currentIneligibility(accountId, placeId)).thenReturn(Set.of());

        PlaceAmenitiesView view = getUseCase.execute(placeId);

        assertTrue(view.configured());
        assertEquals(List.of(new AmenityView("WIFI", "Wi-Fi para clientes"),
                new AmenityView("PARKING", "Estacionamento")), view.amenities());
    }

    @Test
    @DisplayName("Leitura de local ausente ou inativo responde 404 sem consultar comodidades")
    void readOfMissingOrInactivePlaceIsNotFound() {
        when(placeRepository.findById(placeId)).thenReturn(Optional.empty(), Optional.of(place("INACTIVE", null)));

        assertEquals("PLACE_NOT_FOUND", assertThrows(BusinessException.class,
                () -> getUseCase.execute(placeId)).getErrorCode());
        assertEquals("PLACE_NOT_FOUND", assertThrows(BusinessException.class,
                () -> getUseCase.execute(placeId)).getErrorCode());
        assertEquals("MISSING_PLACE_ID", assertThrows(BusinessException.class,
                () -> getUseCase.execute(null)).getErrorCode());
        verifyNoInteractions(placeAmenityRepository, representationPolicy);
    }

    // --- Gravação --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Lista inválida é recusada antes de autorização, trava ou escrita")
    void invalidListIsRejectedBeforeAnyIo() {
        for (List<String> codes : List.of(List.of("WIFI", "SAUNA"), List.of("WIFI", "WIFI"),
                Arrays.asList("WIFI", null))) {
            assertThrows(BusinessException.class, () -> updateUseCase.execute(command(codes)));
        }
        assertEquals("MISSING_AMENITIES", assertThrows(BusinessException.class,
                () -> updateUseCase.execute(command(null))).getErrorCode());
        verifyNoInteractions(representationPolicy, placeRepository, placeAmenityRepository);
    }

    @Test
    @DisplayName("Gravação autoriza a conta, trava o local, confere a representação e só então grava")
    void updateFollowsPolicyOrderAndSaves() {
        BusinessAccount account = approvedAccount();
        Place place = place("ACTIVE", accountId);
        when(representationPolicy.requireApprovedAccount(actorId, accountId)).thenReturn(account);
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(place));
        when(placeAmenityRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PlaceAmenitiesView view = updateUseCase.execute(command(List.of("PET_FRIENDLY", "WIFI")));

        InOrder order = inOrder(representationPolicy, placeRepository, placeAmenityRepository);
        order.verify(representationPolicy).requireApprovedAccount(actorId, accountId);
        order.verify(placeRepository).findByIdForUpdate(placeId);
        ArgumentCaptor<PlaceAmenityConfiguration> saved = ArgumentCaptor.forClass(PlaceAmenityConfiguration.class);
        order.verify(placeAmenityRepository).save(saved.capture());

        assertEquals(placeId, saved.getValue().getPlaceId());
        assertEquals(accountId, saved.getValue().getBusinessAccountId());
        assertEquals(actorId, saved.getValue().getUpdatedByUserId());
        assertEquals(List.of(PlaceAmenity.WIFI, PlaceAmenity.PET_FRIENDLY), List.copyOf(saved.getValue().getAmenities()));
        assertTrue(view.configured());
        assertEquals(List.of("WIFI", "PET_FRIENDLY"), view.amenities().stream().map(AmenityView::code).toList());
    }

    @Test
    @DisplayName("Local de outra conta responde 403 e local inativo 404, sem gravar")
    void updateRejectsPlaceNotRepresentedOrInactive() {
        BusinessAccount account = approvedAccount();
        when(representationPolicy.requireApprovedAccount(actorId, accountId)).thenReturn(account);
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(
                Optional.of(place("ACTIVE", UUID.randomUUID())),
                Optional.of(place("ACTIVE", null)),
                Optional.of(place("INACTIVE", accountId)),
                Optional.empty());

        assertEquals("BUSINESS_NOT_RESPONSIBLE", assertThrows(BusinessException.class,
                () -> updateUseCase.execute(command(List.of("WIFI")))).getErrorCode());
        assertEquals("BUSINESS_NOT_RESPONSIBLE", assertThrows(BusinessException.class,
                () -> updateUseCase.execute(command(List.of("WIFI")))).getErrorCode());
        assertEquals("PLACE_NOT_FOUND", assertThrows(BusinessException.class,
                () -> updateUseCase.execute(command(List.of("WIFI")))).getErrorCode());
        assertEquals("PLACE_NOT_FOUND", assertThrows(BusinessException.class,
                () -> updateUseCase.execute(command(List.of("WIFI")))).getErrorCode());
        verify(placeAmenityRepository, never()).save(any());
    }

    @Test
    @DisplayName("Recusa da Política B interrompe antes de travar o local")
    void accountRefusalStopsBeforeLock() {
        when(representationPolicy.requireApprovedAccount(actorId, accountId)).thenThrow(new BusinessException(
                "Conta comercial não aprovada", org.springframework.http.HttpStatus.CONFLICT,
                "BUSINESS_ACCOUNT_NOT_APPROVED"));

        assertEquals("BUSINESS_ACCOUNT_NOT_APPROVED", assertThrows(BusinessException.class,
                () -> updateUseCase.execute(command(List.of("WIFI")))).getErrorCode());
        verifyNoInteractions(placeRepository, placeAmenityRepository);
    }

    @Test
    @DisplayName("Ator, local e conta ausentes respondem antes de qualquer acesso")
    void missingIdentifiersAreRejected() {
        assertEquals("UNAUTHORIZED", assertThrows(BusinessException.class, () -> updateUseCase.execute(
                new UpdatePlaceAmenitiesCommand(null, placeId, accountId, List.of()))).getErrorCode());
        assertEquals("MISSING_PLACE_ID", assertThrows(BusinessException.class, () -> updateUseCase.execute(
                new UpdatePlaceAmenitiesCommand(actorId, null, accountId, List.of()))).getErrorCode());
        assertEquals("MISSING_BUSINESS_ACCOUNT_ID", assertThrows(BusinessException.class, () -> updateUseCase.execute(
                new UpdatePlaceAmenitiesCommand(actorId, placeId, null, List.of()))).getErrorCode());
        verifyNoInteractions(representationPolicy, placeRepository, placeAmenityRepository);
    }

    // --- Apoio -----------------------------------------------------------------------------------------------

    private UpdatePlaceAmenitiesCommand command(List<String> codes) {
        return new UpdatePlaceAmenitiesCommand(actorId, placeId, accountId, codes);
    }

    private Place place(String status, UUID claimedBy) {
        return new Place(placeId, "Local", "local", "RESTAURANTE", "Desc", "Rua A", "Curitiba", "PR", "BR",
                -25.4, -49.2, 50, "USER", false, claimedBy, status);
    }

    private BusinessAccount approvedAccount() {
        BusinessAccount account = mock(BusinessAccount.class);
        when(account.getId()).thenReturn(accountId);
        return account;
    }

    private PlaceAmenityConfiguration configuration(UUID businessAccountId, PlaceAmenity... amenities) {
        return PlaceAmenityConfiguration.create(placeId, businessAccountId, actorId, List.of(amenities), null);
    }
}
