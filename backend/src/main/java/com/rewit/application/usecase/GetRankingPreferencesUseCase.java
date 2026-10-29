package com.rewit.application.usecase;

import com.rewit.application.dto.ranking.RankingPreferencesDto;
import com.rewit.application.port.ProfileRepository;
import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Caso de uso para recuperação da preferência de participação do usuário em rankings (ADR-016).
 */
@Service
public class GetRankingPreferencesUseCase {

    private final ProfileRepository profileRepository;

    public GetRankingPreferencesUseCase(ProfileRepository profileRepository) {
        this.profileRepository = Objects.requireNonNull(profileRepository, "ProfileRepository must not be null");
    }

    @Transactional(readOnly = true)
    public RankingPreferencesDto execute(UUID userId) {
        if (userId == null) {
            throw new BusinessException("Identificador de usuário obrigatório", HttpStatus.BAD_REQUEST, "MISSING_USER_ID");
        }

        return profileRepository.findByUserId(userId)
                .map(profile -> new RankingPreferencesDto(profile.isShowInRankings()))
                .orElseThrow(() -> new BusinessException("Perfil do usuário não encontrado", HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND"));
    }
}

