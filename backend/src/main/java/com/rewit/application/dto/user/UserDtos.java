package com.rewit.application.dto.user;

import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;

import java.util.UUID;

/**
 * DTOs e Commands para operações de consulta e atualização de usuário e perfil na camada de aplicação.
 */
public class UserDtos {

    public record UpdateProfileCommand(
            UUID userId,
            String handle,
            String displayName,
            String bio,
            Boolean isAnonymousDefault
    ) {}

    public record UserProfileResult(
            User user,
            Profile profile
    ) {}

    public record ChangePasswordCommand(
            UUID userId,
            String currentPassword,
            String newPassword
    ) {}
}
