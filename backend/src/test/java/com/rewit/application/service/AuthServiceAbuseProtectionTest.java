package com.rewit.application.service;

import com.rewit.application.dto.auth.AuthDtos.AuthResult;
import com.rewit.application.dto.auth.AuthDtos.LoginCommand;
import com.rewit.application.dto.auth.AuthDtos.RefreshCommand;
import com.rewit.application.dto.auth.AuthDtos.RegisterCommand;
import com.rewit.application.port.AuthSessionRepository;
import com.rewit.application.port.PasswordHasher;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.common.exception.RefreshTokenReuseDetectedException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.AuthSession;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.ratelimit.RateLimitProperties;
import com.rewit.infrastructure.ratelimit.RateLimitTestSupport;
import com.rewit.infrastructure.ratelimit.RateLimitTestSupport.MutableClock;
import com.rewit.infrastructure.security.Argon2PasswordHasher;
import com.rewit.infrastructure.security.JwtTokenService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AuthService: rate limiting de login, cadastro e refresh e tempo uniforme no login")
class AuthServiceAbuseProtectionTest {

    private static final String EMAIL = "alvo@rewit.com";
    private static final String PASSWORD = "Senha-Correta-123";
    private static final String STORED_HASH = "$argon2id$hash-armazenado";

    private UserRepository userRepository;
    private ProfileRepository profileRepository;
    private AuthSessionRepository authSessionRepository;
    private PasswordHasher passwordHasher;
    private JwtTokenService tokenService;
    private MutableClock clock;
    private RateLimitProperties rateLimitProperties;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        profileRepository = mock(ProfileRepository.class);
        authSessionRepository = mock(AuthSessionRepository.class);
        passwordHasher = mock(PasswordHasher.class);
        tokenService = new JwtTokenService("super_secret_local_jwt_key_that_is_at_least_32_bytes_long_12345",
                "rewit-api", "rewit-clients", 900, 2592000);
        clock = new MutableClock(Instant.parse("2026-10-06T12:00:00Z"));
        rateLimitProperties = RateLimitTestSupport.properties();
        rebuildService();

        when(authSessionRepository.save(any(AuthSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ------------------------------------------------------------------ login

    @Test
    @DisplayName("Login com e-mail inexistente executa verificação de custo equivalente e responde INVALID_CREDENTIALS")
    void loginWithUnknownEmailSimulatesVerification() {
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(login(EMAIL, PASSWORD)));

        assertInvalidCredentials(ex);
        verify(passwordHasher).simulateVerification(PASSWORD);
        verify(passwordHasher, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("Login com senha incorreta verifica o hash real e responde exatamente como o e-mail inexistente")
    void loginWithWrongPasswordVerifiesRealHash() {
        localUser();

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(login(EMAIL, "Senha-Errada-1")));

        assertInvalidCredentials(ex);
        verify(passwordHasher).matches("Senha-Errada-1", STORED_HASH);
        verify(passwordHasher, never()).simulateVerification(anyString());
    }

    @Test
    @DisplayName("Conta desativada ou federada também passa pela verificação de custo equivalente")
    void disabledOrFederatedAccountSimulatesVerification() {
        User disabled = new User(UUID.randomUUID(), EMAIL, STORED_HASH, AuthProvider.LOCAL, null);
        disabled.softDelete();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(disabled));
        assertInvalidCredentials(assertThrows(BusinessException.class, () -> authService.login(login(EMAIL, PASSWORD))));

        User federated = new User(UUID.randomUUID(), "google@rewit.com", null, AuthProvider.GOOGLE, "sub-123");
        when(userRepository.findByEmail("google@rewit.com")).thenReturn(Optional.of(federated));
        assertInvalidCredentials(assertThrows(BusinessException.class,
                () -> authService.login(login("google@rewit.com", PASSWORD))));

        verify(passwordHasher, times(2)).simulateVerification(PASSWORD);
        verify(passwordHasher, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("Limite de login: a 11ª tentativa sem sucesso recebe 429 sem consultar conta nem senha")
    void loginLimitReachedRejectsBeforeVerification() {
        localUser();
        for (int i = 0; i < 10; i++) {
            assertInvalidCredentials(assertThrows(BusinessException.class,
                    () -> authService.login(login(EMAIL, "Senha-Errada-1"))));
        }
        clearInvocations(userRepository, passwordHasher);

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(login(EMAIL, PASSWORD)));

        assertTooManyRequests(ex, "Muitas tentativas de login. Tente novamente mais tarde.");
        verifyNoInteractions(userRepository, passwordHasher);
    }

    @Test
    @DisplayName("O limite de login é o mesmo para e-mail inexistente e existente, e ignora caixa e espaços")
    void loginLimitDoesNotRevealAccountExistence() {
        localUser();
        for (int i = 0; i < 10; i++) {
            String existingEmailVariant = i % 2 == 0 ? EMAIL : "  ALVO@Rewit.com ";
            assertThrows(BusinessException.class, () -> authService.login(login("ninguem@rewit.com", PASSWORD)));
            assertThrows(BusinessException.class, () -> authService.login(login(existingEmailVariant, "x-errada")));
        }

        assertTooManyRequests(assertThrows(BusinessException.class,
                () -> authService.login(login("ninguem@rewit.com", PASSWORD))), null);
        assertTooManyRequests(assertThrows(BusinessException.class,
                () -> authService.login(login(EMAIL, PASSWORD))), null);
    }

    @Test
    @DisplayName("Login bem-sucedido não conta como tentativa; falhas anteriores continuam contadas")
    void successfulLoginReleasesItsAttempt() {
        localUser();
        for (int i = 0; i < 9; i++) {
            assertThrows(BusinessException.class, () -> authService.login(login(EMAIL, "x-errada")));
        }
        for (int i = 0; i < 5; i++) {
            AuthResult result = authService.login(login(EMAIL, PASSWORD));
            assertNotNull(result.accessToken());
        }
        assertInvalidCredentials(assertThrows(BusinessException.class, () -> authService.login(login(EMAIL, "x-errada"))));

        assertTooManyRequests(assertThrows(BusinessException.class, () -> authService.login(login(EMAIL, PASSWORD))), null);
    }

    @Test
    @DisplayName("Após a janela de login, novas tentativas são aceitas")
    void loginAllowedAgainAfterWindow() {
        localUser();
        for (int i = 0; i < 10; i++) {
            assertThrows(BusinessException.class, () -> authService.login(login(EMAIL, "x-errada")));
        }
        assertTooManyRequests(assertThrows(BusinessException.class, () -> authService.login(login(EMAIL, PASSWORD))), null);

        clock.advance(Duration.ofMinutes(15).plusMillis(1));

        assertNotNull(authService.login(login(EMAIL, PASSWORD)).refreshToken());
    }

    @Test
    @DisplayName("Tempo uniforme: com Argon2 real, e-mail inexistente custa o mesmo que senha incorreta")
    void unknownEmailTakesComparableTimeToWrongPassword() {
        Argon2PasswordHasher argon2 = new Argon2PasswordHasher();
        passwordHasher = argon2;
        rateLimitProperties.getAuth().getLogin().setEnabled(false);
        rebuildService();
        User user = new User(UUID.randomUUID(), EMAIL, argon2.hash(PASSWORD), AuthProvider.LOCAL, null);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        long unknown = medianLoginNanos("ninguem@rewit.com");
        long wrongPassword = medianLoginNanos(EMAIL);

        // Antes, o e-mail inexistente respondia sem nenhum Argon2 (ordens de grandeza mais rápido)
        assertTrue(unknown * 2 >= wrongPassword,
                "e-mail inexistente " + unknown / 1_000_000 + "ms vs senha incorreta " + wrongPassword / 1_000_000 + "ms");
    }

    // ------------------------------------------------------------------ cadastro

    @Test
    @DisplayName("Limite de cadastro: tentativas inválidas também contam; a excedente recebe 429 sem tocar o banco")
    void registrationLimitCountsEveryAttempt() {
        rateLimitProperties.getAuth().getRegistration().setLimit(3);
        rebuildService();
        stubRegistrationPersistence();

        authService.register(register("um@rewit.com", "handle_um"));
        assertThrows(BusinessException.class, () -> authService.register(
                new RegisterCommand("dois@rewit.com", "curta", "handle_dois", "Dois", null, null)));
        authService.register(register("tres@rewit.com", "handle_tres"));
        clearInvocations(userRepository, profileRepository);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.register(register("quatro@rewit.com", "handle_quatro")));

        assertTooManyRequests(ex, "Muitas tentativas de cadastro. Tente novamente mais tarde.");
        verifyNoInteractions(userRepository, profileRepository);
    }

    @Test
    @DisplayName("Após a janela de cadastro, novos cadastros são aceitos")
    void registrationAllowedAgainAfterWindow() {
        rateLimitProperties.getAuth().getRegistration().setLimit(1);
        rebuildService();
        stubRegistrationPersistence();
        authService.register(register("um@rewit.com", "handle_um"));
        assertThrows(BusinessException.class, () -> authService.register(register("dois@rewit.com", "handle_dois")));

        clock.advance(Duration.ofMinutes(1).plusMillis(1));

        assertNotNull(authService.register(register("dois@rewit.com", "handle_dois")).accessToken());
    }

    // ------------------------------------------------------------------ refresh

    @Test
    @DisplayName("Refresh válido dentro do limite rotaciona a sessão normalmente")
    void validRefreshRotatesSession() {
        User user = localUser();
        AuthSession current = activeSession(user, "token-atual");

        AuthResult result = authService.refresh(new RefreshCommand("token-atual", null, null));

        assertNotEquals("token-atual", result.refreshToken());
        assertTrue(current.isRevoked());
        assertNotNull(current.getReplacedBySessionId());
    }

    @Test
    @DisplayName("Limite de refresh por usuário: a excedente recebe 429 sem rotacionar nem consumir o token; após a janela, funciona")
    void refreshLimitDoesNotConsumeToken() {
        rateLimitProperties.getAuth().getRefresh().setLimit(2);
        rebuildService();
        User user = localUser();
        activeSession(user, "token-1");
        activeSession(user, "token-2");
        authService.refresh(new RefreshCommand("token-1", null, null));
        authService.refresh(new RefreshCommand("token-2", null, null));
        AuthSession third = activeSession(user, "token-3");
        clearInvocations(authSessionRepository);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.refresh(new RefreshCommand("token-3", null, null)));

        assertTooManyRequests(ex, "Muitas renovações de sessão. Tente novamente mais tarde.");
        verify(authSessionRepository, never()).save(any(AuthSession.class));
        assertTrue(third.isValid(), "o token não foi consumido");

        clock.advance(Duration.ofMinutes(5).plusMillis(1));
        authService.refresh(new RefreshCommand("token-3", null, null));
        assertTrue(third.isRevoked());
    }

    @Test
    @DisplayName("Detecção de reúso continua revogando todas as sessões mesmo com o limite de refresh esgotado")
    void reuseDetectionIsNotBlockedByRateLimit() {
        rateLimitProperties.getAuth().getRefresh().setLimit(1);
        rebuildService();
        User user = localUser();
        activeSession(user, "token-1");
        authService.refresh(new RefreshCommand("token-1", null, null));
        AuthSession revoked = activeSession(user, "token-revogado");
        revoked.revoke();

        assertThrows(RefreshTokenReuseDetectedException.class,
                () -> authService.refresh(new RefreshCommand("token-revogado", null, null)));

        verify(authSessionRepository).revokeAllByUserId(user.getId());
    }

    // ------------------------------------------------------------------ helpers

    private void rebuildService() {
        authService = new AuthService(userRepository, profileRepository, authSessionRepository, passwordHasher,
                tokenService, RateLimitTestSupport.inMemory(rateLimitProperties, clock, new SimpleMeterRegistry()));
    }

    private User localUser() {
        User user = new User(UUID.randomUUID(), EMAIL, STORED_HASH, AuthProvider.LOCAL, null);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordHasher.matches(PASSWORD, STORED_HASH)).thenReturn(true);
        when(profileRepository.findByUserId(user.getId()))
                .thenReturn(Optional.of(new Profile(UUID.randomUUID(), user.getId(), "alvo", "Alvo", null, null)));
        return user;
    }

    private AuthSession activeSession(User user, String rawToken) {
        String hash = tokenService.hashRefreshToken(rawToken);
        AuthSession session = new AuthSession(UUID.randomUUID(), user.getId(), hash,
                Instant.now().plus(Duration.ofDays(30)), null, null);
        when(authSessionRepository.findByTokenHashForUpdate(hash)).thenReturn(Optional.of(session));
        return session;
    }

    private void stubRegistrationPersistence() {
        when(passwordHasher.hash(anyString())).thenReturn(STORED_HASH);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(profileRepository.save(any(Profile.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private long medianLoginNanos(String email) {
        long[] samples = new long[7];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            assertThrows(BusinessException.class, () -> authService.login(login(email, "Senha-Errada-1")));
            samples[i] = System.nanoTime() - start;
        }
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    private static LoginCommand login(String email, String password) {
        return new LoginCommand(email, password, null, null);
    }

    private static RegisterCommand register(String email, String handle) {
        return new RegisterCommand(email, PASSWORD, handle, "Nome", null, null);
    }

    private static void assertInvalidCredentials(BusinessException ex) {
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        assertEquals("INVALID_CREDENTIALS", ex.getErrorCode());
        assertEquals("Credenciais inválidas", ex.getMessage());
    }

    private static void assertTooManyRequests(BusinessException ex, String expectedMessage) {
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        assertEquals("RATE_LIMIT_EXCEEDED", ex.getErrorCode());
        if (expectedMessage != null) {
            assertEquals(expectedMessage, ex.getMessage());
        }
    }
}
