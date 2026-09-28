package com.rewit.application.service;

import com.rewit.application.dto.auth.AuthDtos.*;
import com.rewit.application.port.AuthSessionRepository;
import com.rewit.application.port.PasswordHasher;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.TokenService;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.AuthSession;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Serviço de aplicação para orquestração de autenticação local, registro, sessões e tokens.
 * Mantém fronteiras transacionais estritas e desacoplamento do domínio em relação a frameworks.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final AuthSessionRepository authSessionRepository;
    private final PasswordHasher passwordHasher;
    private final TokenService tokenService;

    public AuthService(
            UserRepository userRepository,
            ProfileRepository profileRepository,
            AuthSessionRepository authSessionRepository,
            PasswordHasher passwordHasher,
            TokenService tokenService
    ) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository must not be null");
        this.authSessionRepository = Objects.requireNonNull(authSessionRepository, "authSessionRepository must not be null");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher must not be null");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService must not be null");
    }

    @Transactional
    public AuthResult register(RegisterCommand cmd) {
        Objects.requireNonNull(cmd, "RegisterCommand cannot be null");

        String normalizedEmail = User.normalizeEmail(cmd.email());
        String normalizedHandle = Profile.normalizeHandle(cmd.handle());

        validatePasswordPolicy(cmd.password());

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException("E-mail já cadastrado na plataforma", "EMAIL_ALREADY_EXISTS");
        }

        if (profileRepository.existsByHandle(normalizedHandle)) {
            throw new BusinessException("Nome de usuário (@handle) já está em uso", "HANDLE_ALREADY_EXISTS");
        }

        String passwordHash = passwordHasher.hash(cmd.password());

        // 1. Criar e persistir User
        User user = new User(null, normalizedEmail, passwordHash, AuthProvider.LOCAL, null);
        User savedUser = userRepository.save(user);

        // 2. Criar e persistir Profile vinculado
        Profile profile = new Profile(null, savedUser.getId(), normalizedHandle, cmd.displayName(), null, null);
        Profile savedProfile = profileRepository.save(profile);

        // 3. Emitir tokens e criar sessão de autenticação
        return createSessionAndGenerateResult(savedUser, savedProfile, cmd.userAgent(), cmd.ipAddress());
    }

    @Transactional
    public AuthResult login(LoginCommand cmd) {
        Objects.requireNonNull(cmd, "LoginCommand cannot be null");

        if (cmd.email() == null || cmd.email().isBlank() || cmd.password() == null || cmd.password().isBlank()) {
            throw new BusinessException("Credenciais inválidas", "INVALID_CREDENTIALS");
        }

        String normalizedEmail = cmd.email().trim().toLowerCase();

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BusinessException("Credenciais inválidas", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS"));

        if (user.isDeleted() || !user.isActive()) {
            throw new BusinessException("Credenciais inválidas", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }

        if (user.getAuthProvider() != AuthProvider.LOCAL || user.getPasswordHash() == null) {
            throw new BusinessException("Credenciais inválidas", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }

        if (!passwordHasher.matches(cmd.password(), user.getPasswordHash())) {
            throw new BusinessException("Credenciais inválidas", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }

        Profile profile = profileRepository.findByUserId(user.getId())
                .orElseThrow(() -> new BusinessException("Perfil não encontrado para o usuário", HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND"));

        return createSessionAndGenerateResult(user, profile, cmd.userAgent(), cmd.ipAddress());
    }

    @Transactional
    public AuthResult refresh(RefreshCommand cmd) {
        Objects.requireNonNull(cmd, "RefreshCommand cannot be null");
        if (cmd.refreshToken() == null || cmd.refreshToken().isBlank()) {
            throw new BusinessException("Refresh token inválido ou ausente", HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");
        }

        String tokenHash = tokenService.hashRefreshToken(cmd.refreshToken());

        AuthSession currentSession = authSessionRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new BusinessException("Refresh token inválido", HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN"));

        // Detecção de reúso de refresh token revogado (Token Reuse Detection)
        if (currentSession.isRevoked()) {
            boolean isRecentConcurrentRotation = currentSession.getReplacedBySessionId() != null
                    && currentSession.getRevokedAt() != null
                    && currentSession.getRevokedAt().isAfter(Instant.now().minusSeconds(10));

            if (!isRecentConcurrentRotation) {
                authSessionRepository.revokeAllByUserId(currentSession.getUserId());
            }
            throw new BusinessException("Refresh token revogado ou já reutilizado. Todas as sessões foram invalidadas.", HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REVOKED");
        }

        if (currentSession.isExpired()) {
            throw new BusinessException("Refresh token expirado", HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_EXPIRED");
        }

        User user = userRepository.findById(currentSession.getUserId())
                .orElseThrow(() -> new BusinessException("Conta desativada ou inexistente", HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED"));

        if (user.isDeleted() || !user.isActive()) {
            throw new BusinessException("Conta desativada ou inexistente", HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED");
        }

        Profile profile = profileRepository.findByUserId(user.getId())
                .orElseThrow(() -> new BusinessException("Perfil não encontrado", HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND"));

        // Rotação do refresh token
        String newRawRefreshToken = tokenService.generateRefreshToken();
        String newTokenHash = tokenService.hashRefreshToken(newRawRefreshToken);
        Instant newExpiry = Instant.now().plusSeconds(tokenService.getRefreshTokenTtlSeconds());

        AuthSession newSession = new AuthSession(null, user.getId(), newTokenHash, newExpiry, cmd.userAgent(), cmd.ipAddress());
        AuthSession savedNewSession = authSessionRepository.save(newSession);

        currentSession.rotate(savedNewSession.getId());
        authSessionRepository.save(currentSession);

        String newAccessToken = tokenService.generateAccessToken(user.getId());

        return new AuthResult(user, profile, newAccessToken, newRawRefreshToken, tokenService.getAccessTokenTtlSeconds());
    }

    @Transactional
    public void logout(LogoutCommand cmd) {
        Objects.requireNonNull(cmd, "LogoutCommand cannot be null");

        if (cmd.refreshToken() != null && !cmd.refreshToken().isBlank()) {
            String tokenHash = tokenService.hashRefreshToken(cmd.refreshToken());
            authSessionRepository.findByTokenHash(tokenHash).ifPresent(session -> {
                session.revoke();
                authSessionRepository.save(session);
            });
        } else if (cmd.userId() != null) {
            authSessionRepository.revokeAllByUserId(cmd.userId());
        }
    }

    @Transactional(readOnly = true)
    public UserMeResult getMe(UUID userId) {
        Objects.requireNonNull(userId, "userId cannot be null");

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("Conta de usuário inativa ou inexistente", HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED"));

        if (user.isDeleted() || !user.isActive()) {
            throw new BusinessException("Conta de usuário inativa ou inexistente", HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED");
        }

        Profile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("Perfil do usuário não encontrado", HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND"));

        return new UserMeResult(user, profile);
    }

    private AuthResult createSessionAndGenerateResult(User user, Profile profile, String userAgent, String ipAddress) {
        String accessToken = tokenService.generateAccessToken(user.getId());
        String rawRefreshToken = tokenService.generateRefreshToken();
        String tokenHash = tokenService.hashRefreshToken(rawRefreshToken);
        Instant expiresAt = Instant.now().plusSeconds(tokenService.getRefreshTokenTtlSeconds());

        AuthSession session = new AuthSession(null, user.getId(), tokenHash, expiresAt, userAgent, ipAddress);
        authSessionRepository.save(session);

        return new AuthResult(user, profile, accessToken, rawRefreshToken, tokenService.getAccessTokenTtlSeconds());
    }

    private void validatePasswordPolicy(String password) {
        if (password == null || password.length() < 8) {
            throw new BusinessException("A senha deve conter no mínimo 8 caracteres", "INVALID_PASSWORD_POLICY");
        }
        if (password.length() > 128) {
            throw new BusinessException("A senha não deve exceder 128 caracteres", "INVALID_PASSWORD_POLICY");
        }
    }
}
