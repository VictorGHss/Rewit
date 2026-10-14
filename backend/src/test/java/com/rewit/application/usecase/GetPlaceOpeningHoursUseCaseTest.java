package com.rewit.application.usecase;

import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.PlaceOpeningHoursView;
import com.rewit.application.port.PlaceOpeningHoursRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.service.BusinessRepresentationPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.BusinessRepresentationIneligibility;
import com.rewit.domain.model.OpeningHoursInterval;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceDaySchedule;
import com.rewit.domain.model.PlaceOpeningHoursSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: GetPlaceOpeningHoursUseCase (Decisão F2)")
class GetPlaceOpeningHoursUseCaseTest {

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private PlaceOpeningHoursRepository placeOpeningHoursRepository;

    @Mock
    private BusinessRepresentationPolicy representationPolicy;

    @InjectMocks
    private GetPlaceOpeningHoursUseCase useCase;

    private final UUID placeId = UUID.randomUUID();
    private final UUID businessAccountId = UUID.randomUUID();
    private final UUID updatedByUserId = UUID.randomUUID();

    private Place activePlace;
    private PlaceOpeningHoursSchedule schedule;

    @BeforeEach
    void setUp() {
        activePlace = new Place(
                placeId, "Local Teste", "local-teste", "RESTAURANTE", "Desc",
                "Rua A", "Curitiba", "PR", "BR", -25.4, -49.2, 50, "USER",
                false, businessAccountId, "ACTIVE"
        );

        schedule = PlaceOpeningHoursSchedule.create(
                placeId,
                businessAccountId,
                updatedByUserId,
                List.of(PlaceDaySchedule.open(1, List.of(
                        new OpeningHoursInterval(LocalTime.of(8, 0), LocalTime.of(18, 0))
                )))
        );
    }

    @Test
    @DisplayName("Deve rejeitar placeId nulo com 400 MISSING_PLACE_ID")
    void shouldThrowWhenPlaceIdIsNull() {
        BusinessException ex = assertThrows(BusinessException.class, () -> useCase.execute(null));
        assertEquals("MISSING_PLACE_ID", ex.getErrorCode());
    }

    @Test
    @DisplayName("Deve responder 404 PLACE_NOT_FOUND quando local não existe")
    void shouldThrowWhenPlaceNotFound() {
        when(placeRepository.findById(placeId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> useCase.execute(placeId));
        assertEquals("PLACE_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    @DisplayName("Deve responder 404 PLACE_NOT_FOUND quando local está inativo no catálogo")
    void shouldThrowWhenPlaceIsInactive() {
        Place inactivePlace = new Place(
                placeId, "Local Inativo", "local-inativo", "RESTAURANTE", "Desc",
                "Rua A", "Curitiba", "PR", "BR", -25.4, -49.2, 50, "USER",
                false, businessAccountId, "INACTIVE"
        );
        when(placeRepository.findById(placeId)).thenReturn(Optional.of(inactivePlace));

        BusinessException ex = assertThrows(BusinessException.class, () -> useCase.execute(placeId));
        assertEquals("PLACE_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    @DisplayName("Deve retornar unconfigured quando não há horários cadastrados no repositório")
    void shouldReturnUnconfiguredWhenNoScheduleExists() {
        when(placeRepository.findById(placeId)).thenReturn(Optional.of(activePlace));
        when(placeOpeningHoursRepository.findScheduleByPlaceId(placeId)).thenReturn(Optional.empty());

        PlaceOpeningHoursView view = useCase.execute(placeId);

        assertNotNull(view);
        assertEquals(placeId, view.placeId());
        assertFalse(view.configured());
        assertTrue(view.days().isEmpty());
    }

    @Test
    @DisplayName("Deve retornar horários configurados quando representação comercial é plenamente elegível")
    void shouldReturnConfiguredWhenScheduleExistsAndRepresentationIsEligible() {
        when(placeRepository.findById(placeId)).thenReturn(Optional.of(activePlace));
        when(placeOpeningHoursRepository.findScheduleByPlaceId(placeId)).thenReturn(Optional.of(schedule));
        when(representationPolicy.currentIneligibility(businessAccountId, placeId)).thenReturn(Collections.emptySet());

        PlaceOpeningHoursView view = useCase.execute(placeId);

        assertNotNull(view);
        assertEquals(placeId, view.placeId());
        assertTrue(view.configured());
        assertEquals(7, view.days().size());
        assertFalse(view.days().getFirst().closed());
        assertEquals(1, view.days().getFirst().intervals().size());
    }

    @Test
    @DisplayName("Deve ocultar horários (unconfigured) quando administrador da conta está inoperante/suspenso")
    void shouldReturnUnconfiguredWhenAdminIsNotOperational() {
        when(placeRepository.findById(placeId)).thenReturn(Optional.of(activePlace));
        when(placeOpeningHoursRepository.findScheduleByPlaceId(placeId)).thenReturn(Optional.of(schedule));
        when(representationPolicy.currentIneligibility(businessAccountId, placeId))
                .thenReturn(Set.of(BusinessRepresentationIneligibility.ADMINISTRATOR_NOT_OPERATIONAL));

        PlaceOpeningHoursView view = useCase.execute(placeId);

        assertNotNull(view);
        assertEquals(placeId, view.placeId());
        assertFalse(view.configured(), "Horários devem ficar ocultos (configured = false)");
        assertTrue(view.days().isEmpty(), "Dias e intervalos devem retornar vazios");
    }

    @Test
    @DisplayName("Deve ocultar horários (unconfigured) quando conta comercial não está aprovada")
    void shouldReturnUnconfiguredWhenAccountIsNotApproved() {
        when(placeRepository.findById(placeId)).thenReturn(Optional.of(activePlace));
        when(placeOpeningHoursRepository.findScheduleByPlaceId(placeId)).thenReturn(Optional.of(schedule));
        when(representationPolicy.currentIneligibility(businessAccountId, placeId))
                .thenReturn(Set.of(BusinessRepresentationIneligibility.ACCOUNT_NOT_APPROVED));

        PlaceOpeningHoursView view = useCase.execute(placeId);

        assertNotNull(view);
        assertEquals(placeId, view.placeId());
        assertFalse(view.configured());
        assertTrue(view.days().isEmpty());
    }

    @Test
    @DisplayName("Deve ocultar horários (unconfigured) quando local foi transferido ou desvinculado (PLACE_NOT_REPRESENTED)")
    void shouldReturnUnconfiguredWhenPlaceIsNotRepresented() {
        when(placeRepository.findById(placeId)).thenReturn(Optional.of(activePlace));
        when(placeOpeningHoursRepository.findScheduleByPlaceId(placeId)).thenReturn(Optional.of(schedule));
        when(representationPolicy.currentIneligibility(businessAccountId, placeId))
                .thenReturn(Set.of(BusinessRepresentationIneligibility.PLACE_NOT_REPRESENTED));

        PlaceOpeningHoursView view = useCase.execute(placeId);

        assertNotNull(view);
        assertEquals(placeId, view.placeId());
        assertFalse(view.configured());
        assertTrue(view.days().isEmpty());
    }
}
