package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.CheckInStatus;
import com.rewit.domain.enums.PresenceVerificationOutcome;
import com.rewit.domain.enums.VerificationMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários de Domínio: CheckIn com coordenadas anuláveis e motivo de rejeição")
class CheckInDomainTest {

    @Test
    @DisplayName("CheckIn sem coordenadas é válido para VERIFIED, REJECTED e PENDING")
    void checkInWithoutCoordinatesIsValidForAllowedStatuses() {
        UUID reviewId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();

        CheckIn verified = new CheckIn(null, reviewId, userId, placeId, null, null, 25.0,
                CheckInStatus.VERIFIED, VerificationMethod.GPS, Instant.now(), null);
        assertNull(verified.getLatitude());
        assertNull(verified.getLongitude());
        assertFalse(verified.hasCoordinates());
        assertEquals(25.0, verified.getDistanceToCentroidMeters());

        CheckIn rejected = new CheckIn(null, reviewId, userId, placeId, null, null, null,
                CheckInStatus.REJECTED, VerificationMethod.GPS, null, PresenceVerificationOutcome.OUTSIDE_RADIUS);
        assertNull(rejected.getLatitude());
        assertNull(rejected.getLongitude());
        assertNull(rejected.getDistanceToCentroidMeters());
        assertFalse(rejected.hasCoordinates());
        assertEquals(PresenceVerificationOutcome.OUTSIDE_RADIUS, rejected.getRejectionReason());

        CheckIn pending = new CheckIn(null, reviewId, userId, placeId, null, null, null,
                CheckInStatus.PENDING, VerificationMethod.GPS, null, null);
        assertNull(pending.getLatitude());
        assertNull(pending.getLongitude());
        assertFalse(pending.hasCoordinates());
    }

    @Test
    @DisplayName("Coordenadas incompletas (apenas latitude ou apenas longitude) ou não finitas são recusadas")
    void incompleteOrNonFiniteCoordinatesAreRejected() {
        UUID reviewId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();

        BusinessException onlyLat = assertThrows(BusinessException.class, () ->
                new CheckIn(null, reviewId, userId, placeId, -25.43, null, null, CheckInStatus.VERIFIED));
        assertEquals("INCOMPLETE_COORDINATES", onlyLat.getErrorCode());

        BusinessException onlyLon = assertThrows(BusinessException.class, () ->
                new CheckIn(null, reviewId, userId, placeId, null, -49.28, null, CheckInStatus.VERIFIED));
        assertEquals("INCOMPLETE_COORDINATES", onlyLon.getErrorCode());

        BusinessException nanLat = assertThrows(BusinessException.class, () ->
                new CheckIn(null, reviewId, userId, placeId, Double.NaN, -49.28, null, CheckInStatus.VERIFIED));
        assertEquals("INVALID_LATITUDE", nanLat.getErrorCode());

        BusinessException infLon = assertThrows(BusinessException.class, () ->
                new CheckIn(null, reviewId, userId, placeId, -25.43, Double.POSITIVE_INFINITY, null, CheckInStatus.VERIFIED));
        assertEquals("INVALID_LONGITUDE", infLon.getErrorCode());
    }

    @Test
    @DisplayName("Cálculos geográficos recusam operar quando coordenadas estão ausentes e calculam com coordenadas transitórias válidas")
    void geographicCalculationsRequireValidTransientCoordinates() {
        UUID reviewId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();

        Place place = new Place(placeId, "Café", "cafe", "CAFETERIA", null,
                "Rua XV", "Curitiba", "PR", "BR", -25.4385, -49.2850, 50, "USER", false, null, "ACTIVE");

        CheckIn withoutCoords = new CheckIn(null, reviewId, userId, placeId, null, null, null,
                CheckInStatus.VERIFIED, VerificationMethod.GPS, Instant.now(), null);

        BusinessException missingCoordsEx = assertThrows(BusinessException.class,
                () -> withoutCoords.distanceMetersTo(place));
        assertEquals("CHECKIN_COORDINATES_UNAVAILABLE", missingCoordsEx.getErrorCode());

        BusinessException missingRawEx = assertThrows(BusinessException.class,
                () -> withoutCoords.distanceMetersTo(-25.4385, -49.2850));
        assertEquals("CHECKIN_COORDINATES_UNAVAILABLE", missingRawEx.getErrorCode());

        CheckIn withTransientCoords = new CheckIn(null, reviewId, userId, placeId, -25.4395, -49.2850, null,
                CheckInStatus.PENDING, VerificationMethod.GPS, null, null);
        double distance = withTransientCoords.distanceMetersTo(place);
        assertTrue(distance > 100.0 && distance < 125.0, "Distância esperada ~111 m, obtida: " + distance);
    }

    @Test
    @DisplayName("rejectionReason só é permitido em REJECTED e apenas com motivos reais de PresenceVerificationOutcome")
    void rejectionReasonIsRestrictedToRejectedAndRealOutcomes() {
        UUID reviewId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();

        BusinessException onVerified = assertThrows(BusinessException.class, () ->
                new CheckIn(null, reviewId, userId, placeId, null, null, 10.0,
                        CheckInStatus.VERIFIED, VerificationMethod.GPS, Instant.now(),
                        PresenceVerificationOutcome.OUTSIDE_RADIUS));
        assertEquals("INVALID_REJECTION_REASON", onVerified.getErrorCode());

        BusinessException invalidOutcome = assertThrows(BusinessException.class, () ->
                new CheckIn(null, reviewId, userId, placeId, null, null, null,
                        CheckInStatus.REJECTED, VerificationMethod.GPS, null,
                        PresenceVerificationOutcome.VERIFIED));
        assertEquals("INVALID_REJECTION_REASON", invalidOutcome.getErrorCode());

        BusinessException notRequestedOutcome = assertThrows(BusinessException.class, () ->
                new CheckIn(null, reviewId, userId, placeId, null, null, null,
                        CheckInStatus.REJECTED, VerificationMethod.GPS, null,
                        PresenceVerificationOutcome.NOT_REQUESTED));
        assertEquals("INVALID_REJECTION_REASON", notRequestedOutcome.getErrorCode());

        BusinessException rejectedWithDistance = assertThrows(BusinessException.class, () ->
                new CheckIn(null, reviewId, userId, placeId, null, null, 120.0,
                        CheckInStatus.REJECTED, VerificationMethod.GPS, null,
                        PresenceVerificationOutcome.OUTSIDE_RADIUS));
        assertEquals("NON_VERIFIED_CHECKIN_DISTANCE_FORBIDDEN", rejectedWithDistance.getErrorCode());

        BusinessException pendingWithDistance = assertThrows(BusinessException.class, () ->
                new CheckIn(null, reviewId, userId, placeId, null, null, 45.0,
                        CheckInStatus.PENDING, VerificationMethod.GPS, null, null));
        assertEquals("NON_VERIFIED_CHECKIN_DISTANCE_FORBIDDEN", pendingWithDistance.getErrorCode());
    }

    @Test
    @DisplayName("CheckIn.reject(reason) limpa distância transitória, verifiedAt e registra motivo real; verify preserva distância válida")
    void rejectClearsDistanceAndSetsRejectionReason() {
        CheckIn pending = new CheckIn(null, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                -25.43, -49.28, null, CheckInStatus.PENDING);

        pending.verify(Instant.now(), 35.0);
        assertEquals(CheckInStatus.VERIFIED, pending.getStatus());
        assertEquals(35.0, pending.getDistanceToCentroidMeters());
        assertNotNull(pending.getVerifiedAt());

        pending.reject(PresenceVerificationOutcome.ACCURACY_TOO_LOW);

        assertEquals(CheckInStatus.REJECTED, pending.getStatus());
        assertNull(pending.getVerifiedAt());
        assertNull(pending.getDistanceToCentroidMeters());
        assertEquals(PresenceVerificationOutcome.ACCURACY_TOO_LOW, pending.getRejectionReason());
    }
}
