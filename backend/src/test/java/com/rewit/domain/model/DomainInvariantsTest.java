package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.EventStatus;
import com.rewit.domain.enums.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Invariantes e Regras do Domínio Rewit")
class DomainInvariantsTest {

    @Test
    @DisplayName("ReviewTarget deve rejeitar nota menor que 1.0 ou maior que 5.0")
    void shouldRejectInvalidRatingRange() {
        UUID reviewId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        // Nota 0.9 (abaixo de 1.0)
        BusinessException ex1 = assertThrows(BusinessException.class, () ->
                new ReviewTarget(null, reviewId, targetId, BigDecimal.valueOf(0.9), "Nota inválida")
        );
        assertEquals("INVALID_RATING_RANGE", ex1.getErrorCode());

        // Nota 5.1 (acima de 5.0)
        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                new ReviewTarget(null, reviewId, targetId, BigDecimal.valueOf(5.1), "Nota inválida")
        );
        assertEquals("INVALID_RATING_RANGE", ex2.getErrorCode());

        // Nota válida entre 1.0 e 5.0
        ReviewTarget validTarget = new ReviewTarget(null, reviewId, targetId, BigDecimal.valueOf(4.5), "Excelente");
        assertNotNull(validTarget.getId());
        assertEquals(BigDecimal.valueOf(4.5), validTarget.getRating());
    }

    @Test
    @DisplayName("Place deve rejeitar coordenadas fora dos limites do elipsoide WGS 84")
    void shouldRejectInvalidCoordinatesOnPlace() {
        // Latitude > 90
        assertThrows(BusinessException.class, () ->
                new Place(null, "Praça Central", "praca-central", "PARQUE", "Descrição",
                        "Rua 1", "Curitiba", "PR", "BR", 91.0, -49.27, 50, "USER", false, null, "ACTIVE")
        );

        // Longitude < -180
        assertThrows(BusinessException.class, () ->
                new Place(null, "Praça Central", "praca-central", "PARQUE", "Descrição",
                        "Rua 1", "Curitiba", "PR", "BR", -25.42, -181.0, 50, "USER", false, null, "ACTIVE")
        );

        // Raio de tolerância <= 0
        assertThrows(BusinessException.class, () ->
                new Place(null, "Praça Central", "praca-central", "PARQUE", "Descrição",
                        "Rua 1", "Curitiba", "PR", "BR", -25.42, -49.27, 0, "USER", false, null, "ACTIVE")
        );
    }

    @Test
    @DisplayName("UserFollow não deve permitir que o usuário siga a si mesmo")
    void shouldRejectSelfFollow() {
        UUID userId = UUID.randomUUID();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                new UserFollow(null, userId, userId)
        );
        assertEquals("SELF_FOLLOW_FORBIDDEN", ex.getErrorCode());
    }

    @Test
    @DisplayName("PlaceEvent não deve permitir data de término anterior à de início")
    void shouldRejectEventEndDateBeforeStartDate() {
        UUID placeId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant past = now.minus(2, ChronoUnit.HOURS);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                new PlaceEvent(null, placeId, "Festival de Música", "Festival", "SHOW",
                        now, past, EventStatus.SCHEDULED, null)
        );
        assertEquals("INVALID_EVENT_DATE_RANGE", ex.getErrorCode());
    }

    @Test
    @DisplayName("Promotion não deve permitir término anterior ao início")
    void shouldRejectPromotionEndDateBeforeStartDate() {
        UUID placeId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant past = now.minus(1, ChronoUnit.DAYS);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                new Promotion(null, placeId, null, "Black Friday", "Desconto 50%", "BF50", now, past)
        );
        assertEquals("INVALID_PROMOTION_DATES", ex.getErrorCode());
    }

    @Test
    @DisplayName("CheckIn deve rejeitar distância negativa e exigir review vinculado")
    void shouldValidateCheckInRules() {
        UUID placeId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Sem reviewId associado
        assertThrows(BusinessException.class, () ->
                new CheckIn(null, null, userId, placeId, -25.42, -49.27, 25.0, null)
        );

        // Distância negativa
        UUID reviewId = UUID.randomUUID();
        assertThrows(BusinessException.class, () ->
                new CheckIn(null, reviewId, userId, placeId, -25.42, -49.27, -5.0, null)
        );
    }

    @Test
    @DisplayName("Place, Product, PlaceService e PlaceEvent devem herdar de RateableTarget")
    void shouldInheritRateableTargetHierarchy() {
        Place place = new Place(null, "Café do Centro", "cafe-do-centro", "RESTAURANTE", "Cafeteria",
                "Rua XV", "Curitiba", "PR", "BR", -25.42, -49.27, 40, "USER", true, null, "ACTIVE");
        assertEquals(TargetType.PLACE, place.getTargetType());

        Product product = new Product(null, "Café Especial 250g", "Grão Real", "Torra Média",
                "Café artesanal", "ALIMENTOS", null, "ACTIVE");
        assertEquals(TargetType.PRODUCT, product.getTargetType());

        PlaceService service = new PlaceService(null, place.getId(), "Atendimento Balcão", "ATENDIMENTO", "Rápido", "ACTIVE");
        assertEquals(TargetType.SERVICE, service.getTargetType());

        PlaceEvent event = new PlaceEvent(null, place.getId(), "Degustação de Cafés", "Degustação guiada", "EVENTO",
                Instant.now(), Instant.now().plus(4, ChronoUnit.HOURS), EventStatus.SCHEDULED, null);
        assertEquals(TargetType.EVENT, event.getTargetType());
    }
}
