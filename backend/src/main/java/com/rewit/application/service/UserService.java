package com.rewit.application.service;

import com.rewit.application.dto.user.UserDtos.UpdateProfileCommand;
import com.rewit.application.dto.user.UserDtos.UserProfileResult;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Serviço de aplicação para gerenciamento de identidade e perfil do usuário autenticado.
 * Mantém fronteiras transacionais estritas e garante que o usuário opere apenas sobre sua própria conta.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;

    public UserService(UserRepository userRepository, ProfileRepository profileRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository must not be null");
    }

    @Transactional(readOnly = true)
    public UserProfileResult getMe(UUID userId) {
        Objects.requireNonNull(userId, "userId cannot be null");

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("Conta de usuário inativa ou inexistente", HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED"));

        if (user.isDeleted() || !user.isActive()) {
            throw new BusinessException("Conta de usuário inativa ou inexistente", HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED");
        }

        Profile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("Perfil do usuário não encontrado", HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND"));

        return new UserProfileResult(user, profile);
    }

    @Transactional
    public UserProfileResult updateProfile(UpdateProfileCommand cmd) {
        Objects.requireNonNull(cmd, "UpdateProfileCommand cannot be null");
        Objects.requireNonNull(cmd.userId(), "userId cannot be null");

        User user = userRepository.findById(cmd.userId())
                .orElseThrow(() -> new BusinessException("Conta de usuário inativa ou inexistente", HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED"));

        if (user.isDeleted() || !user.isActive()) {
            throw new BusinessException("Conta de usuário inativa ou inexistente", HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED");
        }

        Profile profile = profileRepository.findByUserId(cmd.userId())
                .orElseThrow(() -> new BusinessException("Perfil do usuário não encontrado", HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND"));

        // Se o handle foi informado no payload, normaliza e verifica unicidade
        if (cmd.handle() != null && !cmd.handle().isBlank()) {
            String normalizedHandle = Profile.normalizeHandle(cmd.handle());
            if (!normalizedHandle.equalsIgnoreCase(profile.getHandle())) {
                if (profileRepository.existsByHandle(normalizedHandle)) {
                    throw new BusinessException("Nome de usuário (@handle) já está em uso", HttpStatus.CONFLICT, "HANDLE_ALREADY_EXISTS");
                }
                profile.updateHandle(normalizedHandle);
            }
        }

        // Atualização dos demais campos mutáveis permitidos
        profile.updateDetails(
                cmd.displayName(),
                cmd.bio(),
                cmd.isAnonymousDefault()
        );

        Profile savedProfile = profileRepository.save(profile);
        return new UserProfileResult(user, savedProfile);
    }
}
