package com.rewit.presentation.controller;

import com.rewit.application.dto.auth.AuthDtos.*;
import com.rewit.application.service.AuthService;
import com.rewit.presentation.dto.auth.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.UUID;

/**
 * Controller RESTful para autenticação local, registro, sessões e perfil autenticado.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Autenticação", description = "Endpoints de registro, login local, rotação de refresh token e identidade")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
    }

    @PostMapping("/register")
    @Operation(summary = "Registrar novo usuário com credencial local e perfil inicial")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest
    ) {
        RegisterCommand cmd = new RegisterCommand(
                request.email(),
                request.password(),
                request.handle(),
                request.displayName(),
                httpRequest.getHeader("User-Agent"),
                extractClientIp(httpRequest)
        );

        AuthResult result = authService.register(cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(toAuthResponse(result));
    }

    @PostMapping("/login")
    @Operation(summary = "Autenticar com credenciais locais e emitir tokens")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        LoginCommand cmd = new LoginCommand(
                request.email(),
                request.password(),
                httpRequest.getHeader("User-Agent"),
                extractClientIp(httpRequest)
        );

        AuthResult result = authService.login(cmd);
        return ResponseEntity.ok(toAuthResponse(result));
    }

    @PostMapping("/reactivate")
    @Operation(summary = "Reativar a própria conta desativada com as credenciais locais e emitir tokens")
    public ResponseEntity<AuthResponse> reactivate(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        LoginCommand cmd = new LoginCommand(
                request.email(),
                request.password(),
                httpRequest.getHeader("User-Agent"),
                extractClientIp(httpRequest)
        );

        AuthResult result = authService.reactivate(cmd);
        return ResponseEntity.ok(toAuthResponse(result));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotacionar refresh token e emitir novo access token")
    public ResponseEntity<AuthResponse> refresh(
            @Valid @RequestBody RefreshRequest request,
            HttpServletRequest httpRequest
    ) {
        RefreshCommand cmd = new RefreshCommand(
                request.refreshToken(),
                httpRequest.getHeader("User-Agent"),
                extractClientIp(httpRequest)
        );

        AuthResult result = authService.refresh(cmd);
        return ResponseEntity.ok(toAuthResponse(result));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revogar sessão associada ao refresh token", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> logout(
            @RequestBody(required = false) LogoutRequest request,
            Authentication authentication
    ) {
        UUID authenticatedUserId = authentication != null ? UUID.fromString(authentication.getName()) : null;
        String refreshToken = request != null ? request.refreshToken() : null;

        LogoutCommand cmd = new LogoutCommand(refreshToken, authenticatedUserId);
        authService.logout(cmd);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    @Operation(summary = "Obter dados do usuário e perfil atualmente autenticado", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<UserMeResponse> me(Authentication authentication) {
        UUID authenticatedUserId = UUID.fromString(authentication.getName());
        UserMeResult result = authService.getMe(authenticatedUserId);

        UserMeResponse response = new UserMeResponse(
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

        return ResponseEntity.ok(response);
    }

    private AuthResponse toAuthResponse(AuthResult result) {
        return new AuthResponse(
                result.accessToken(),
                result.refreshToken(),
                "Bearer",
                result.expiresInSeconds(),
                new AuthResponse.UserSummary(
                        result.user().getId(),
                        result.user().getEmail(),
                        result.profile().getHandle(),
                        result.profile().getDisplayName()
                )
        );
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
