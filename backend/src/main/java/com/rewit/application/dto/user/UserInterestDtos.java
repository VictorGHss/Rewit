package com.rewit.application.dto.user;

import com.rewit.domain.enums.InterestCategory;
import com.rewit.domain.enums.InterestGroup;
import com.rewit.domain.enums.InterestOnboardingStatus;
import com.rewit.domain.model.UserInterestProfile;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * DTOs da camada de aplicação para os interesses do usuário e o questionário inicial.
 */
public final class UserInterestDtos {

    private UserInterestDtos() {
    }

    /**
     * @param completeOnboarding confirma o questionário; nulo equivale a {@code false} (rascunho)
     */
    public record UpdateMyInterestsCommand(
            UUID actorUserId,
            List<String> interests,
            Boolean completeOnboarding
    ) {
    }

    public record InterestOptionView(String code, String label) {
        public static InterestOptionView fromDomain(InterestCategory category) {
            return new InterestOptionView(category.getCode(), category.getLabel());
        }
    }

    public record InterestQuestionView(String code, String title, String question, List<InterestOptionView> options) {
        public static InterestQuestionView fromDomain(InterestGroup group) {
            return new InterestQuestionView(group.getCode(), group.getTitle(), group.getQuestion(),
                    InterestCategory.ofGroup(group).stream().map(InterestOptionView::fromDomain).toList());
        }
    }

    /** Catálogo completo: perguntas na ordem do questionário, cada uma com as opções na ordem do catálogo. */
    public record InterestCatalogView(int minInterestsToComplete, List<InterestQuestionView> questions) {
        public static InterestCatalogView current() {
            return new InterestCatalogView(UserInterestProfile.MIN_INTERESTS_TO_COMPLETE,
                    Arrays.stream(InterestGroup.values()).map(InterestQuestionView::fromDomain).toList());
        }
    }

    public record UserInterestProfileView(
            InterestOnboardingStatus onboardingStatus,
            Instant onboardingCompletedAt,
            List<InterestOptionView> interests
    ) {
        public static UserInterestProfileView fromDomain(UserInterestProfile profile) {
            return new UserInterestProfileView(profile.getOnboardingStatus(), profile.getOnboardingCompletedAt(),
                    profile.getInterests().stream().map(InterestOptionView::fromDomain).toList());
        }

        public static UserInterestProfileView notStarted() {
            return new UserInterestProfileView(InterestOnboardingStatus.NOT_STARTED, null, List.of());
        }
    }
}
