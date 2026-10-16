package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Testes de Domínio: anonimato incompatível com visibilidade FOLLOWERS")
class ReviewAnonymityVisibilityTest {

    private static final String CODE = "ANONYMOUS_FOLLOWERS_VISIBILITY_NOT_ALLOWED";

    private static Review newReview(boolean anonymous, String visibility) {
        return new Review(UUID.randomUUID(), UUID.randomUUID(), null, "Texto original", anonymous, visibility, null, null, null);
    }

    private static Review legacyAnonymousFollowers() {
        Instant now = Instant.now();
        return new Review(UUID.randomUUID(), UUID.randomUUID(), null, "Texto original", true, false,
                ReviewStatus.ACTIVE, "FOLLOWERS", null, null, null, now, now);
    }

    private static void assertRejected(Runnable action) {
        BusinessException ex = assertThrows(BusinessException.class, action::run);
        assertEquals(CODE, ex.getErrorCode());
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, ex.getStatus());
    }

    @Test
    @DisplayName("Criação rejeita anônima com FOLLOWERS, inclusive com caixa e espaços diferentes")
    void creationRejectsAnonymousFollowers() {
        assertRejected(() -> newReview(true, "FOLLOWERS"));
        assertRejected(() -> newReview(true, " followers "));
    }

    @Test
    @DisplayName("Criação aceita as demais combinações")
    void creationAcceptsOtherCombinations() {
        assertDoesNotThrow(() -> newReview(true, "PUBLIC"));
        assertDoesNotThrow(() -> newReview(true, "PRIVATE"));
        assertDoesNotThrow(() -> newReview(true, null));
        assertDoesNotThrow(() -> newReview(false, "FOLLOWERS"));
    }

    @Test
    @DisplayName("Edição rejeita tornar FOLLOWERS uma anônima, sem alterar nenhum campo")
    void editRejectsFollowersOnAnonymousReview() {
        Review review = newReview(true, "PUBLIC");
        assertRejected(() -> review.editContent("Novo", null, null, "FOLLOWERS", Instant.now()));
        assertEquals("PUBLIC", review.getVisibility());
        assertEquals("Texto original", review.getExperienceText());
        assertTrue(review.isAnonymous());
    }

    @Test
    @DisplayName("Edição rejeita tornar anônima uma FOLLOWERS, sem alterar nenhum campo")
    void editRejectsAnonymityOnFollowersReview() {
        Review review = newReview(false, "FOLLOWERS");
        assertRejected(() -> review.editContent("Novo", null, true, null, Instant.now()));
        assertFalse(review.isAnonymous());
        assertEquals("FOLLOWERS", review.getVisibility());
        assertEquals("Texto original", review.getExperienceText());
    }

    @Test
    @DisplayName("Edição rejeita estabelecer as duas propriedades de uma vez")
    void editRejectsBothAtOnce() {
        Review review = newReview(false, "PUBLIC");
        assertRejected(() -> review.editContent("Novo", null, true, "FOLLOWERS", Instant.now()));
        assertFalse(review.isAnonymous());
        assertEquals("PUBLIC", review.getVisibility());
    }

    @Test
    @DisplayName("Linha antiga anônima FOLLOWERS é reconstituída e reconhecida")
    void legacyRowIsRehydrated() {
        Review legacy = assertDoesNotThrow(ReviewAnonymityVisibilityTest::legacyAnonymousFollowers);
        assertTrue(legacy.isAnonymousWithFollowersVisibility());
        assertFalse(newReview(false, "FOLLOWERS").isAnonymousWithFollowersVisibility());
        assertFalse(newReview(true, "PUBLIC").isAnonymousWithFollowersVisibility());
    }

    @Test
    @DisplayName("Linha antiga segue editável sem mudar a combinação e pode deixá-la")
    void legacyRowStaysEditableAndCanLeaveTheCombination() {
        Review legacy = legacyAnonymousFollowers();
        legacy.editContent("Texto corrigido", null, null, null, Instant.now());
        assertEquals("Texto corrigido", legacy.getExperienceText());
        assertTrue(legacy.isAnonymousWithFollowersVisibility());

        legacy.editContent("Texto corrigido", null, null, "PRIVATE", Instant.now());
        assertFalse(legacy.isAnonymousWithFollowersVisibility());
        assertEquals("PRIVATE", legacy.getVisibility());
    }
}
