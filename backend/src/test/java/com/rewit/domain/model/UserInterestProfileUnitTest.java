package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.InterestCategory;
import com.rewit.domain.enums.InterestGroup;
import com.rewit.domain.enums.InterestOnboardingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.rewit.domain.enums.InterestCategory.BAR;
import static com.rewit.domain.enums.InterestCategory.CAFE;
import static com.rewit.domain.enums.InterestCategory.PARK;
import static com.rewit.domain.enums.InterestCategory.RESTAURANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Domínio: perfil de interesses e questionário inicial")
class UserInterestProfileUnitTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-10-27T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-27T10:05:00Z");
    private static final Instant T2 = Instant.parse("2026-10-27T11:00:00Z");

    @Test
    @DisplayName("Perfil novo começa em andamento, sem interesses e sem data de conclusão")
    void startIsInProgressAndEmpty() {
        UserInterestProfile profile = UserInterestProfile.start(USER, T0);

        assertEquals(InterestOnboardingStatus.IN_PROGRESS, profile.getOnboardingStatus());
        assertTrue(profile.getInterests().isEmpty());
        assertNull(profile.getOnboardingCompletedAt());
        assertEquals(T0, profile.getCreatedAt());
        assertEquals(T0, profile.getUpdatedAt());
    }

    @Test
    @DisplayName("Rascunho aceita qualquer quantidade, inclusive nenhuma, e continua em andamento")
    void draftAcceptsAnyAmount() {
        UserInterestProfile draft = UserInterestProfile.start(USER, T0).replaceInterests(List.of(CAFE), false, T1);
        assertEquals(Set.of(CAFE), draft.getInterests());
        assertEquals(InterestOnboardingStatus.IN_PROGRESS, draft.getOnboardingStatus());
        assertEquals(T1, draft.getUpdatedAt());
        assertEquals(T0, draft.getCreatedAt());

        UserInterestProfile empty = draft.replaceInterests(List.of(), false, T2);
        assertTrue(empty.getInterests().isEmpty());
        assertEquals(InterestOnboardingStatus.IN_PROGRESS, empty.getOnboardingStatus());
    }

    @Test
    @DisplayName("Concluir exige o mínimo: abaixo dele responde 422 INTERESTS_BELOW_MINIMUM")
    void completionRequiresMinimum() {
        UserInterestProfile profile = UserInterestProfile.start(USER, T0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> profile.replaceInterests(List.of(CAFE, BAR), true, T1));
        assertEquals("INTERESTS_BELOW_MINIMUM", ex.getErrorCode());
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, ex.getStatus());
        assertEquals(UserInterestProfile.MIN_INTERESTS_TO_COMPLETE, 3);
    }

    @Test
    @DisplayName("Conclusão grava a data da primeira confirmação; reenvio e edição posteriores a preservam")
    void completionDateIsTheFirstConfirmation() {
        UserInterestProfile completed = UserInterestProfile.start(USER, T0)
                .replaceInterests(List.of(CAFE, BAR, PARK), true, T1);
        assertEquals(InterestOnboardingStatus.COMPLETED, completed.getOnboardingStatus());
        assertEquals(T1, completed.getOnboardingCompletedAt());

        UserInterestProfile resent = completed.replaceInterests(List.of(CAFE, BAR, PARK), true, T2);
        assertEquals(T1, resent.getOnboardingCompletedAt());
        assertEquals(T2, resent.getUpdatedAt());

        UserInterestProfile edited = completed.replaceInterests(List.of(RESTAURANT, BAR, PARK), true, T2);
        assertEquals(Set.of(RESTAURANT, BAR, PARK), edited.getInterests());
        assertEquals(T1, edited.getOnboardingCompletedAt());
    }

    @Test
    @DisplayName("Depois de concluído, completeOnboarding=false não desfaz a conclusão e o mínimo continua valendo")
    void completionIsDefinitive() {
        UserInterestProfile completed = UserInterestProfile.start(USER, T0)
                .replaceInterests(List.of(CAFE, BAR, PARK), true, T1);

        UserInterestProfile stillCompleted = completed.replaceInterests(List.of(CAFE, BAR, RESTAURANT), false, T2);
        assertTrue(stillCompleted.isOnboardingCompleted());
        assertEquals(T1, stillCompleted.getOnboardingCompletedAt());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> completed.replaceInterests(List.of(CAFE), false, T2));
        assertEquals("INTERESTS_BELOW_MINIMUM", ex.getErrorCode());
    }

    @Test
    @DisplayName("Conta anterior ao questionário (concluída sem interesses) é lida, mas a próxima gravação exige o mínimo")
    void legacyCompletedProfileWithoutInterests() {
        UserInterestProfile legacy = new UserInterestProfile(USER, List.of(), T0, T0, T0);
        assertEquals(InterestOnboardingStatus.COMPLETED, legacy.getOnboardingStatus());
        assertTrue(legacy.getInterests().isEmpty());

        assertThrows(BusinessException.class, () -> legacy.replaceInterests(List.of(CAFE, BAR), false, T1));
        assertEquals(Set.of(CAFE, BAR, PARK),
                legacy.replaceInterests(List.of(CAFE, BAR, PARK), false, T1).getInterests());
    }

    @Test
    @DisplayName("fromCodes recusa lista ausente, código desconhecido, minúsculo, nulo e repetido com 400")
    void fromCodesRejectsInvalidInput() {
        assertCode("MISSING_INTERESTS", () -> UserInterestProfile.fromCodes(null));
        assertCode("INVALID_INTEREST_CODE", () -> UserInterestProfile.fromCodes(List.of("CAFE", "SAUNA")));
        assertCode("INVALID_INTEREST_CODE", () -> UserInterestProfile.fromCodes(List.of("cafe")));
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertCode("INVALID_INTEREST_CODE", () -> UserInterestProfile.fromCodes(withNull));
        assertCode("DUPLICATE_INTEREST", () -> UserInterestProfile.fromCodes(List.of("CAFE", "BAR", "CAFE")));

        assertEquals(List.of(BAR, CAFE), UserInterestProfile.fromCodes(List.of("BAR", "CAFE")));
        assertTrue(UserInterestProfile.fromCodes(List.of()).isEmpty());
    }

    @Test
    @DisplayName("Interesses ficam na ordem do catálogo, qualquer que seja a ordem recebida")
    void interestsAreKeptInCatalogOrder() {
        UserInterestProfile profile = UserInterestProfile.start(USER, T0)
                .replaceInterests(List.of(PARK, BAR, RESTAURANT), true, T1);
        assertEquals(List.of(RESTAURANT, BAR, PARK), new ArrayList<>(profile.getInterests()));
    }

    @Test
    @DisplayName("Usuário é obrigatório")
    void userIsRequired() {
        assertCode("MISSING_USER_ID", () -> UserInterestProfile.start(null, T0));
    }

    @Test
    @DisplayName("Catálogo: toda categoria pertence a exatamente uma pergunta, toda pergunta tem opções e cabem no banco")
    void catalogGroupsCoverEveryCategoryOnce() {
        Set<InterestCategory> grouped = EnumSet.noneOf(InterestCategory.class);
        for (InterestGroup group : InterestGroup.values()) {
            List<InterestCategory> options = InterestCategory.ofGroup(group);
            assertFalse(options.isEmpty(), "pergunta sem opções: " + group);
            for (InterestCategory option : options) {
                assertTrue(grouped.add(option), "categoria em mais de uma pergunta: " + option);
            }
        }
        assertEquals(EnumSet.allOf(InterestCategory.class), grouped);
        assertTrue(InterestCategory.values().length >= UserInterestProfile.MIN_INTERESTS_TO_COMPLETE);
        // category_code VARCHAR(64) e interest_name VARCHAR(128) (V1)
        assertTrue(Arrays.stream(InterestCategory.values()).allMatch(c -> c.getCode().length() <= 64
                && !c.getLabel().isBlank() && c.getLabel().length() <= 128));
    }

    private static void assertCode(String code, Runnable action) {
        BusinessException ex = assertThrows(BusinessException.class, action::run);
        assertEquals(code, ex.getErrorCode());
    }
}
