package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.CheckInStatus;
import com.rewit.domain.enums.VerificationMethod;
import com.rewit.domain.model.CheckIn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários: mapeamento da distância do CheckIn para NUMERIC(7,2) (Step 29.2)")
class CheckInJpaEntityMappingTest {

    @Test
    @DisplayName("A distância do domínio é gravada com escala 2 e arredondamento meio para cima")
    void distanceIsRoundedToColumnScale() {
        assertEquals(new BigDecimal("111.13"), CheckInJpaEntity.fromDomain(checkIn(111.126)).getDistanceToCentroidMeters());
        assertEquals(new BigDecimal("12.35"), CheckInJpaEntity.fromDomain(checkIn(12.345)).getDistanceToCentroidMeters());
        assertEquals(new BigDecimal("0.00"), CheckInJpaEntity.fromDomain(checkIn(0.004)).getDistanceToCentroidMeters());
        assertEquals(new BigDecimal("50.00"), CheckInJpaEntity.fromDomain(checkIn(50)).getDistanceToCentroidMeters());
    }

    @Test
    @DisplayName("A volta para o domínio mantém o valor armazenado como double")
    void storedDistanceRoundTripsToDomain() {
        CheckIn restored = CheckInJpaEntity.fromDomain(checkIn(87.5)).toDomain();

        assertEquals(87.5, restored.getDistanceToCentroidMeters());
    }

    private static CheckIn checkIn(double distanceMeters) {
        return new CheckIn(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                -23.5505, -46.6333, distanceMeters, CheckInStatus.VERIFIED, VerificationMethod.GPS, Instant.now());
    }
}
