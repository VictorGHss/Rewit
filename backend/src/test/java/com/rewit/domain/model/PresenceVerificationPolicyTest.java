package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.PresenceVerificationOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Política de verificação de presença (check-in V1)")
class PresenceVerificationPolicyTest {

    @Test
    @DisplayName("Limites da V1: raio de 250 m (padrão 50 m), precisão de 100 m e janela de 24 horas")
    void limits() {
        assertEquals(250, PresenceVerificationPolicy.MAX_VALIDATION_RADIUS_METERS);
        assertEquals(50, PresenceVerificationPolicy.DEFAULT_VALIDATION_RADIUS_METERS);
        assertEquals(100, PresenceVerificationPolicy.MAX_HORIZONTAL_ACCURACY_METERS);
        assertEquals(Duration.ofHours(24), PresenceVerificationPolicy.VERIFIED_CHECK_IN_WINDOW);
    }

    @Test
    @DisplayName("Precisão até 100 m dentro do raio verifica; acima de 100 m não")
    void accuracyBoundary() {
        assertEquals(PresenceVerificationOutcome.VERIFIED,
                PresenceVerificationPolicy.evaluateLocation(100.0, 10.0, true));
        assertEquals(PresenceVerificationOutcome.VERIFIED,
                PresenceVerificationPolicy.evaluateLocation(0.0, 10.0, true));
        assertEquals(PresenceVerificationOutcome.ACCURACY_TOO_LOW,
                PresenceVerificationPolicy.evaluateLocation(100.01, 10.0, true));
    }

    @Test
    @DisplayName("Precisão ausente, negativa ou não numérica nunca verifica")
    void missingOrInvalidAccuracy() {
        for (Double accuracy : new Double[] {null, -0.5, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(PresenceVerificationOutcome.ACCURACY_UNAVAILABLE,
                    PresenceVerificationPolicy.evaluateLocation(accuracy, 10.0, true), String.valueOf(accuracy));
        }
    }

    @Test
    @DisplayName("Fora do raio do local não verifica, nem com raio legado maior que 250 m")
    void outsideRadius() {
        assertEquals(PresenceVerificationOutcome.OUTSIDE_RADIUS,
                PresenceVerificationPolicy.evaluateLocation(5.0, 300.0, false));
        // PostGIS diria "dentro" de um raio gravado acima do teto: o teto ainda vale
        assertEquals(PresenceVerificationOutcome.OUTSIDE_RADIUS,
                PresenceVerificationPolicy.evaluateLocation(5.0, 250.5, true));
        assertEquals(PresenceVerificationOutcome.OUTSIDE_RADIUS,
                PresenceVerificationPolicy.evaluateLocation(5.0, Double.NaN, true));
        assertEquals(PresenceVerificationOutcome.VERIFIED,
                PresenceVerificationPolicy.evaluateLocation(5.0, 250.0, true));
    }

    @Test
    @DisplayName("Local novo: raio padrão sem valor, aceita até 250 m e recusa zero, negativo ou acima do teto")
    void newPlaceRadius() {
        assertEquals(50, Place.newPlaceValidationRadius(null));
        assertEquals(1, Place.newPlaceValidationRadius(1));
        assertEquals(250, Place.newPlaceValidationRadius(250));

        BusinessException tooLarge = assertThrows(BusinessException.class, () -> Place.newPlaceValidationRadius(251));
        assertEquals("VALIDATION_RADIUS_TOO_LARGE", tooLarge.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, tooLarge.getStatus());

        for (int invalid : new int[] {0, -10}) {
            assertEquals("INVALID_RADIUS",
                    assertThrows(BusinessException.class, () -> Place.newPlaceValidationRadius(invalid)).getErrorCode());
        }
    }
}
