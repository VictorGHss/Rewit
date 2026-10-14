package com.rewit.presentation.dto.catalog;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.DayScheduleCommand;
import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.DayScheduleView;
import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.PlaceOpeningHoursView;
import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.TimeIntervalCommand;
import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.TimeIntervalView;
import com.rewit.application.dto.catalog.PlaceOpeningHoursDtos.UpdatePlaceOpeningHoursCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Contratos de entrada e saída RESTful para gerenciamento de horários de funcionamento (F2.1).
 */
public final class PlaceOpeningHoursPresentationDtos {

    private PlaceOpeningHoursPresentationDtos() {
    }

    @Schema(description = "Intervalo de funcionamento com horários de abertura e fechamento")
    public record TimeIntervalRequest(
            @NotNull(message = "O horário de abertura é obrigatório")
            @JsonFormat(pattern = "HH:mm")
            @Schema(type = "string", example = "08:00")
            LocalTime openTime,

            @NotNull(message = "O horário de fechamento é obrigatório")
            @JsonFormat(pattern = "HH:mm")
            @Schema(type = "string", example = "18:00")
            LocalTime closeTime
    ) {
        public TimeIntervalCommand toCommand() {
            return new TimeIntervalCommand(openTime, closeTime);
        }
    }

    @Schema(description = "Programação de horários para um dia da semana")
    public record DayScheduleRequest(
            @Min(value = 1, message = "Dia da semana deve estar entre 1 (Segunda-feira) e 7 (Domingo)")
            @Max(value = 7, message = "Dia da semana deve estar entre 1 (Segunda-feira) e 7 (Domingo)")
            @Schema(example = "1", description = "1 = Segunda-feira, 7 = Domingo")
            int dayOfWeek,

            @Schema(example = "false", description = "Indica se o estabelecimento estará fechado no dia")
            boolean closed,

            @Valid
            @Schema(description = "Lista de intervalos de funcionamento no dia (obrigatório se closed for false)")
            List<TimeIntervalRequest> intervals
    ) {
        public DayScheduleCommand toCommand() {
            List<TimeIntervalCommand> intervalCommands = intervals != null
                    ? intervals.stream().map(TimeIntervalRequest::toCommand).toList()
                    : Collections.emptyList();
            return new DayScheduleCommand(dayOfWeek, closed, intervalCommands);
        }
    }

    @Schema(description = "Payload para configuração/atualização dos horários de funcionamento do estabelecimento")
    public record UpdatePlaceOpeningHoursRequest(
            @NotNull(message = "O identificador da conta comercial é obrigatório")
            @Schema(description = "Identificador da conta comercial aprovada que administra o local")
            UUID businessAccountId,

            @NotNull(message = "A lista de dias de funcionamento é obrigatória")
            @Valid
            @Schema(description = "Programação semanal dos dias de funcionamento")
            List<DayScheduleRequest> days
    ) {
        public UpdatePlaceOpeningHoursCommand toCommand(UUID actorUserId, UUID placeId) {
            List<DayScheduleCommand> dayCommands = days != null
                    ? days.stream().map(DayScheduleRequest::toCommand).toList()
                    : Collections.emptyList();
            return new UpdatePlaceOpeningHoursCommand(actorUserId, placeId, businessAccountId, dayCommands);
        }
    }

    @Schema(description = "Intervalo de funcionamento retornado pela API")
    public record TimeIntervalResponse(
            @JsonFormat(pattern = "HH:mm")
            @Schema(type = "string", example = "08:00")
            LocalTime openTime,

            @JsonFormat(pattern = "HH:mm")
            @Schema(type = "string", example = "18:00")
            LocalTime closeTime
    ) {
        public static TimeIntervalResponse fromView(TimeIntervalView view) {
            return new TimeIntervalResponse(view.openTime(), view.closeTime());
        }
    }

    @Schema(description = "Horários de um dia específico retornado pela API")
    public record DayScheduleResponse(
            @Schema(example = "1")
            int dayOfWeek,

            @Schema(example = "MONDAY")
            String dayName,

            @Schema(example = "false")
            boolean closed,

            @Schema(description = "Intervalos de funcionamento no dia")
            List<TimeIntervalResponse> intervals
    ) {
        public static DayScheduleResponse fromView(DayScheduleView view) {
            List<TimeIntervalResponse> intervalResponses = view.intervals() != null
                    ? view.intervals().stream().map(TimeIntervalResponse::fromView).toList()
                    : Collections.emptyList();
            return new DayScheduleResponse(view.dayOfWeek(), view.dayName(), view.closed(), intervalResponses);
        }
    }

    @Schema(description = "Resposta com o cronograma semanal de funcionamento do local")
    public record PlaceOpeningHoursResponse(
            @Schema(description = "Identificador do local")
            UUID placeId,

            @Schema(example = "true", description = "Indica se o local já possui horários de funcionamento configurados")
            boolean configured,

            @Schema(description = "Grade semanal dos 7 dias da semana ordenados de Segunda a Domingo")
            List<DayScheduleResponse> days
    ) {
        public static PlaceOpeningHoursResponse fromView(PlaceOpeningHoursView view) {
            List<DayScheduleResponse> dayResponses = view.days() != null
                    ? view.days().stream().map(DayScheduleResponse::fromView).toList()
                    : Collections.emptyList();
            return new PlaceOpeningHoursResponse(view.placeId(), view.configured(), dayResponses);
        }
    }
}

