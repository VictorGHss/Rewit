package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.DayScheduleCommand;
import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.DayScheduleView;
import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.PlaceOpeningHoursView;
import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.TimeIntervalCommand;
import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.TimeIntervalView;
import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.UpdatePlaceOpeningHoursCommand;
import com.rewit.application.usecase.GetPlaceOpeningHoursUseCase;
import com.rewit.application.usecase.UpdatePlaceOpeningHoursUseCase;
import com.rewit.common.exception.GlobalExceptionHandler;
import com.rewit.presentation.dto.catalog.PlaceOpeningHoursPresentationDtos.DayScheduleRequest;
import com.rewit.presentation.dto.catalog.PlaceOpeningHoursPresentationDtos.TimeIntervalRequest;
import com.rewit.presentation.dto.catalog.PlaceOpeningHoursPresentationDtos.UpdatePlaceOpeningHoursRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: PlaceOpeningHoursController (F2.1)")
class PlaceOpeningHoursControllerUnitTest {

    @Mock
    private GetPlaceOpeningHoursUseCase getPlaceOpeningHoursUseCase;

    @Mock
    private UpdatePlaceOpeningHoursUseCase updatePlaceOpeningHoursUseCase;

    @InjectMocks
    private PlaceOpeningHoursController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private final UUID actorUserId = UUID.randomUUID();
    private final UUID placeId = UUID.randomUUID();
    private final UUID businessAccountId = UUID.randomUUID();
    private Authentication auth;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        auth = new UsernamePasswordAuthenticationToken(actorUserId.toString(), "credentials", Collections.emptyList());
    }

    @Test
    @DisplayName("GET /api/v1/places/{placeId}/opening-hours deve retornar horários configurados com 200 OK")
    void shouldReturnConfiguredOpeningHours() throws Exception {
        List<DayScheduleView> days = List.of(
                new DayScheduleView(1, "MONDAY", false, List.of(
                        new TimeIntervalView(LocalTime.of(8, 0), LocalTime.of(18, 0))
                )),
                new DayScheduleView(7, "SUNDAY", true, Collections.emptyList())
        );
        PlaceOpeningHoursView view = new PlaceOpeningHoursView(placeId, true, days);

        when(getPlaceOpeningHoursUseCase.execute(placeId)).thenReturn(view);

        mockMvc.perform(get("/api/v1/places/{placeId}/opening-hours", placeId)
                        .principal(auth)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.days[0].dayOfWeek").value(1))
                .andExpect(jsonPath("$.days[0].dayName").value("MONDAY"))
                .andExpect(jsonPath("$.days[0].closed").value(false))
                .andExpect(jsonPath("$.days[0].intervals[0].openTime").value("08:00"))
                .andExpect(jsonPath("$.days[0].intervals[0].closeTime").value("18:00"))
                .andExpect(jsonPath("$.days[1].dayOfWeek").value(7))
                .andExpect(jsonPath("$.days[1].closed").value(true));

        verify(getPlaceOpeningHoursUseCase).execute(placeId);
    }

    @Test
    @DisplayName("GET /api/v1/places/{placeId}/opening-hours deve retornar unconfigured quando ausente")
    void shouldReturnUnconfiguredOpeningHours() throws Exception {
        PlaceOpeningHoursView view = PlaceOpeningHoursView.unconfigured(placeId);
        when(getPlaceOpeningHoursUseCase.execute(placeId)).thenReturn(view);

        mockMvc.perform(get("/api/v1/places/{placeId}/opening-hours", placeId)
                        .principal(auth)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.days").isEmpty());

        verify(getPlaceOpeningHoursUseCase).execute(placeId);
    }

    @Test
    @DisplayName("GET /api/v1/places/{placeId}/opening-hours deve rejeitar requisição sem autenticação")
    void shouldRejectGetWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/places/{placeId}/opening-hours", placeId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("PUT /api/v1/places/{placeId}/opening-hours deve atualizar e retornar 200 OK")
    void shouldUpdateOpeningHoursSuccessfully() throws Exception {
        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                businessAccountId,
                List.of(
                        new DayScheduleRequest(1, false, List.of(
                                new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(12, 0)),
                                new TimeIntervalRequest(LocalTime.of(14, 0), LocalTime.of(18, 0))
                        )),
                        new DayScheduleRequest(7, true, Collections.emptyList())
                )
        );

        PlaceOpeningHoursView view = new PlaceOpeningHoursView(placeId, true, List.of(
                new DayScheduleView(1, "MONDAY", false, List.of(
                        new TimeIntervalView(LocalTime.of(8, 0), LocalTime.of(12, 0)),
                        new TimeIntervalView(LocalTime.of(14, 0), LocalTime.of(18, 0))
                )),
                new DayScheduleView(7, "SUNDAY", true, Collections.emptyList())
        ));

        when(updatePlaceOpeningHoursUseCase.execute(any(UpdatePlaceOpeningHoursCommand.class))).thenReturn(view);

        mockMvc.perform(put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeId").value(placeId.toString()))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.days[0].dayOfWeek").value(1))
                .andExpect(jsonPath("$.days[0].intervals[0].openTime").value("08:00"))
                .andExpect(jsonPath("$.days[0].intervals[0].closeTime").value("12:00"))
                .andExpect(jsonPath("$.days[0].intervals[1].openTime").value("14:00"))
                .andExpect(jsonPath("$.days[0].intervals[1].closeTime").value("18:00"));

        verify(updatePlaceOpeningHoursUseCase).execute(any(UpdatePlaceOpeningHoursCommand.class));
    }

    @Test
    @DisplayName("PUT /api/v1/places/{placeId}/opening-hours deve rejeitar payload com businessAccountId nulo")
    void shouldRejectPutWithNullBusinessAccountId() throws Exception {
        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                null,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                )))
        );

        mockMvc.perform(put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/v1/places/{placeId}/opening-hours deve rejeitar dia da semana fora de 1..7")
    void shouldRejectPutWithInvalidDayOfWeek() throws Exception {
        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                businessAccountId,
                List.of(new DayScheduleRequest(9, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                )))
        );

        mockMvc.perform(put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/v1/places/{placeId}/opening-hours deve rejeitar quando não autenticado")
    void shouldRejectPutWithoutAuthentication() throws Exception {
        UpdatePlaceOpeningHoursRequest request = new UpdatePlaceOpeningHoursRequest(
                businessAccountId,
                List.of(new DayScheduleRequest(1, false, List.of(
                        new TimeIntervalRequest(LocalTime.of(8, 0), LocalTime.of(18, 0))
                )))
        );

        mockMvc.perform(put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("PUT /api/v1/places/{placeId}/opening-hours deve rejeitar lista de dias com elemento nulo (F1)")
    void shouldRejectPutWithNullDaysElement() throws Exception {
        String jsonPayload = """
                {
                    "businessAccountId": "%s",
                    "days": [null]
                }
                """.formatted(businessAccountId);

        mockMvc.perform(put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("PUT /api/v1/places/{placeId}/opening-hours deve rejeitar lista de intervalos com elemento nulo (F1)")
    void shouldRejectPutWithNullIntervalsElement() throws Exception {
        String jsonPayload = """
                {
                    "businessAccountId": "%s",
                    "days": [
                        {
                            "dayOfWeek": 1,
                            "closed": false,
                            "intervals": [null]
                        }
                    ]
                }
                """.formatted(businessAccountId);

        mockMvc.perform(put("/api/v1/places/{placeId}/opening-hours", placeId)
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
