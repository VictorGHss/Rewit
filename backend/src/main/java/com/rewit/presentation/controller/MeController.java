package com.rewit.presentation.controller;

import com.rewit.application.dto.user.UserDtos.ChangePasswordCommand;
import com.rewit.application.dto.user.UserDtos.UpdateProfileCommand;
import com.rewit.application.dto.user.UserDtos.UserProfileResult;
import com.rewit.application.service.UserService;
import com.rewit.presentation.dto.user.ChangePasswordRequest;
import com.rewit.presentation.dto.user.ChangePasswordResponse;
import com.rewit.presentation.dto.user.UpdateProfileRequest;
import com.rewit.presentation.dto.user.UserProfileResponse;
import com.rewit.application.dto.ReviewDto.ReviewPublicView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.service.ReviewService;
import com.rewit.presentation.dto.common.PagedResponse;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.ReviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    private final ReviewService reviewService;

    public MeController(UserService userService, ReviewService reviewService) {
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
        this.reviewService = Objects.requireNonNull(reviewService, "reviewService must not be null");
    }

    @GetMapping
    @Operation(summary = "Obter dados do usuário e perfil atualmente autenticado", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<UserProfileResponse> getMe(Authentication authentication) {
        UUID authenticatedUserId = extractAuthenticatedUserId(authentication);
        UserProfileResult result = userService.getMe(authenticatedUserId);
        return ResponseEntity.ok(toResponse(result));
    }

    @GetMapping("/reviews")
    @Operation(summary = "Listar publicações de avaliação do usuário autenticado", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PagedResponse<ReviewResponse>> getMyReviews(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            Authentication authentication
    ) {
        UUID authenticatedUserId = extractAuthenticatedUserId(authentication);
        PageResult<ReviewPublicView> pageResult = reviewService.findMyReviews(authenticatedUserId, page, size);

        List<ReviewResponse> content = pageResult.content().stream()
                .map(ReviewController::toResponse)
                .toList();

        return ResponseEntity.ok(new PagedResponse<>(
                content,
                pageResult.pageNumber(),
                pageResult.pageSize(),
                pageResult.totalElements(),
                pageResult.totalPages(),
                pageResult.isLast()
        ));
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

    @PostMapping("/password")
    @Operation(summary = "Alterar senha da conta local e revogar sessões existentes", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ChangePasswordResponse> changePassword(
            Authentication authentication,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        UUID authenticatedUserId = extractAuthenticatedUserId(authentication);
        ChangePasswordCommand cmd = new ChangePasswordCommand(
                authenticatedUserId,
                request.currentPassword(),
                request.newPassword()
        );

        userService.changePassword(cmd);
        return ResponseEntity.ok(new ChangePasswordResponse("Senha alterada com sucesso. Todas as sessões anteriores foram revogadas."));
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
