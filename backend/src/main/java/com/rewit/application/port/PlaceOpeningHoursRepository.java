package com.rewit.application.port;

import com.rewit.domain.model.PlaceOpeningHoursSchedule;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência para horários de funcionamento de locais reivindicados.
 */
public interface PlaceOpeningHoursRepository {

    /**
     * Busca o cronograma de horários configurado para o local informado.
     * Retorna vazio se o estabelecimento ainda não possui configuração de horários.
     */
    Optional<PlaceOpeningHoursSchedule> findScheduleByPlaceId(UUID placeId);

    /**
     * Persiste ou atualiza de forma atômica o cronograma semanal e seus respectivos intervalos.
     */
    void saveSchedule(PlaceOpeningHoursSchedule schedule);
}

