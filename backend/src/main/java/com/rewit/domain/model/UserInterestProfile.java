package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.InterestCategory;
import com.rewit.domain.enums.InterestOnboardingStatus;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Interesses declarados por um usuário e a situação do questionário inicial que os coleta.
 *
 * <p>Existir o perfil significa que o usuário já gravou respostas (questionário em andamento ou concluído); a
 * ausência de perfil é {@link InterestOnboardingStatus#NOT_STARTED}. A conclusão é definitiva: depois dela o
 * questionário não volta a ser apresentado e toda gravação precisa manter ao menos
 * {@link #MIN_INTERESTS_TO_COMPLETE} interesses. Enquanto em andamento, rascunhos com qualquer quantidade são aceitos.
 *
 * <p>Contas anteriores ao questionário (V40) foram marcadas como concluídas sem interesses: o mínimo vale para a
 * próxima gravação, não para o estado herdado.
 */
public final class UserInterestProfile {

    /** Mínimo de interesses para concluir o questionário e para cada gravação depois da conclusão. */
    public static final int MIN_INTERESTS_TO_COMPLETE = 3;

    private final UUID userId;
    private final Set<InterestCategory> interests;
    private final Instant onboardingCompletedAt;
    private final Instant createdAt;
    private final Instant updatedAt;

    public UserInterestProfile(UUID userId, Collection<InterestCategory> interests, Instant onboardingCompletedAt,
                               Instant createdAt, Instant updatedAt) {
        if (userId == null) {
            throw new BusinessException("O usuário é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_USER_ID");
        }
        this.userId = userId;
        this.interests = Collections.unmodifiableSet(distinct(interests));
        this.onboardingCompletedAt = onboardingCompletedAt;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
    }

    /** Perfil novo, sem interesses e com o questionário em andamento. */
    public static UserInterestProfile start(UUID userId, Instant now) {
        Instant at = now != null ? now : Instant.now();
        return new UserInterestProfile(userId, List.of(), null, at, at);
    }

    /**
     * Substitui integralmente os interesses. Com {@code completeOnboarding} ou com o questionário já concluído, exige
     * ao menos {@link #MIN_INTERESTS_TO_COMPLETE} ({@code 422 INTERESTS_BELOW_MINIMUM}). A data da conclusão é a da
     * primeira confirmação e não muda em reenvios; {@code completeOnboarding = false} não desfaz uma conclusão.
     */
    public UserInterestProfile replaceInterests(Collection<InterestCategory> newInterests, boolean completeOnboarding,
                                                Instant now) {
        Instant at = now != null ? now : Instant.now();
        Set<InterestCategory> selected = distinct(newInterests);
        boolean completed = onboardingCompletedAt != null || completeOnboarding;
        if (completed && selected.size() < MIN_INTERESTS_TO_COMPLETE) {
            throw new BusinessException("Selecione pelo menos " + MIN_INTERESTS_TO_COMPLETE + " interesses",
                    HttpStatus.UNPROCESSABLE_CONTENT, "INTERESTS_BELOW_MINIMUM");
        }
        Instant completedAt = onboardingCompletedAt != null ? onboardingCompletedAt : (completeOnboarding ? at : null);
        return new UserInterestProfile(userId, selected, completedAt, createdAt, at);
    }

    /**
     * Converte os códigos recebidos na API: lista ausente, elemento nulo, código fora do catálogo ou código repetido
     * são recusados com {@code 400}, antes de qualquer leitura ou escrita.
     */
    public static List<InterestCategory> fromCodes(List<String> codes) {
        if (codes == null) {
            throw new BusinessException("A lista de interesses é obrigatória", HttpStatus.BAD_REQUEST,
                    "MISSING_INTERESTS");
        }
        List<InterestCategory> categories = new ArrayList<>(codes.size());
        for (String code : codes) {
            categories.add(InterestCategory.fromCode(code));
        }
        distinct(categories);
        return List.copyOf(categories);
    }

    private static Set<InterestCategory> distinct(Collection<InterestCategory> categories) {
        Set<InterestCategory> set = EnumSet.noneOf(InterestCategory.class);
        if (categories == null) {
            return set;
        }
        for (InterestCategory category : categories) {
            if (category == null) {
                throw new BusinessException("Interesse desconhecido: use um código do catálogo de interesses",
                        HttpStatus.BAD_REQUEST, "INVALID_INTEREST_CODE");
            }
            if (!set.add(category)) {
                throw new BusinessException("O interesse não pode ser repetido na requisição", HttpStatus.BAD_REQUEST,
                        "DUPLICATE_INTEREST");
            }
        }
        return set;
    }

    public UUID getUserId() {
        return userId;
    }

    /** Interesses na ordem do catálogo; vazio num rascunho ou numa conta anterior ao questionário. */
    public Set<InterestCategory> getInterests() {
        return interests;
    }

    /** Primeira confirmação do questionário; nulo enquanto em andamento. */
    public Instant getOnboardingCompletedAt() {
        return onboardingCompletedAt;
    }

    public boolean isOnboardingCompleted() {
        return onboardingCompletedAt != null;
    }

    public InterestOnboardingStatus getOnboardingStatus() {
        return isOnboardingCompleted() ? InterestOnboardingStatus.COMPLETED : InterestOnboardingStatus.IN_PROGRESS;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
