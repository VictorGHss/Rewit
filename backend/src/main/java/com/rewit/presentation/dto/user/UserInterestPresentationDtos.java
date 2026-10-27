package com.rewit.presentation.dto.user;

import com.rewit.application.dto.user.UserInterestDtos.InterestCatalogView;
import com.rewit.application.dto.user.UserInterestDtos.InterestOptionView;
import com.rewit.application.dto.user.UserInterestDtos.InterestQuestionView;
import com.rewit.application.dto.user.UserInterestDtos.UpdateMyInterestsCommand;
import com.rewit.application.dto.user.UserInterestDtos.UserInterestProfileView;
import com.rewit.domain.enums.InterestCategory;
import com.rewit.domain.enums.InterestOnboardingStatus;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contratos REST dos interesses do usuário e do questionário inicial.
 */
public final class UserInterestPresentationDtos {

    /** Teto defensivo do payload: o catálogo inteiro sem repetição cabe nele. */
    public static final int MAX_INTERESTS = 50;

    private UserInterestPresentationDtos() {
    }

    @Schema(description = "Substituição integral dos interesses do usuário autenticado")
    public record UpdateMyInterestsRequest(
            @NotNull(message = "A lista de interesses é obrigatória")
            @Size(max = MAX_INTERESTS, message = "A lista de interesses excede o tamanho máximo permitido")
            @ArraySchema(arraySchema = @Schema(description = "Códigos do catálogo, sem repetição; num rascunho do "
                    + "questionário pode ser vazia"),
                    schema = @Schema(implementation = InterestCategory.class))
            List<@NotNull(message = "O interesse não pode ser nulo") String> interests,

            @NotNull(message = "Informe se o questionário deve ser concluído")
            @Schema(example = "false", description = "true confirma o questionário (exige o mínimo de interesses); "
                    + "false grava um rascunho. Não desfaz uma conclusão anterior")
            Boolean completeOnboarding
    ) {
        public UpdateMyInterestsCommand toCommand(UUID actorUserId) {
            return new UpdateMyInterestsCommand(actorUserId, interests, completeOnboarding);
        }
    }

    @Schema(description = "Interesse do catálogo controlado")
    public record InterestOptionResponse(
            @Schema(example = "CAFE") String code,
            @Schema(example = "Cafés e cafeterias") String label
    ) {
        public static InterestOptionResponse fromView(InterestOptionView view) {
            return new InterestOptionResponse(view.code(), view.label());
        }
    }

    @Schema(description = "Pergunta do questionário inicial e as opções que ela agrupa")
    public record InterestQuestionResponse(
            @Schema(example = "FOOD_AND_DRINK") String code,
            @Schema(example = "Comer e beber") String title,
            @Schema(example = "Onde você gosta de comer e beber?") String question,
            List<InterestOptionResponse> options
    ) {
        public static InterestQuestionResponse fromView(InterestQuestionView view) {
            return new InterestQuestionResponse(view.code(), view.title(), view.question(),
                    view.options().stream().map(InterestOptionResponse::fromView).toList());
        }
    }

    @Schema(description = "Catálogo de interesses aceitos pela API, organizado nas perguntas do questionário")
    public record InterestCatalogResponse(
            @Schema(example = "3", description = "Mínimo de interesses para concluir o questionário")
            int minInterestsToComplete,
            List<InterestQuestionResponse> questions
    ) {
        public static InterestCatalogResponse fromView(InterestCatalogView view) {
            return new InterestCatalogResponse(view.minInterestsToComplete(),
                    view.questions().stream().map(InterestQuestionResponse::fromView).toList());
        }
    }

    @Schema(description = "Interesses do usuário autenticado e situação do questionário inicial")
    public record UserInterestsResponse(
            @Schema(example = "IN_PROGRESS") InterestOnboardingStatus onboardingStatus,

            @Schema(description = "Primeira confirmação do questionário; nulo enquanto não concluído")
            Instant onboardingCompletedAt,

            @Schema(description = "Interesses gravados, na ordem do catálogo")
            List<InterestOptionResponse> interests
    ) {
        public static UserInterestsResponse fromView(UserInterestProfileView view) {
            return new UserInterestsResponse(view.onboardingStatus(), view.onboardingCompletedAt(),
                    view.interests().stream().map(InterestOptionResponse::fromView).toList());
        }
    }
}
