package com.rewit.domain.model;

import com.rewit.domain.enums.PresenceVerificationOutcome;

import java.time.Duration;

/**
 * Regras da verificação de presença física (check-in V1). O backend é a única autoridade: o app envia as coordenadas
 * e a precisão do GPS e exibe o resultado devolvido, sem repetir estes limites.
 *
 * <ul>
 *   <li>Raio de validação de um local: até {@link #MAX_VALIDATION_RADIUS_METERS} metros (padrão
 *       {@link #DEFAULT_VALIDATION_RADIUS_METERS}), garantido também por CHECK no banco (V41).</li>
 *   <li>Precisão horizontal do GPS: até {@link #MAX_HORIZONTAL_ACCURACY_METERS} metros. Ausente ou inválida não
 *       verifica.</li>
 *   <li>Frequência: no máximo um check-in verificado por usuário e local em {@link #VERIFIED_CHECK_IN_WINDOW}
 *       móveis.</li>
 * </ul>
 */
public final class PresenceVerificationPolicy {

    public static final int MAX_VALIDATION_RADIUS_METERS = 250;
    public static final int DEFAULT_VALIDATION_RADIUS_METERS = 50;
    public static final int MAX_HORIZONTAL_ACCURACY_METERS = 100;
    public static final Duration VERIFIED_CHECK_IN_WINDOW = Duration.ofHours(24);

    private PresenceVerificationPolicy() {
    }

    /**
     * Avalia precisão e distância, antes da regra de frequência. {@code withinPlaceRadius} vem do PostGIS
     * ({@code ST_DWithin} com o raio do local); a distância também é limitada ao raio máximo, para que um raio
     * gravado acima dele nunca amplie a área de verificação.
     *
     * @return {@link PresenceVerificationOutcome#VERIFIED} se a presença pode ser confirmada, sujeita ainda à
     *         regra de frequência; senão, o motivo da recusa
     */
    public static PresenceVerificationOutcome evaluateLocation(Double accuracyMeters, double distanceMeters,
                                                               boolean withinPlaceRadius) {
        if (accuracyMeters == null || !Double.isFinite(accuracyMeters) || accuracyMeters < 0) {
            return PresenceVerificationOutcome.ACCURACY_UNAVAILABLE;
        }
        if (accuracyMeters > MAX_HORIZONTAL_ACCURACY_METERS) {
            return PresenceVerificationOutcome.ACCURACY_TOO_LOW;
        }
        if (!withinPlaceRadius || !(distanceMeters <= MAX_VALIDATION_RADIUS_METERS)) {
            return PresenceVerificationOutcome.OUTSIDE_RADIUS;
        }
        return PresenceVerificationOutcome.VERIFIED;
    }
}
