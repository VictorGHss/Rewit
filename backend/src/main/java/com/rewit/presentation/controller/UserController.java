package com.rewit.presentation.controller;

import com.rewit.application.dto.user.UserDtos.PublicUserProfileView;
import com.rewit.application.service.UserService;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.user.PublicUserProfileResponse;
import com.rewit.presentation.dto.user.PublicUserProfileResponse.UserStatsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Controller RESTful para consulta pública de perfis de usuários (/api/v1/users/{id}) - Step 18.0.
 * A identidade do solicitante é inferida do token JWT para resolução contextual de conexões sociais (isFollowing).
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Usuários", description = "Endpoints de consulta pública de perfis e estatísticas de usuários")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar perfil público e estatísticas factuais do usuário", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PublicUserProfileResponse> getPublicProfile(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        UUID requesterUserId = extractAuthenticatedUserId(authentication);
        PublicUserProfileView view = userService.getPublicProfile(id, requesterUserId);
        return ResponseEntity.ok(toResponse(view));
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return UUID.fromString(authentication.getName());
    }

    public static PublicUserProfileResponse toResponse(PublicUserProfileView view) {
        return new PublicUserProfileResponse(
                view.id(),
                view.handle(),
                view.displayName(),
                view.bio(),
                view.avatarUrl(),
                new UserStatsResponse(
                        view.stats().totalReviews(),
                        view.stats().verifiedReviewsCount(),
                        view.stats().followersCount(),
                        view.stats().followingCount(),
                        view.stats().helpfulVotesReceived()
                ),
                view.isFollowing()
        );
    }
}
