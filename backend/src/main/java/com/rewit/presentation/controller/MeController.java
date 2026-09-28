package com.rewit.presentation.controller;

import com.rewit.application.dto.user.UserDtos.UpdateProfileCommand;
import com.rewit.application.dto.user.UserDtos.UserProfileResult;
import com.rewit.application.service.UserService;
import com.rewit.presentation.dto.user.UpdateProfileRequest;
import com.rewit.presentation.dto.user.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.UUID;

/**
 * Controller RESTful para consulta e atualização de perfil do usuário autenticado (/api/v1/me).
 * A identidade é inferida exclusivamente a partir do token JWT do contexto de segurança, prevenindo IDOR.
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Usuário e Perfil", description = "Endpoints de identidade e gerenciamento de perfil do usuário autenticado")
public class MeController {

    private final UserService userService;

    public MeController(UserService userService) {
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
    }

    @GetMapping
    @Operation(summary = "Obter dados do usuário e perfil atualmente autenticado", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<UserProfileResponse> getMe(Authentication authentication) {
        UUID authenticatedUserId = extractAuthenticatedUserId(authentication);
        UserProfileResult result = userService.getMe(authenticatedUserId);
        return ResponseEntity.ok(toResponse(result));
    }

    @PatchMapping("/profile")
    @Operation(summary = "Atualizar dados do perfil do usuário autenticado", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<UserProfileResponse> updateProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        UUID authenticatedUserId = extractAuthenticatedUserId(authentication);
        UpdateProfileCommand cmd = new UpdateProfileCommand(
                authenticatedUserId,
                request.handle(),
                request.displayName(),
                request.bio(),
                request.isAnonymousDefault()
        );

        UserProfileResult result = userService.updateProfile(cmd);
        return ResponseEntity.ok(toResponse(result));
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        Objects.requireNonNull(authentication, "Authentication must not be null");
        return UUID.fromString(authentication.getName());
    }

    private UserProfileResponse toResponse(UserProfileResult result) {
        return new UserProfileResponse(
                result.user().getId(),
                result.user().getEmail(),
                result.profile().getHandle(),
                result.profile().getDisplayName(),
                result.profile().getBio(),
                result.profile().getAvatarUrl(),
                result.user().isVerified(),
                result.profile().isAnonymousDefault(),
                result.profile().getReputationScore(),
                result.user().getCreatedAt()
        );
    }
}
