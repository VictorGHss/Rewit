package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.CheckInStatus;
import com.rewit.domain.enums.PresenceVerificationOutcome;
import com.rewit.domain.enums.VerificationMethod;
import com.rewit.domain.model.CheckIn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários: mapeamento do CheckIn entre JPA e domínio (Step 29.2 / minimização GPS)")
class CheckInJpaEntityMappingTest {

    @Test
    @DisplayName("A distância do domínio é gravada com escala 2 e arredondamento meio para cima")
    void distanceIsRoundedToColumnScale() {
        assertEquals(new BigDecimal("111.13"), CheckInJpaEntity.fromDomain(checkIn(111.126)).getDistanceToCentroidMeters());
        assertEquals(new BigDecimal("12.35"), CheckInJpaEntity.fromDomain(checkIn(12.345)).getDistanceToCentroidMeters());
        assertEquals(new BigDecimal("0.00"), CheckInJpaEntity.fromDomain(checkIn(0.004)).getDistanceToCentroidMeters());
        assertEquals(new BigDecimal("50.00"), CheckInJpaEntity.fromDomain(checkIn(50.0)).getDistanceToCentroidMeters());
    }

    @Test
    @DisplayName("A volta para o domínio mantém o valor armazenado como Double")
    void storedDistanceRoundTripsToDomain() {
        CheckIn restored = CheckInJpaEntity.fromDomain(checkIn(87.5)).toDomain();

        assertEquals(87.5, restored.getDistanceToCentroidMeters());
    }

    @Test
    @DisplayName("Conversão JPA <-> domínio preserva null em coordenadas e distância sem fallback para (0.0, 0.0)")
    void nullCoordinatesAndDistanceRoundTripWithoutNullIslandFallback() {
        CheckIn verifiedWithoutCoords = new CheckIn(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                42.5,
                CheckInStatus.VERIFIED,
                VerificationMethod.GPS,
                Instant.now(),
                null
        );

        CheckInJpaEntity verifiedEntity = CheckInJpaEntity.fromDomain(verifiedWithoutCoords);
        assertNull(verifiedEntity.getCoordinates());
        assertEquals(new BigDecimal("42.50"), verifiedEntity.getDistanceToCentroidMeters());
        assertNull(verifiedEntity.getRejectionReason());

        CheckIn restoredVerified = verifiedEntity.toDomain();
        assertNull(restoredVerified.getLatitude());
        assertNull(restoredVerified.getLongitude());
        assertFalse(restoredVerified.hasCoordinates());
        assertEquals(42.5, restoredVerified.getDistanceToCentroidMeters());
        assertEquals(CheckInStatus.VERIFIED, restoredVerified.getStatus());
        assertNull(restoredVerified.getRejectionReason());

        CheckIn rejectedWithoutCoordsOrDistance = new CheckIn(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                null,
                CheckInStatus.REJECTED,
                VerificationMethod.GPS,
                null,
                PresenceVerificationOutcome.OUTSIDE_RADIUS
        );

        CheckInJpaEntity rejectedEntity = CheckInJpaEntity.fromDomain(rejectedWithoutCoordsOrDistance);
        assertNull(rejectedEntity.getCoordinates());
        assertNull(rejectedEntity.getDistanceToCentroidMeters());
        assertEquals("OUTSIDE_RADIUS", rejectedEntity.getRejectionReason());

        CheckIn restoredRejected = rejectedEntity.toDomain();
        assertNull(restoredRejected.getLatitude());
        assertNull(restoredRejected.getLongitude());
        assertNull(restoredRejected.getDistanceToCentroidMeters());
        assertEquals(CheckInStatus.REJECTED, restoredRejected.getStatus());
        assertEquals(PresenceVerificationOutcome.OUTSIDE_RADIUS, restoredRejected.getRejectionReason());
    }

    @Test
    @DisplayName("CheckInJpaEntity.fromDomain nunca converte coordenadas transitórias em Point e persiste distância apenas em VERIFIED")
    void fromDomainNeverPersistsTransientCoordinates() {
        CheckIn pending = new CheckIn(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                -25.4385,
                -49.2850,
                null,
                CheckInStatus.PENDING,
                VerificationMethod.GPS,
                null,
                null
        );
        CheckInJpaEntity pendingEntity = CheckInJpaEntity.fromDomain(pending);
        assertNull(pendingEntity.getCoordinates(), "PENDING não pode persistir coordenadas");
        assertNull(pendingEntity.getDistanceToCentroidMeters(), "PENDING não pode persistir distância");
        assertNull(pendingEntity.getRejectionReason());
        CheckIn restoredPending = pendingEntity.toDomain();
        assertNull(restoredPending.getLatitude());
        assertNull(restoredPending.getLongitude());
        assertNull(restoredPending.getDistanceToCentroidMeters());
        assertEquals(CheckInStatus.PENDING, restoredPending.getStatus());

        CheckIn withTransientCoords = new CheckIn(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                -25.4385,
                -49.2850,
                18.5,
                CheckInStatus.VERIFIED,
                VerificationMethod.GPS,
                Instant.now(),
                null
        );

        CheckInJpaEntity entity = CheckInJpaEntity.fromDomain(withTransientCoords);
        assertNull(entity.getCoordinates(), "Coordenadas transitórias não podem virar Point persistido");
        assertEquals(new BigDecimal("18.50"), entity.getDistanceToCentroidMeters());

        CheckIn restored = entity.toDomain();
        assertNull(restored.getLatitude());
        assertNull(restored.getLongitude());
        assertFalse(restored.hasCoordinates());
        assertEquals(18.5, restored.getDistanceToCentroidMeters());

        // Transição para REJECTED limpa distância tanto no domínio quanto na entidade JPA
        withTransientCoords.reject(PresenceVerificationOutcome.OUTSIDE_RADIUS);
        CheckInJpaEntity rejectedEntity = CheckInJpaEntity.fromDomain(withTransientCoords);
        assertNull(rejectedEntity.getCoordinates());
        assertNull(rejectedEntity.getDistanceToCentroidMeters());
        assertEquals("OUTSIDE_RADIUS", rejectedEntity.getRejectionReason());

        // Defesa adicional em toDomain(): linha legada/anômala PENDING com distância residual no objeto JPA não vaza para o domínio
        pendingEntity.setDistanceToCentroidMeters(new BigDecimal("99.00"));
        CheckIn sanitizedPending = pendingEntity.toDomain();
        assertNull(sanitizedPending.getDistanceToCentroidMeters());
    }

    private static CheckIn checkIn(Double distanceMeters) {
        return new CheckIn(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, null, distanceMeters, CheckInStatus.VERIFIED, VerificationMethod.GPS, Instant.now(), null);
    }
}
