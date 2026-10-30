package com.rewit.application.usecase;

import com.rewit.application.dto.ranking.RankingPreferencesDto;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.service.AccountStatusPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Caso de uso para atualização da preferência de opt-in de participação nos rankings (ADR-016).
 * Exige conta ativa e operacional via {@link AccountStatusPolicy}.
 */
@Service
public class UpdateRankingPreferencesUseCase {

    private final ProfileRepository profileRepository;
    private final AccountStatusPolicy accountStatusPolicy;

    public UpdateRankingPreferencesUseCase(ProfileRepository profileRepository,
                                          AccountStatusPolicy accountStatusPolicy) {
        this.profileRepository = Objects.requireNonNull(profileRepository, "ProfileRepository must not be null");
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
    }

    @Transactional
    public RankingPreferencesDto execute(UUID userId, boolean showInRankings) {
        if (userId == null) {
            throw new BusinessException("Identificador de usuário obrigatório", HttpStatus.BAD_REQUEST, "MISSING_USER_ID");
        }

        accountStatusPolicy.requireOperational(userId);

        Profile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("Perfil do usuário não encontrado", HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND"));

        profile.updateRankingPreferences(showInRankings);
        Profile saved = profileRepository.save(profile);

        return new RankingPreferencesDto(saved.isShowInRankings());
    }
}
