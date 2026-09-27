package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.CheckInStatus;
import com.rewit.domain.enums.SavedItemType;
import com.rewit.domain.enums.TagSource;
import com.rewit.domain.enums.VerificationMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Consolidação do Modelo de Domínio (Prompt 02)")
class DomainModelConsolidationTest {

    @Test
    @DisplayName("RateableTargetStats deve calcular e validar notas e contagens derivadas")
    void shouldValidateRateableTargetStats() {
        UUID targetId = UUID.randomUUID();

        // Inicialização válida
        RateableTargetStats stats = new RateableTargetStats(targetId, BigDecimal.valueOf(4.25), 10);
        assertEquals(targetId, stats.getTargetId());
        assertEquals(BigDecimal.valueOf(4.25), stats.getAverageRating());
        assertEquals(10, stats.getReviewsCount());
        assertNotNull(stats.getLastCalculatedAt());

        // Atualização válida
        stats.updateStats(BigDecimal.valueOf(4.75), 12);
        assertEquals(BigDecimal.valueOf(4.75), stats.getAverageRating());
        assertEquals(12, stats.getReviewsCount());

        // Rejeitar nota negativa
        BusinessException ex1 = assertThrows(BusinessException.class, () ->
                new RateableTargetStats(targetId, BigDecimal.valueOf(-0.1), 5)
        );
        assertEquals("INVALID_AVERAGE_RATING", ex1.getErrorCode());

        // Rejeitar nota acima de 5.0
        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                new RateableTargetStats(targetId, BigDecimal.valueOf(5.1), 5)
        );
        assertEquals("INVALID_AVERAGE_RATING", ex2.getErrorCode());

        // Rejeitar contagem negativa
        BusinessException ex3 = assertThrows(BusinessException.class, () ->
                new RateableTargetStats(targetId, BigDecimal.valueOf(4.0), -1)
        );
        assertEquals("INVALID_REVIEWS_COUNT", ex3.getErrorCode());

        // Rejeitar targetId nulo
        assertThrows(BusinessException.class, () ->
                new RateableTargetStats(null, BigDecimal.valueOf(4.0), 0)
        );
    }

    @Test
    @DisplayName("User deve suportar soft-delete e manter integridade de provedores")
    void shouldSupportUserSoftDelete() {
        User user = new User(null, "usuario@rewit.com", "hash_seguro", AuthProvider.GOOGLE, "google_sub_123");
        assertNotNull(user.getId());
        assertTrue(user.isActive());
        assertFalse(user.isDeleted());
        assertNull(user.getDeletedAt());

        // Executar soft delete
        user.softDelete();
        assertFalse(user.isActive());
        assertTrue(user.isDeleted());
        assertNotNull(user.getDeletedAt());
    }

    @Test
    @DisplayName("Place deve suportar número e bairro e manter construtor retrocompatível")
    void shouldSupportStructuredAddressOnPlace() {
        // Construtor completo com número e bairro
        Place place1 = new Place(null, "Padaria Central", "padaria-central", "PADARIA", "Pães artesanais",
                "Av. Paulista", "1000", "Bela Vista", "São Paulo", "SP", "BR",
                -23.56, -46.65, 60, "USER", true, null, "ACTIVE");
        assertEquals("1000", place1.getStreetNumber());
        assertEquals("Bela Vista", place1.getNeighborhood());

        // Construtor compatível
        Place place2 = new Place(null, "Parque Ibirapuera", "parque-ibirapuera", "PARQUE", "Parque público",
                "Av. Pedro Álvares Cabral", "São Paulo", "SP", "BR",
                -23.58, -46.65, 200, "USER", true, null, "ACTIVE");
        assertNull(place2.getStreetNumber());
        assertNull(place2.getNeighborhood());
    }

    @Test
    @DisplayName("CheckIn deve suportar métodos de validação presencial (GPS, QR_CODE, NFC, BEACON)")
    void shouldSupportCheckInVerificationMethods() {
        UUID reviewId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();

        // Check-in com QR_CODE
        CheckIn qrCheckIn = new CheckIn(null, reviewId, userId, placeId,
                -23.56, -46.65, 12.5, CheckInStatus.VERIFIED, VerificationMethod.QR_CODE);
        assertEquals(VerificationMethod.QR_CODE, qrCheckIn.getVerificationMethod());

        // Check-in com NFC
        CheckIn nfcCheckIn = new CheckIn(null, reviewId, userId, placeId,
                -23.56, -46.65, 5.0, CheckInStatus.VERIFIED, VerificationMethod.NFC);
        assertEquals(VerificationMethod.NFC, nfcCheckIn.getVerificationMethod());

        // Check-in retrocompatível (default GPS)
        CheckIn gpsCheckIn = new CheckIn(null, reviewId, userId, placeId,
                -23.56, -46.65, 30.0, CheckInStatus.VERIFIED);
        assertEquals(VerificationMethod.GPS, gpsCheckIn.getVerificationMethod());
    }

    @Test
    @DisplayName("UserInterest deve validar limites do peso de preferência (0.00 a 1.00)")
    void shouldValidateUserInterestWeight() {
        UUID userId = UUID.randomUUID();

        // Peso válido
        UserInterest validInterest = new UserInterest(null, userId, "Tecnologia", "TECH", BigDecimal.valueOf(0.85));
        assertEquals(BigDecimal.valueOf(0.85), validInterest.getWeight());

        // Peso padrão retrocompatível
        UserInterest defaultInterest = new UserInterest(null, userId, "Gastronomia", "FOOD");
        assertEquals(BigDecimal.ONE, defaultInterest.getWeight());

        // Peso inválido negativo
        BusinessException ex1 = assertThrows(BusinessException.class, () ->
                new UserInterest(null, userId, "Games", "GAMES", BigDecimal.valueOf(-0.1))
        );
        assertEquals("INVALID_INTEREST_WEIGHT", ex1.getErrorCode());

        // Peso inválido acima de 1.0
        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                new UserInterest(null, userId, "Cinema", "CINEMA", BigDecimal.valueOf(1.1))
        );
        assertEquals("INVALID_INTEREST_WEIGHT", ex2.getErrorCode());
    }

    @Test
    @DisplayName("Notification deve suportar metadados flexíveis em JSON e marcação de leitura")
    void shouldSupportNotificationMetadataAndReadStatus() {
        UUID userId = UUID.randomUUID();
        String metadata = "{\"reviewId\":\"" + UUID.randomUUID() + "\",\"reaction\":\"HELPFUL\"}";

        Notification notification = new Notification(null, userId, "NEW_REACTION",
                "Nova reação recebida", "Seu review recebeu uma reação útil.", "/reviews/1", metadata);

        assertEquals(metadata, notification.getMetadataJson());
        assertNull(notification.getReadAt());

        notification.markAsRead();
        assertNotNull(notification.getReadAt());
    }

    @Test
    @DisplayName("SavedItem deve suportar salvamento polimórfico de múltiplos tipos de entidades")
    void shouldSupportPolymorphicSavedItems() {
        UUID userId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        SavedItem savedPlace = new SavedItem(null, userId, targetId, SavedItemType.PLACE, "Lugares Favoritos");
        assertEquals(SavedItemType.PLACE, savedPlace.getItemType());
        assertEquals("Lugares Favoritos", savedPlace.getFolderName());

        SavedItem savedProduct = new SavedItem(null, userId, targetId, SavedItemType.PRODUCT, "Desejos");
        assertEquals(SavedItemType.PRODUCT, savedProduct.getItemType());

        SavedItem savedReview = new SavedItem(null, userId, targetId, SavedItemType.REVIEW, null);
        assertEquals("Geral", savedReview.getFolderName());
    }

    @Test
    @DisplayName("ReviewTag deve validar grau de confiança entre 0.00 e 1.00 e suportar fontes variadas")
    void shouldValidateReviewTagConfidenceAndSource() {
        UUID reviewId = UUID.randomUUID();
        UUID tagId = UUID.randomUUID();

        ReviewTag userTag = new ReviewTag(null, reviewId, tagId, TagSource.USER, BigDecimal.ONE);
        assertEquals(TagSource.USER, userTag.getSource());
        assertEquals(BigDecimal.ONE, userTag.getConfidence());

        ReviewTag aiTag = new ReviewTag(null, reviewId, tagId, TagSource.AI, BigDecimal.valueOf(0.92));
        assertEquals(TagSource.AI, aiTag.getSource());
        assertEquals(BigDecimal.valueOf(0.92), aiTag.getConfidence());

        // Confiança acima de 1.0
        assertThrows(BusinessException.class, () ->
                new ReviewTag(null, reviewId, tagId, TagSource.AI, BigDecimal.valueOf(1.05))
        );

        // Confiança abaixo de 0.0
        assertThrows(BusinessException.class, () ->
                new ReviewTag(null, reviewId, tagId, TagSource.RULE, BigDecimal.valueOf(-0.01))
        );
    }
}
