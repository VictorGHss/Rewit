package com.rewit.application.service;

import com.rewit.application.dto.auth.AuthDtos.*;
import com.rewit.application.port.AuthSessionRepository;
import com.rewit.application.port.EmailReservation;
import com.rewit.application.port.PasswordHasher;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.RateLimiter;
import com.rewit.application.port.TokenService;
import com.rewit.application.port.UserRepository;
import com.rewit.application.ratelimit.RateLimitPermit;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.common.exception.BusinessException;
import com.rewit.common.exception.RefreshTokenReuseDetectedException;
import com.rewit.domain.enums.AccountStatus;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.AuthSession;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Serviço de aplicação para orquestração de autenticação local, registro, sessões e tokens.
 * Mantém fronteiras transacionais estritas e desacoplamento do domínio em relação a frameworks.
 *
 * <p>Proteção contra abuso: o cadastro é limitado globalmente, o login por identidade (só tentativas sem sucesso
 * permanecem contadas) e o refresh por usuário, sempre depois da detecção de reúso. O login executa uma
 * verificação de senha com custo equivalente mesmo quando não há conta local, para que o tempo de resposta não
 * revele se o e-mail está cadastrado.
 */
@Service
public class AuthService {

    // Perfil de conta criada por login social: handle aleatório (sem derivar do e-mail) e nome padrão sem a claim
    private static final String GENERATED_HANDLE_PREFIX = "usuario_";
    private static final char[] HANDLE_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int GENERATED_HANDLE_RANDOM_LENGTH = 10;
    private static final int GENERATED_HANDLE_ATTEMPTS = 5;
    private static final String DEFAULT_SOCIAL_DISPLAY_NAME = "Usuário Rewit";
    private static final int DISPLAY_NAME_MIN_LENGTH = 2;
    private static final int DISPLAY_NAME_MAX_LENGTH = 100;

    private final SecureRandom handleRandom = new SecureRandom();
    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final AuthSessionRepository authSessionRepository;
    private final PasswordHasher passwordHasher;
    private final TokenService tokenService;
    private final RateLimiter rateLimiter;
    private final EmailReservation emailReservation;

    public AuthService(
            UserRepository userRepository,
            ProfileRepository profileRepository,
            AuthSessionRepository authSessionRepository,
            PasswordHasher passwordHasher,
            TokenService tokenService,
            RateLimiter rateLimiter,
            EmailReservation emailReservation
    ) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository must not be null");
        this.authSessionRepository = Objects.requireNonNull(authSessionRepository, "authSessionRepository must not be null");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher must not be null");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter must not be null");
        this.emailReservation = Objects.requireNonNull(emailReservation, "emailReservation must not be null");
    }

    @Transactional
    public AuthResult register(RegisterCommand cmd) {
        Objects.requireNonNull(cmd, "RegisterCommand cannot be null");

        // Toda tentativa conta, inclusive as inválidas: sem IP, o limite é a soma de todos os clientes
        rateLimiter.acquireOrThrow(RateLimitedAction.REGISTRATION, RateLimitSubject.global());

        String normalizedEmail = User.normalizeEmail(cmd.email());
        String normalizedHandle = Profile.normalizeHandle(cmd.handle());

        validatePasswordPolicy(cmd.password());

        // Domínio dos e-mails reservados de contas excluídas (purge): nunca um endereço de cadastro
        if (User.isReservedEmail(normalizedEmail)) {
            throw new BusinessException("E-mail inválido ou ausente", "INVALID_EMAIL");
        }

        // Após o purge, o e-mail de uma conta excluída continua reservado como HMAC (EmailReservation). Sem segredo
        // configurado o purge não executa, então não há valor reservado a procurar
        if (userRepository.existsByEmail(normalizedEmail)
                || (emailReservation.isConfigured()
                    && userRepository.existsByEmail(emailReservation.reservedEmailFor(normalizedEmail)))) {
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

        // A tentativa ocupa a janela antes da verificação, inclusive para e-mail inexistente; só o sucesso a devolve
        RateLimitPermit attempt = rateLimiter.acquireOrThrow(RateLimitedAction.LOGIN, RateLimitSubject.ofIdentity(normalizedEmail));

        User user = userRepository.findByEmail(normalizedEmail).orElse(null);

        if (user == null || user.isDeleted() || !user.isActive()
                || user.getAuthProvider() != AuthProvider.LOCAL || user.getPasswordHash() == null) {
            passwordHasher.simulateVerification(cmd.password());
            throw new BusinessException("Credenciais inválidas", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }

        if (!passwordHasher.matches(cmd.password(), user.getPasswordHash())) {
            throw new BusinessException("Credenciais inválidas", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }

        rateLimiter.release(attempt);

        Profile profile = profileRepository.findByUserId(user.getId())
                .orElseThrow(() -> new BusinessException("Perfil não encontrado para o usuário", HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND"));

        return createSessionAndGenerateResult(user, profile, cmd.userAgent(), cmd.ipAddress());
    }

    /**
     * Reativação de uma conta {@code DEACTIVATED} pelo próprio usuário (C2), com as mesmas credenciais do login.
     *
     * <p>Mesmo contrato e mesmas proteções do login: limite {@code LOGIN} na mesma identidade (não é um segundo
     * orçamento de tentativas), exatamente uma verificação Argon2 em todo caminho e {@code 401 INVALID_CREDENTIALS}
     * para senha errada e para conta inexistente, federada, suspensa ou excluída. O titular de uma conta suspensa
     * não distingue esse estado de uma senha errada. Conta {@code ACTIVE} apenas autentica, como no login.
     *
     * <p>A reativação trava a linha da conta e relê o estado depois da senha verificada: uma suspensão concorrente
     * confirmada antes vence. A transição revoga as sessões anteriores antes de criar a nova, inclusive alguma criada
     * por um refresh concorrente com a desativação, que escapou da revogação daquela transação.
     */
    @Transactional
    public AuthResult reactivate(LoginCommand cmd) {
        Objects.requireNonNull(cmd, "LoginCommand cannot be null");

        if (cmd.email() == null || cmd.email().isBlank() || cmd.password() == null || cmd.password().isBlank()) {
            throw new BusinessException("Credenciais inválidas", "INVALID_CREDENTIALS");
        }

        String normalizedEmail = cmd.email().trim().toLowerCase();

        RateLimitPermit attempt = rateLimiter.acquireOrThrow(RateLimitedAction.LOGIN, RateLimitSubject.ofIdentity(normalizedEmail));

        User user = userRepository.findByEmail(normalizedEmail).orElse(null);

        if (user == null || !isSelfRecoverable(user.getStatus())
                || user.getAuthProvider() != AuthProvider.LOCAL || user.getPasswordHash() == null) {
            passwordHasher.simulateVerification(cmd.password());
            throw new BusinessException("Credenciais inválidas", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }

        if (!passwordHasher.matches(cmd.password(), user.getPasswordHash())) {
            throw new BusinessException("Credenciais inválidas", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }

        User locked = userRepository.findByIdForUpdate(user.getId())
                .filter(u -> isSelfRecoverable(u.getStatus()))
                .orElseThrow(() -> new BusinessException("Credenciais inválidas", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS"));

        if (locked.reactivate()) {
            userRepository.save(locked);
            authSessionRepository.revokeAllByUserId(locked.getId());
        }

        rateLimiter.release(attempt);

        Profile profile = profileRepository.findByUserId(locked.getId())
                .orElseThrow(() -> new BusinessException("Perfil não encontrado para o usuário", HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND"));

        return createSessionAndGenerateResult(locked, profile, cmd.userAgent(), cmd.ipAddress());
    }

    /**
     * Sessão para uma identidade externa já verificada ({@link SocialAuthService}): a conta é localizada somente
     * pelo par provedor + subject do ID token.
     *
     * <ul>
     *   <li>Conta vinculada: autentica se estiver {@code ACTIVE}; em qualquer outro estado (desativada, suspensa,
     *   excluída) responde {@code 401 ACCOUNT_DISABLED}, sem distinguir o estado.</li>
     *   <li>Sem conta vinculada: cria a conta federada (sem senha) e o perfil, desde que o provedor ateste o e-mail
     *   ({@code email_verified}). Um e-mail já usado por outra conta, local ou de outro provedor, inclusive o
     *   reservado de uma conta excluída, responde {@code 409 SOCIAL_ACCOUNT_EMAIL_CONFLICT}: nunca há vinculação
     *   automática por e-mail coincidente.</li>
     * </ul>
     *
     * <p>O perfil novo não expõe o e-mail: o handle é aleatório e o nome de exibição vem da claim {@code name} do
     * token, quando houver; os dois podem ser editados depois. A criação conta no limite global de cadastro.
     * Duas criações concorrentes da mesma identidade colidem nos índices únicos; quem chama repete em nova
     * transação, que então encontra a conta criada.
     */
    @Transactional
    public AuthResult authenticateExternalIdentity(ExternalIdentity identity, String userAgent, String ipAddress) {
        Objects.requireNonNull(identity, "ExternalIdentity cannot be null");
        if (identity.provider() == null || identity.provider() == AuthProvider.LOCAL
                || identity.subject() == null || identity.subject().isBlank()) {
            throw new IllegalArgumentException("Identidade externa sem provedor federado ou subject");
        }

        User linked = userRepository.findByAuthProviderAndProviderUserId(identity.provider(), identity.subject()).orElse(null);
        if (linked != null) {
            if (!linked.isOperational()) {
                throw new BusinessException("Conta desativada ou inexistente", HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED");
            }
            Profile profile = profileRepository.findByUserId(linked.getId())
                    .orElseThrow(() -> new BusinessException("Perfil não encontrado para o usuário", HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND"));
            return createSessionAndGenerateResult(linked, profile, userAgent, ipAddress);
        }

        if (identity.email() == null || identity.email().isBlank() || !identity.emailVerified()) {
            throw new BusinessException("O provedor não confirmou um e-mail para criar a conta", "SOCIAL_EMAIL_NOT_VERIFIED");
        }
        String normalizedEmail = User.normalizeEmail(identity.email());
        if (User.isReservedEmail(normalizedEmail)) {
            throw new BusinessException("E-mail inválido ou ausente", "INVALID_EMAIL");
        }
        if (userRepository.existsByEmail(normalizedEmail)
                || (emailReservation.isConfigured()
                    && userRepository.existsByEmail(emailReservation.reservedEmailFor(normalizedEmail)))) {
            throw new BusinessException(
                    "Já existe uma conta Rewit com este e-mail. Entre com o método usado no cadastro.",
                    HttpStatus.CONFLICT, "SOCIAL_ACCOUNT_EMAIL_CONFLICT");
        }

        rateLimiter.acquireOrThrow(RateLimitedAction.REGISTRATION, RateLimitSubject.global());

        User savedUser = userRepository.save(new User(null, normalizedEmail, null, identity.provider(), identity.subject()));
        Profile savedProfile = profileRepository.save(
                new Profile(null, savedUser.getId(), availableGeneratedHandle(), socialDisplayName(identity.name()), null, null));

        return createSessionAndGenerateResult(savedUser, savedProfile, userAgent, ipAddress);
    }

    private String availableGeneratedHandle() {
        for (int attempt = 0; attempt < GENERATED_HANDLE_ATTEMPTS; attempt++) {
            StringBuilder handle = new StringBuilder(GENERATED_HANDLE_PREFIX);
            for (int i = 0; i < GENERATED_HANDLE_RANDOM_LENGTH; i++) {
                handle.append(HANDLE_ALPHABET[handleRandom.nextInt(HANDLE_ALPHABET.length)]);
            }
            if (!profileRepository.existsByHandle(handle.toString())) {
                return handle.toString();
            }
        }
        throw new IllegalStateException("Não foi possível gerar um handle disponível");
    }

    /** Nome de exibição a partir da claim {@code name} do token, nos limites do cadastro (2 a 100 caracteres). */
    static String socialDisplayName(String claimedName) {
        if (claimedName == null) {
            return DEFAULT_SOCIAL_DISPLAY_NAME;
        }
        String name = claimedName.strip().replaceAll("\\s+", " ");
        if (name.codePointCount(0, name.length()) > DISPLAY_NAME_MAX_LENGTH) {
            name = name.substring(0, name.offsetByCodePoints(0, DISPLAY_NAME_MAX_LENGTH)).strip();
        }
        return name.codePointCount(0, name.length()) < DISPLAY_NAME_MIN_LENGTH ? DEFAULT_SOCIAL_DISPLAY_NAME : name;
    }

    /** Estados a partir dos quais o próprio usuário volta a operar: suspensão e exclusão não se desfazem por aqui. */
    private static boolean isSelfRecoverable(AccountStatus status) {
        return status == AccountStatus.ACTIVE || status == AccountStatus.DEACTIVATED;
    }

    // A revogação em massa do reúso precisa ser commitada mesmo com o erro devolvido; demais erros fazem rollback
    @Transactional(noRollbackFor = RefreshTokenReuseDetectedException.class)
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
                throw new RefreshTokenReuseDetectedException();
            }
            throw new BusinessException("Refresh token revogado ou já reutilizado. Todas as sessões foram invalidadas.", HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REVOKED");
        }

        if (currentSession.isExpired()) {
            throw new BusinessException("Refresh token expirado", HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_EXPIRED");
        }

        // Depois da detecção de reúso, que nunca é bloqueada pelo limite; o 429 faz rollback sem consumir o token
        rateLimiter.acquireOrThrow(RateLimitedAction.REFRESH, RateLimitSubject.ofUser(currentSession.getUserId()));

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

        String newAccessToken = tokenService.generateAccessToken(user.getId(), user.getRole());

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
        String accessToken = tokenService.generateAccessToken(user.getId(), user.getRole());
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
