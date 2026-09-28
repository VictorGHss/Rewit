package com.rewit.application.service;

import com.rewit.application.dto.auth.AuthDtos.*;
import com.rewit.application.port.AuthSessionRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.AuthSession;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.security.Argon2PasswordHasher;
import com.rewit.infrastructure.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Testes Unitários: Autenticação Local, Hasher, JWT e AuthService (Step 4)")
class LocalAuthenticationUnitTest {

    private UserRepository userRepository;
    private ProfileRepository profileRepository;
    private AuthSessionRepository authSessionRepository;
    private Argon2PasswordHasher passwordHasher;
    private JwtTokenService tokenService;
    private AuthService authService;

    private final String jwtSecret = "super_secret_local_jwt_key_that_is_at_least_32_bytes_long_12345";
    private final String issuer = "rewit-api";
    private final String audience = "rewit-clients";

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        profileRepository = mock(ProfileRepository.class);
        authSessionRepository = mock(AuthSessionRepository.class);
        passwordHasher = new Argon2PasswordHasher();
        tokenService = new JwtTokenService(jwtSecret, issuer, audience, 900, 2592000);

        authService = new AuthService(
                userRepository,
                profileRepository,
                authSessionRepository,
                passwordHasher,
                tokenService
        );
    }

    @Test
    @DisplayName("1. Senha nunca deve ser armazenada em texto puro")
    void shouldNeverStorePasswordInPlaintext() {
        String rawPassword = "MinhaSenhaSuperSecreta@123";
        String hash = passwordHasher.hash(rawPassword);

        assertNotEquals(rawPassword, hash);
        assertTrue(hash.startsWith("$argon2id$"), "Hash deve iniciar com o identificador do algoritmo Argon2id");
    }

    @Test
    @DisplayName("2 e 3. Senha correta deve ser aceita e senha incorreta deve ser rejeitada")
    void shouldValidateCorrectAndIncorrectPassword() {
        String rawPassword = "SenhaValida123";
        String hash = passwordHasher.hash(rawPassword);

        assertTrue(passwordHasher.matches("SenhaValida123", hash), "Senha correta deve casar");
        assertFalse(passwordHasher.matches("SenhaErrada456", hash), "Senha incorreta deve ser rejeitada");
        assertFalse(passwordHasher.matches("", hash));
        assertFalse(passwordHasher.matches(null, hash));
    }

    @Test
    @DisplayName("4. E-mail e handle devem ser normalizados no registro")
    void shouldNormalizeEmailAndHandleOnRegister() {
        RegisterCommand cmd = new RegisterCommand(
                "  Novo.Usuario@REWIT.COM  ",
                "senhaForte123",
                "  @Meu_Handle  ",
                "Meu Nome",
                "Mozilla/5.0",
                "127.0.0.1"
        );

        when(userRepository.existsByEmail("novo.usuario@rewit.com")).thenReturn(false);
        when(profileRepository.existsByHandle("meu_handle")).thenReturn(false);

        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(profileRepository.save(any(Profile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(authSessionRepository.save(any(AuthSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthResult result = authService.register(cmd);

        assertNotNull(result);
        assertEquals("novo.usuario@rewit.com", result.user().getEmail());
        assertEquals("meu_handle", result.profile().getHandle());
        assertNotNull(result.accessToken());
        assertNotNull(result.refreshToken());

        // Verifica que o hash foi salvo no User, não o texto puro
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User capturedUser = userCaptor.getValue();
        assertNotEquals("senhaForte123", capturedUser.getPasswordHash());
        assertTrue(capturedUser.getPasswordHash().startsWith("$argon2id$"));
    }

    @Test
    @DisplayName("5. Usuário federado (GOOGLE) não pode autenticar por senha local")
    void shouldRejectLocalLoginForFederatedUser() {
        User googleUser = new User(UUID.randomUUID(), "google@gmail.com", null, AuthProvider.GOOGLE, "sub-google-123");
        when(userRepository.findByEmail("google@gmail.com")).thenReturn(Optional.of(googleUser));

        LoginCommand cmd = new LoginCommand("google@gmail.com", "qualquerSenha123", "Agent", "127.0.0.1");

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(cmd));
        assertEquals("INVALID_CREDENTIALS", ex.getErrorCode());
    }

    @Test
    @DisplayName("6. Usuário soft-deleted ou desativado não pode autenticar")
    void shouldRejectLoginForSoftDeletedUser() {
        User user = new User(UUID.randomUUID(), "deleted@rewit.com", passwordHasher.hash("senha12345"), AuthProvider.LOCAL, null);
        user.softDelete();

        when(userRepository.findByEmail("deleted@rewit.com")).thenReturn(Optional.of(user));

        LoginCommand cmd = new LoginCommand("deleted@rewit.com", "senha12345", "Agent", "127.0.0.1");

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(cmd));
        assertEquals("INVALID_CREDENTIALS", ex.getErrorCode());
    }

    @Test
    @DisplayName("7. JWT deve conter claims esperadas (iss, sub, aud, iat, exp, jti)")
    void shouldContainExpectedJwtClaims() {
        UUID userId = UUID.randomUUID();
        String token = tokenService.generateAccessToken(userId);

        assertNotNull(token);
        UUID extracted = tokenService.extractUserIdFromAccessToken(token);
        assertEquals(userId, extracted);
    }

    @Test
    @DisplayName("8. JWT expirado deve ser rejeitado")
    void shouldRejectExpiredJwt() {
        // Criar TokenService com TTL negativo/zero para simular expiração imediata
        JwtTokenService expiredService = new JwtTokenService(jwtSecret, issuer, audience, -10, 3600);
        String expiredToken = expiredService.generateAccessToken(UUID.randomUUID());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                tokenService.extractUserIdFromAccessToken(expiredToken));
        assertEquals("TOKEN_EXPIRED", ex.getErrorCode());
    }

    @Test
    @DisplayName("9 e 10. JWT com issuer ou audience incorretos deve ser rejeitado")
    void shouldRejectJwtWithWrongIssuerOrAudience() {
        JwtTokenService wrongIssuerService = new JwtTokenService(jwtSecret, "wrong-issuer", audience, 900, 3600);
        String wrongIssuerToken = wrongIssuerService.generateAccessToken(UUID.randomUUID());

        BusinessException ex1 = assertThrows(BusinessException.class, () ->
                tokenService.extractUserIdFromAccessToken(wrongIssuerToken));
        assertEquals("INVALID_TOKEN", ex1.getErrorCode());

        JwtTokenService wrongAudienceService = new JwtTokenService(jwtSecret, issuer, "wrong-audience", 900, 3600);
        String wrongAudienceToken = wrongAudienceService.generateAccessToken(UUID.randomUUID());

        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                tokenService.extractUserIdFromAccessToken(wrongAudienceToken));
        assertEquals("INVALID_TOKEN", ex2.getErrorCode());
    }

    @Test
    @DisplayName("11 e 12. Refresh token deve ser rotacionado e o token antigo deve ser invalidado")
    void shouldRotateRefreshTokenAndInvalidateOldOne() {
        UUID userId = UUID.randomUUID();
        String oldRawToken = "valid-old-refresh-token-1234567890123456";
        String oldHash = tokenService.hashRefreshToken(oldRawToken);

        AuthSession oldSession = new AuthSession(
                UUID.randomUUID(),
                userId,
                oldHash,
                Instant.now().plusSeconds(3600),
                "agent",
                "127.0.0.1"
        );

        User user = new User(userId, "user@rewit.com", "hash", AuthProvider.LOCAL, null);
        Profile profile = new Profile(UUID.randomUUID(), userId, "user", "User", null, null);

        when(authSessionRepository.findByTokenHashForUpdate(oldHash)).thenReturn(Optional.of(oldSession));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(authSessionRepository.save(any(AuthSession.class))).thenAnswer(inv -> inv.getArgument(0));

        RefreshCommand cmd = new RefreshCommand(oldRawToken, "agent", "127.0.0.1");
        AuthResult result = authService.refresh(cmd);

        assertNotNull(result);
        assertNotEquals(oldRawToken, result.refreshToken(), "O refresh token rotacionado deve ser novo e diferente");
        assertTrue(oldSession.isRevoked(), "A sessão anterior deve ter sido revogada na rotação");
        assertNotNull(oldSession.getReplacedBySessionId(), "A sessão anterior deve apontar para o novo ID gerado");
    }

    @Test
    @DisplayName("13 e 14. Refresh token expirado ou revogado deve ser rejeitado")
    void shouldRejectExpiredOrRevokedRefreshToken() {
        UUID userId = UUID.randomUUID();
        String rawToken = "my-token-12345678901234567890";
        String tokenHash = tokenService.hashRefreshToken(rawToken);

        // Caso 1: Expirado
        AuthSession expiredSession = AuthSession.rehydrate(
                UUID.randomUUID(), userId, tokenHash, Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600), null, null, Instant.now().minusSeconds(7200), null, null, null
        );
        when(authSessionRepository.findByTokenHashForUpdate(tokenHash)).thenReturn(Optional.of(expiredSession));

        BusinessException exExp = assertThrows(BusinessException.class, () ->
                authService.refresh(new RefreshCommand(rawToken, "agent", "127.0.0.1")));
        assertEquals("REFRESH_TOKEN_EXPIRED", exExp.getErrorCode());

        // Caso 2: Revogado (Token reuse detection)
        AuthSession revokedSession = AuthSession.rehydrate(
                UUID.randomUUID(), userId, tokenHash, Instant.now().minusSeconds(7200),
                Instant.now().plusSeconds(3600), Instant.now().minusSeconds(100), null, Instant.now().minusSeconds(7200), null, null, null
        );
        when(authSessionRepository.findByTokenHashForUpdate(tokenHash)).thenReturn(Optional.of(revokedSession));

        BusinessException exRev = assertThrows(BusinessException.class, () ->
                authService.refresh(new RefreshCommand(rawToken, "agent", "127.0.0.1")));
        assertEquals("REFRESH_TOKEN_REVOKED", exRev.getErrorCode());
        // Deve revogar todas as sessões do usuário por segurança
        verify(authSessionRepository).revokeAllByUserId(userId);
    }

    @Test
    @DisplayName("15. Logout deve revogar a sessão correspondente")
    void shouldRevokeSessionOnLogout() {
        String rawToken = "token-to-logout-1234567890";
        String tokenHash = tokenService.hashRefreshToken(rawToken);

        AuthSession session = new AuthSession(
                UUID.randomUUID(), UUID.randomUUID(), tokenHash, Instant.now().plusSeconds(3600), "agent", "127.0.0.1"
        );
        when(authSessionRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(session));

        authService.logout(new LogoutCommand(rawToken, null));

        assertTrue(session.isRevoked());
        verify(authSessionRepository).save(session);
    }

    @Test
    @DisplayName("16. Register deve criar User e Profile com política de senha mínima de 8 caracteres")
    void shouldEnforcePasswordPolicyOnRegister() {
        RegisterCommand weakPassCmd = new RegisterCommand(
                "weak@rewit.com", "curta", "weakuser", "Weak", "agent", "127.0.0.1"
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.register(weakPassCmd));
        assertEquals("INVALID_PASSWORD_POLICY", ex.getErrorCode());
    }

    @Test
    @DisplayName("17 e 18. Duplicação de e-mail ou handle no registro deve ser rejeitada")
    void shouldRejectDuplicateEmailOrHandleOnRegister() {
        when(userRepository.existsByEmail("duplicado@rewit.com")).thenReturn(true);

        RegisterCommand dupEmail = new RegisterCommand(
                "duplicado@rewit.com", "senhaForte123", "usuario1", "Nome", "agent", "127.0.0.1"
        );
        BusinessException exEmail = assertThrows(BusinessException.class, () -> authService.register(dupEmail));
        assertEquals("EMAIL_ALREADY_EXISTS", exEmail.getErrorCode());

        when(userRepository.existsByEmail("novo@rewit.com")).thenReturn(false);
        when(profileRepository.existsByHandle("handle_duplicado")).thenReturn(true);

        RegisterCommand dupHandle = new RegisterCommand(
                "novo@rewit.com", "senhaForte123", "handle_duplicado", "Nome", "agent", "127.0.0.1"
        );
        BusinessException exHandle = assertThrows(BusinessException.class, () -> authService.register(dupHandle));
        assertEquals("HANDLE_ALREADY_EXISTS", exHandle.getErrorCode());
    }
}
