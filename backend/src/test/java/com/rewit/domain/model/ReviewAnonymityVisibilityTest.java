package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Testes de Domínio: anonimato incompatível com visibilidade FOLLOWERS")
class ReviewAnonymityVisibilityTest {

    private static final String CODE = "ANONYMOUS_FOLLOWERS_VISIBILITY_NOT_ALLOWED";
    private static final List<String> VISIBILITIES = List.of("PUBLIC", "FOLLOWERS", "PRIVATE");

    private static Review newReview(boolean anonymous, String visibility) {
        return new Review(UUID.randomUUID(), UUID.randomUUID(), null, "Texto original", anonymous, visibility, null, null, null);
    }

    private static Review legacyAnonymousFollowers() {
        Instant now = Instant.now();
        return new Review(UUID.randomUUID(), UUID.randomUUID(), null, "Texto original", true, false,
                ReviewStatus.ACTIVE, "FOLLOWERS", null, null, null, now, now);
    }

    private static boolean isContradictory(boolean anonymous, String visibility) {
        return anonymous && "FOLLOWERS".equals(visibility);
    }

    private static void assertRejected(Runnable action) {
        BusinessException ex = assertThrows(BusinessException.class, action::run);
        assertEquals(CODE, ex.getErrorCode());
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, ex.getStatus());
    }

    private static void assertUnchanged(Review review, boolean anonymous, String visibility) {
        assertEquals(anonymous, review.isAnonymous());
        assertEquals(visibility, review.getVisibility());
        assertEquals("Texto original", review.getExperienceText());
    }

    /** Todas as transições explícitas (anonimato e visibilidade enviados) a partir de cada estado válido. */
    static Stream<Arguments> explicitTransitions() {
        List<Arguments> cases = new ArrayList<>();
        for (boolean fromAnonymous : List.of(true, false)) {
            for (String fromVisibility : VISIBILITIES) {
                if (isContradictory(fromAnonymous, fromVisibility)) {
                    continue;
                }
                for (boolean toAnonymous : List.of(true, false)) {
                    for (String toVisibility : VISIBILITIES) {
                        cases.add(Arguments.of(fromAnonymous, fromVisibility, toAnonymous, toVisibility));
                    }
                }
            }
        }
        return cases.stream();
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

    @ParameterizedTest(name = "de anônima={0}/{1} para anônima={2}/{3}")
    @MethodSource("explicitTransitions")
    @DisplayName("editContent: toda transição explícita vale, exceto a que chega à combinação; rejeitada não altera nada")
    void explicitTransitionMatrix(boolean fromAnonymous, String fromVisibility, boolean toAnonymous, String toVisibility) {
        Review review = newReview(fromAnonymous, fromVisibility);

        if (isContradictory(toAnonymous, toVisibility)) {
            assertRejected(() -> review.editContent("Novo", null, toAnonymous, toVisibility, Instant.now()));
            assertUnchanged(review, fromAnonymous, fromVisibility);
        } else {
            review.editContent("Novo", null, toAnonymous, toVisibility, Instant.now());
            assertEquals(toAnonymous, review.isAnonymous());
            assertEquals(toVisibility, review.getVisibility());
            assertEquals("Novo", review.getExperienceText());
        }
    }

    @Test
    @DisplayName("PATCH parcial: só visibilidade FOLLOWERS numa anônima é rejeitado, sem alterar nada")
    void partialVisibilityOnAnonymousReviewIsRejected() {
        Review review = newReview(true, "PUBLIC");
        assertRejected(() -> review.editContent("Novo", null, null, "FOLLOWERS", Instant.now()));
        assertUnchanged(review, true, "PUBLIC");
    }

    @Test
    @DisplayName("PATCH parcial: só anonimato numa FOLLOWERS é rejeitado, sem alterar nada")
    void partialAnonymityOnFollowersReviewIsRejected() {
        Review review = newReview(false, "FOLLOWERS");
        assertRejected(() -> review.editContent("Novo", null, true, null, Instant.now()));
        assertUnchanged(review, false, "FOLLOWERS");
    }

    @Test
    @DisplayName("PATCH parcial válido não depende da ordem: identificada FOLLOWERS vira anônima PUBLIC numa única edição")
    void validCombinedTransitionDoesNotDependOnOrder() {
        Review review = newReview(false, "FOLLOWERS");
        review.editContent("Texto original", null, true, "PUBLIC", Instant.now());
        assertTrue(review.isAnonymous());
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
    @DisplayName("Linha antiga: edição só de conteúdo continua permitida e mantém a combinação")
    void legacyRowAcceptsContentOnlyEdit() {
        Review legacy = legacyAnonymousFollowers();
        legacy.editContent("Texto corrigido", null, null, null, Instant.now());
        assertEquals("Texto corrigido", legacy.getExperienceText());
        assertTrue(legacy.isAnonymousWithFollowersVisibility());
    }

    @Test
    @DisplayName("Linha antiga: reafirmar a combinação, total ou parcialmente, é rejeitado sem alterar nada")
    void legacyRowCannotReaffirmTheCombination() {
        Review legacy = legacyAnonymousFollowers();

        assertRejected(() -> legacy.editContent("Novo", null, true, "FOLLOWERS", Instant.now()));
        assertRejected(() -> legacy.editContent("Novo", null, true, null, Instant.now()));
        assertRejected(() -> legacy.editContent("Novo", null, null, "followers", Instant.now()));

        assertUnchanged(legacy, true, "FOLLOWERS");
    }

    static Stream<Arguments> legacyExits() {
        return Stream.of(
                Arguments.of(false, null, false, "FOLLOWERS"),
                Arguments.of(null, "PUBLIC", true, "PUBLIC"),
                Arguments.of(null, "PRIVATE", true, "PRIVATE"),
                Arguments.of(true, "PUBLIC", true, "PUBLIC"),
                Arguments.of(false, "FOLLOWERS", false, "FOLLOWERS"),
                Arguments.of(false, "PUBLIC", false, "PUBLIC"));
    }

    @ParameterizedTest(name = "anônima={0}, visibilidade={1}")
    @MethodSource("legacyExits")
    @DisplayName("Linha antiga: o autor pode sair da combinação para qualquer estado válido")
    void legacyRowCanLeaveTheCombination(Boolean anonymous, String visibility, boolean expectedAnonymous, String expectedVisibility) {
        Review legacy = legacyAnonymousFollowers();
        legacy.editContent("Texto corrigido", null, anonymous, visibility, Instant.now());

        assertEquals(expectedAnonymous, legacy.isAnonymous());
        assertEquals(expectedVisibility, legacy.getVisibility());
        assertFalse(legacy.isAnonymousWithFollowersVisibility());
    }

    @Test
    @DisplayName("Não há mutador público isolado de anonimato ou visibilidade (só criação e editContent os alteram)")
    void noIsolatedPrivacyMutators() {
        Set<String> forbidden = Set.of("updateAnonymous", "updateVisibility", "setAnonymous", "setVisibility", "setIsAnonymous");
        List<String> found = Arrays.stream(Review.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .filter(forbidden::contains)
                .toList();
        assertEquals(List.of(), found);
    }
}
