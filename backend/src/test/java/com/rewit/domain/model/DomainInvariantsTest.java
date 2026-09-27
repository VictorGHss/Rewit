package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.CheckInStatus;
import com.rewit.domain.enums.EventStatus;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.enums.VerificationMethod;
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

    @Test
    @DisplayName("CheckIn deve iniciar como PENDING por padrão com verifiedAt nulo")
    void shouldStartCheckInAsPendingWithNullVerifiedAt() {
        UUID reviewId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();

        // Criação com status nulo padrão
        CheckIn checkIn = new CheckIn(null, reviewId, userId, placeId, -25.42, -49.27, 10.0, null);

        assertEquals(CheckInStatus.PENDING, checkIn.getStatus(), "Novo CheckIn deve iniciar como PENDING");
        assertNull(checkIn.getVerifiedAt(), "CheckIn PENDING deve possuir verifiedAt estritamente null");
        assertEquals(VerificationMethod.GPS, checkIn.getVerificationMethod(), "Método padrão deve ser GPS");
    }

    @Test
    @DisplayName("CheckIn deve gerenciar transições de verificação (verify e reject) e consistência de verifiedAt")
    void shouldManageCheckInVerificationTransitionsAndVerifiedAt() {
        UUID reviewId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();

        CheckIn checkIn = new CheckIn(null, reviewId, userId, placeId, -25.42, -49.27, 15.0, CheckInStatus.PENDING);
        assertNull(checkIn.getVerifiedAt());

        // Transição explícita para VERIFIED
        checkIn.verify();
        assertEquals(CheckInStatus.VERIFIED, checkIn.getStatus());
        assertNotNull(checkIn.getVerifiedAt(), "CheckIn VERIFIED deve possuir verifiedAt preenchido");

        // Transição para REJECTED
        checkIn.reject();
        assertEquals(CheckInStatus.REJECTED, checkIn.getStatus());
        assertNull(checkIn.getVerifiedAt(), "CheckIn REJECTED não pode possuir verifiedAt");

        // Tentativa de instanciar PENDING com verifiedAt não-nulo deve ser rejeitada
        BusinessException exPending = assertThrows(BusinessException.class, () ->
                new CheckIn(null, reviewId, userId, placeId, -25.42, -49.27, 15.0,
                        CheckInStatus.PENDING, VerificationMethod.GPS, Instant.now())
        );
        assertEquals("INVALID_VERIFIED_AT", exPending.getErrorCode());

        // Tentativa de instanciar REJECTED com verifiedAt não-nulo deve ser rejeitada
        BusinessException exRejected = assertThrows(BusinessException.class, () ->
                new CheckIn(null, reviewId, userId, placeId, -25.42, -49.27, 15.0,
                        CheckInStatus.REJECTED, VerificationMethod.GPS, Instant.now())
        );
        assertEquals("INVALID_VERIFIED_AT", exRejected.getErrorCode());
    }

    @Test
    @DisplayName("VerificationMethod pode ser especificado sem significar aprovação automática")
    void shouldAllowVerificationMethodWithoutAutomaticApproval() {
        UUID reviewId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();

        // Check-in com QR_CODE ou NFC deve iniciar PENDING se status for null
        CheckIn qrCheckIn = new CheckIn(null, reviewId, userId, placeId, -25.42, -49.27, 5.0,
                null, VerificationMethod.QR_CODE);

        assertEquals(VerificationMethod.QR_CODE, qrCheckIn.getVerificationMethod());
        assertEquals(CheckInStatus.PENDING, qrCheckIn.getStatus(), "Método QR_CODE não implica aprovação automática");
        assertNull(qrCheckIn.getVerifiedAt(), "verifiedAt deve ser null para CheckIn PENDING com QR_CODE");
    }

    @Test
    @DisplayName("CheckIn inconsistente com Review deve ser rejeitado")
    void shouldEnforceCheckInAndReviewConsistency() {
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        UUID anotherPlaceId = UUID.randomUUID();
        UUID anotherUserId = UUID.randomUUID();

        // 1. Review com context_place_id preenchido
        Review reviewWithPlace = new Review(null, userId, placeId, "Excelente restaurante", false,
                -25.42, -49.27, 10.0);

        // Check-in consistente criado via factory/construtor de Review
        CheckIn consistentCheckIn = new CheckIn(null, reviewWithPlace, -25.42, -49.27, 12.0, VerificationMethod.GPS);
        assertDoesNotThrow(() -> consistentCheckIn.validateConsistencyWith(reviewWithPlace));

        // Inconsistência 1: review_id diferente
        CheckIn wrongReviewCheckIn = new CheckIn(null, UUID.randomUUID(), userId, placeId, -25.42, -49.27, 12.0, CheckInStatus.PENDING);
        BusinessException ex1 = assertThrows(BusinessException.class, () ->
                wrongReviewCheckIn.validateConsistencyWith(reviewWithPlace));
        assertEquals("INCONSISTENT_CHECKIN_REVIEW", ex1.getErrorCode());

        // Inconsistência 2: user_id diferente
        CheckIn wrongUserCheckIn = new CheckIn(null, reviewWithPlace.getId(), anotherUserId, placeId, -25.42, -49.27, 12.0, CheckInStatus.PENDING);
        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                wrongUserCheckIn.validateConsistencyWith(reviewWithPlace));
        assertEquals("INCONSISTENT_CHECKIN_USER", ex2.getErrorCode());

        // Inconsistência 3: place_id diferente
        CheckIn wrongPlaceCheckIn = new CheckIn(null, reviewWithPlace.getId(), userId, anotherPlaceId, -25.42, -49.27, 12.0, CheckInStatus.PENDING);
        BusinessException ex3 = assertThrows(BusinessException.class, () ->
                wrongPlaceCheckIn.validateConsistencyWith(reviewWithPlace));
        assertEquals("INCONSISTENT_CHECKIN_PLACE", ex3.getErrorCode());

        // Inconsistência 4: Review sem context_place_id não pode receber CheckIn
        Review reviewWithoutPlace = new Review(null, userId, null, "Avaliação genérica de produto", false, null, null, null);
        assertThrows(BusinessException.class, () ->
                new CheckIn(null, reviewWithoutPlace, -25.42, -49.27, 10.0, VerificationMethod.GPS));

        CheckIn orphanPlaceCheckIn = new CheckIn(null, reviewWithoutPlace.getId(), userId, placeId, -25.42, -49.27, 10.0, CheckInStatus.PENDING);
        BusinessException ex4 = assertThrows(BusinessException.class, () ->
                orphanPlaceCheckIn.validateConsistencyWith(reviewWithoutPlace));
        assertEquals("CHECKIN_REQUIRES_CONTEXT_PLACE", ex4.getErrorCode());
    }

    @Test
    @DisplayName("Review sem ReviewTarget deve ser rejeitada como inválida no nível do agregado")
    void shouldRequireAtLeastOneReviewTargetOnReview() {
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        Review review = new Review(null, userId, placeId, "Texto da experiência", false, null, null, null);

        // Review sem alvos avaliados deve falhar na validação da invariante
        BusinessException ex = assertThrows(BusinessException.class, review::validateHasAtLeastOneTarget);
        assertEquals("REVIEW_WITHOUT_TARGET", ex.getErrorCode());

        // Adicionar alvo com nota válida
        ReviewTarget target = new ReviewTarget(null, review.getId(), placeId, BigDecimal.valueOf(4.5), "Ótimo ambiente");
        review.addTarget(target);

        // Agora a validação deve passar com sucesso
        assertDoesNotThrow(review::validateHasAtLeastOneTarget);
        assertEquals(1, review.getTargets().size());

        // Tentativa de adicionar target de outra review deve falhar
        ReviewTarget inconsistentTarget = new ReviewTarget(null, UUID.randomUUID(), placeId, BigDecimal.valueOf(5.0), null);
        assertThrows(BusinessException.class, () -> review.addTarget(inconsistentTarget));
    }

    @Test
    @DisplayName("CheckIn.status == VERIFIED deve ser a única fonte da verdade para isVerifiedOnSite na Review")
    void shouldUseCheckInAsSingleSourceOfTruthForVerifiedOnSite() {
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();

        Review review = new Review(null, userId, placeId, "Experiência no local", false, -25.42, -49.27, 15.0);
        assertFalse(review.isVerifiedOnSite(), "Novo review inicia com isVerifiedOnSite = false");

        // Anexar CheckIn PENDING: Review permanece não-verificada
        CheckIn pendingCheckIn = new CheckIn(null, review, -25.42, -49.27, 20.0, VerificationMethod.GPS);
        review.attachCheckIn(pendingCheckIn);
        assertFalse(review.isVerifiedOnSite(), "Review com CheckIn PENDING não pode ser verifiedOnSite");

        // Quando o CheckIn é efetivamente verificado
        pendingCheckIn.verify();
        review.attachCheckIn(pendingCheckIn);
        assertTrue(review.isVerifiedOnSite(), "Review com CheckIn VERIFIED deve refletir verifiedOnSite = true");

        // Quando o CheckIn é rejeitado
        pendingCheckIn.reject();
        review.attachCheckIn(pendingCheckIn);
        assertFalse(review.isVerifiedOnSite(), "Review com CheckIn REJECTED deve refletir verifiedOnSite = false");
    }

    @Test
    @DisplayName("Deve validar limites de latitude, longitude e precisão de localização")
    void shouldValidateLocationAndPrivacyInvariants() {
        UUID userId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();

        // Review com latitude inválida (> 90)
        assertThrows(BusinessException.class, () ->
                new Review(null, userId, placeId, "Texto", false, 91.0, -49.27, 10.0));

        // Review com longitude inválida (< -180)
        assertThrows(BusinessException.class, () ->
                new Review(null, userId, placeId, "Texto", false, -25.42, -181.0, 10.0));

        // Review com precisão de localização negativa
        BusinessException exAcc = assertThrows(BusinessException.class, () ->
                new Review(null, userId, placeId, "Texto", false, -25.42, -49.27, -1.0));
        assertEquals("INVALID_LOCATION_ACCURACY", exAcc.getErrorCode());

        // CheckIn com latitude inválida
        assertThrows(BusinessException.class, () ->
                new CheckIn(null, reviewId, userId, placeId, -91.0, -49.27, 10.0, CheckInStatus.PENDING));

        // CheckIn com longitude inválida
        assertThrows(BusinessException.class, () ->
                new CheckIn(null, reviewId, userId, placeId, -25.42, 181.0, 10.0, CheckInStatus.PENDING));
    }
}
