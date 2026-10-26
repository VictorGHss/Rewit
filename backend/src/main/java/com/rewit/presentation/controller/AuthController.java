package com.rewit.presentation.controller;

import com.rewit.application.dto.auth.AuthDtos.*;
import com.rewit.application.service.AuthService;
import com.rewit.application.service.SocialAuthService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.presentation.dto.auth.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Controller RESTful para autenticação local e social, registro, sessões e perfil autenticado.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Autenticação", description = "Endpoints de registro, login local e social, rotação de refresh token e identidade")
public class AuthController {

    // Parâmetros que a Apple envia ao redirect (response_mode=form_post) e que o app consome; o resto é descartado
    private static final List<String> APPLE_CALLBACK_PARAMETERS = List.of("code", "id_token", "state", "user", "error");
    private static final int MAX_APPLE_CALLBACK_PARAMETER_LENGTH = 16 * 1024;

    private final AuthService authService;
    private final SocialAuthService socialAuthService;
    private final String appleAndroidPackage;

    public AuthController(
            AuthService authService,
            SocialAuthService socialAuthService,
            // Mesmo padrão de SocialAuthProperties, que valida o formato na inicialização
            @Value("${rewit.social-auth.apple-android-package:app.rewit.mobile}") String appleAndroidPackage
    ) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.socialAuthService = Objects.requireNonNull(socialAuthService, "socialAuthService must not be null");
        this.appleAndroidPackage = Objects.requireNonNull(appleAndroidPackage, "appleAndroidPackage must not be null");
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

    @PostMapping("/social/nonce")
    @Operation(summary = "Emitir nonce de uso único para o login social (Google, Apple)")
    public ResponseEntity<SocialNonceResponse> socialNonce() {
        SocialNonceResult result = socialAuthService.issueNonce();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SocialNonceResponse(result.nonce(), result.expiresInSeconds()));
    }

    @PostMapping("/social/google")
    @Operation(summary = "Autenticar com o ID token do Google Sign-In e emitir tokens do Rewit")
    public ResponseEntity<AuthResponse> googleLogin(
            @Valid @RequestBody SocialLoginRequest request,
            HttpServletRequest httpRequest
    ) {
        return socialLogin(AuthProvider.GOOGLE, request, httpRequest);
    }

    @PostMapping("/social/apple")
    @Operation(summary = "Autenticar com o identity token do Sign in with Apple e emitir tokens do Rewit")
    public ResponseEntity<AuthResponse> appleLogin(
            @Valid @RequestBody SocialLoginRequest request,
            HttpServletRequest httpRequest
    ) {
        return socialLogin(AuthProvider.APPLE, request, httpRequest);
    }

    /**
     * Retorno do fluxo web do Sign in with Apple (Android): a Apple publica o resultado neste endpoint
     * ({@code response_mode=form_post}) e ele é repassado ao app pelo intent {@code signinwithapple://callback}.
     * Nada é validado nem gravado aqui; o app ainda envia o token a {@code POST /social/apple}, onde assinatura,
     * audiência e o nonce da tentativa são conferidos.
     */
    @PostMapping(value = "/apple/callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "Retorno do Sign in with Apple no Android (form_post) repassado ao app")
    public ResponseEntity<Void> appleCallback(@RequestParam MultiValueMap<String, String> form) {
        if (!socialAuthService.isProviderEnabled(AuthProvider.APPLE)) {
            throw new BusinessException("Login com este provedor não está disponível", HttpStatus.SERVICE_UNAVAILABLE,
                    "SOCIAL_PROVIDER_UNAVAILABLE");
        }

        StringBuilder query = new StringBuilder();
        for (String name : APPLE_CALLBACK_PARAMETERS) {
            List<String> values = form.get(name);
            if (values == null || values.isEmpty()) {
                continue;
            }
            if (values.size() > 1 || values.getFirst().length() > MAX_APPLE_CALLBACK_PARAMETER_LENGTH) {
                throw new BusinessException("Retorno do provedor inválido", HttpStatus.BAD_REQUEST, "INVALID_SOCIAL_CALLBACK");
            }
            query.append(query.isEmpty() ? "" : "&")
                    .append(name).append('=').append(URLEncoder.encode(values.getFirst(), StandardCharsets.UTF_8));
        }

        String location = "intent://callback?" + query
                + "#Intent;package=" + appleAndroidPackage + ";scheme=signinwithapple;end";
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, location)
                .header("Referrer-Policy", "no-referrer")
                .cacheControl(CacheControl.noStore())
                .build();
    }

    private ResponseEntity<AuthResponse> socialLogin(AuthProvider provider, SocialLoginRequest request,
                                                     HttpServletRequest httpRequest) {
        SocialLoginCommand cmd = new SocialLoginCommand(
                provider,
                request.idToken(),
                request.nonce(),
                httpRequest.getHeader("User-Agent"),
                extractClientIp(httpRequest)
        );

        AuthResult result = socialAuthService.authenticate(cmd);
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

    /**
     * IP do cliente gravado na sessão. Headers de proxy enviados pelo cliente nunca são lidos aqui: atrás de um proxy
     * confiável, o RemoteIpValve do Tomcat (server.tomcat.remoteip, perfil external-test) já substitui o remoteAddr,
     * e só quando o peer imediato está na lista de proxies internos.
     */
    private String extractClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
