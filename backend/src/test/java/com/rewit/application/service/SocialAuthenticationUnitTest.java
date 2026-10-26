package com.rewit.application.service;

import com.rewit.application.dto.auth.AuthDtos.*;
import com.rewit.application.port.AuthSessionRepository;
import com.rewit.application.port.EmailReservation;
import com.rewit.application.port.ExternalIdentityVerifier;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.SocialLoginNonceStore;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.AuthSession;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.ratelimit.RateLimitTestSupport;
import com.rewit.infrastructure.security.Argon2PasswordHasher;
import com.rewit.infrastructure.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("Login social: nonce, mapeamento da identidade externa e emissão da sessão Rewit")
class SocialAuthenticationUnitTest {

    private static final String VALID_NONCE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private UserRepository userRepository;
    private ProfileRepository profileRepository;
    private AuthSessionRepository authSessionRepository;
    private EmailReservation emailReservation;
    private ExternalIdentityVerifier verifier;
    private SocialLoginNonceStore nonceStore;
    private AuthService authService;
    private SocialAuthService socialAuthService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        profileRepository = mock(ProfileRepository.class);
        authSessionRepository = mock(AuthSessionRepository.class);
        emailReservation = mock(EmailReservation.class);
        verifier = mock(ExternalIdentityVerifier.class);
        nonceStore = mock(SocialLoginNonceStore.class);

        authService = new AuthService(userRepository, profileRepository, authSessionRepository,
                new Argon2PasswordHasher(),
                new JwtTokenService("super_secret_local_jwt_key_that_is_at_least_32_bytes_long_12345",
                        "rewit-api", "rewit-clients", 900, 2592000),
                RateLimitTestSupport.inMemory(), emailReservation);
        socialAuthService = new SocialAuthService(authService, verifier, nonceStore, RateLimitTestSupport.inMemory());

        when(verifier.isEnabled(AuthProvider.GOOGLE)).thenReturn(true);
        when(verifier.isEnabled(AuthProvider.APPLE)).thenReturn(true);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(profileRepository.save(any(Profile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(authSessionRepository.save(any(AuthSession.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ExternalIdentity google(String subject, String email) {
        return new ExternalIdentity(AuthProvider.GOOGLE, subject, email, true, "Maria Silva");
    }

    // -----------------------------------------------------------------------------------------------------
    // Nonce
    // -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Nonce emitido tem 256 bits e somente o seu hash é guardado, com TTL")
    void issueNonceStoresOnlyHash() {
        SocialNonceResult result = socialAuthService.issueNonce();

        assertTrue(result.nonce().matches("^[A-Za-z0-9_-]{43}$"));
        assertEquals(600, result.expiresInSeconds());
        verify(nonceStore).store(SocialAuthService.hashNonce(result.nonce()), Duration.ofMinutes(10));
        assertNotEquals(result.nonce(), socialAuthService.issueNonce().nonce());
    }

    @Test
    @DisplayName("Hash do nonce é o SHA-256 hex minúsculo (o mesmo calculado pelo app)")
    void nonceHashFormat() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SocialAuthService.hashNonce("abc"));
    }

    @Test
    @DisplayName("Nonce com formato inválido é recusado sem consultar o armazenamento nem o provedor")
    void malformedNonce() {
        assertBusiness(HttpStatus.UNAUTHORIZED, "INVALID_SOCIAL_NONCE",
                () -> socialAuthService.authenticate(cmd(AuthProvider.GOOGLE, "token", "curto")));
        verifyNoInteractions(nonceStore);
        verify(verifier, never()).verify(any(), any(), any());
    }

    @Test
    @DisplayName("Nonce desconhecido, expirado ou já usado é recusado antes da validação do token")
    void unknownOrReusedNonce() {
        when(nonceStore.consume(anyString())).thenReturn(false);

        assertBusiness(HttpStatus.UNAUTHORIZED, "INVALID_SOCIAL_NONCE",
                () -> socialAuthService.authenticate(cmd(AuthProvider.GOOGLE, "token", VALID_NONCE)));
        verify(verifier, never()).verify(any(), any(), any());
        verify(authSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("O token é validado contra o hash do nonce consumido")
    void verifiesAgainstNonceHash() {
        when(nonceStore.consume(SocialAuthService.hashNonce(VALID_NONCE))).thenReturn(true);
        when(verifier.verify(AuthProvider.GOOGLE, "token", SocialAuthService.hashNonce(VALID_NONCE)))
                .thenThrow(new BusinessException("Credencial do provedor inválida", HttpStatus.UNAUTHORIZED, "INVALID_SOCIAL_TOKEN"));

        assertBusiness(HttpStatus.UNAUTHORIZED, "INVALID_SOCIAL_TOKEN",
                () -> socialAuthService.authenticate(cmd(AuthProvider.GOOGLE, "token", VALID_NONCE)));
        verify(userRepository, never()).save(any());
        verify(authSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Provedor desligado, LOCAL ou ausente responde 503 sem consumir nonce")
    void disabledProvider() {
        when(verifier.isEnabled(AuthProvider.APPLE)).thenReturn(false);

        assertBusiness(HttpStatus.SERVICE_UNAVAILABLE, "SOCIAL_PROVIDER_UNAVAILABLE",
                () -> socialAuthService.authenticate(cmd(AuthProvider.APPLE, "token", VALID_NONCE)));
        assertBusiness(HttpStatus.SERVICE_UNAVAILABLE, "SOCIAL_PROVIDER_UNAVAILABLE",
                () -> socialAuthService.authenticate(cmd(AuthProvider.LOCAL, "token", VALID_NONCE)));
        assertBusiness(HttpStatus.SERVICE_UNAVAILABLE, "SOCIAL_PROVIDER_UNAVAILABLE",
                () -> socialAuthService.authenticate(cmd(null, "token", VALID_NONCE)));
        verifyNoInteractions(nonceStore);
    }

    @Test
    @DisplayName("Criação concorrente da mesma identidade: a segunda transação encontra a conta e autentica")
    void retriesAfterConcurrentCreation() {
        ExternalIdentity identity = google("sub-concorrente", "maria@gmail.com");
        when(nonceStore.consume(anyString())).thenReturn(true);
        when(verifier.verify(eq(AuthProvider.GOOGLE), eq("token"), anyString())).thenReturn(identity);

        User existing = new User(UUID.randomUUID(), "maria@gmail.com", null, AuthProvider.GOOGLE, "sub-concorrente");
        Profile profile = new Profile(UUID.randomUUID(), existing.getId(), "usuario_existente", "Maria", null, null);
        when(userRepository.findByAuthProviderAndProviderUserId(AuthProvider.GOOGLE, "sub-concorrente"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("uq_users_provider_user_id"));
        when(profileRepository.findByUserId(existing.getId())).thenReturn(Optional.of(profile));

        AuthResult result = socialAuthService.authenticate(cmd(AuthProvider.GOOGLE, "token", VALID_NONCE));

        assertEquals(existing.getId(), result.user().getId());
        verify(nonceStore, times(1)).consume(anyString());
    }

    // -----------------------------------------------------------------------------------------------------
    // Mapeamento da identidade (AuthService)
    // -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Identidade nova com e-mail verificado cria conta federada sem senha, perfil e sessão Rewit")
    void createsFederatedAccount() {
        when(userRepository.findByAuthProviderAndProviderUserId(any(), any())).thenReturn(Optional.empty());

        AuthResult result = authService.authenticateExternalIdentity(google("google-sub-1", "Maria@Gmail.com"), "UA", "127.0.0.1");

        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(user.capture());
        assertEquals("maria@gmail.com", user.getValue().getEmail());
        assertEquals(AuthProvider.GOOGLE, user.getValue().getAuthProvider());
        assertEquals("google-sub-1", user.getValue().getProviderUserId());
        assertNull(user.getValue().getPasswordHash(), "Conta federada não possui senha local");

        assertTrue(result.profile().getHandle().matches("^usuario_[a-z0-9]{10}$"), "Handle não deriva do e-mail");
        assertEquals("Maria Silva", result.profile().getDisplayName());
        assertNotNull(result.accessToken());
        assertNotNull(result.refreshToken());
        verify(authSessionRepository).save(any(AuthSession.class));
    }

    @Test
    @DisplayName("Identidade já vinculada autentica a conta vinculada, independente do e-mail atual do token")
    void linkedIdentityLogsIntoLinkedAccount() {
        User linked = new User(UUID.randomUUID(), "antigo@gmail.com", null, AuthProvider.GOOGLE, "google-sub-1");
        Profile profile = new Profile(UUID.randomUUID(), linked.getId(), "maria", "Maria", null, null);
        when(userRepository.findByAuthProviderAndProviderUserId(AuthProvider.GOOGLE, "google-sub-1")).thenReturn(Optional.of(linked));
        when(profileRepository.findByUserId(linked.getId())).thenReturn(Optional.of(profile));

        AuthResult result = authService.authenticateExternalIdentity(google("google-sub-1", "novo@gmail.com"), null, null);

        assertEquals(linked.getId(), result.user().getId());
        assertEquals("antigo@gmail.com", result.user().getEmail());
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).existsByEmail(any());
    }

    @Test
    @DisplayName("Conta vinculada desativada, suspensa ou excluída responde 401 ACCOUNT_DISABLED sem sessão")
    void linkedAccountNotOperational() {
        User deactivated = new User(UUID.randomUUID(), "a@gmail.com", null, AuthProvider.GOOGLE, "sub-a");
        deactivated.deactivate();
        User suspended = new User(UUID.randomUUID(), "b@gmail.com", null, AuthProvider.GOOGLE, "sub-b");
        suspended.suspend();
        User deleted = new User(UUID.randomUUID(), "c@gmail.com", null, AuthProvider.GOOGLE, "sub-c");
        deleted.softDelete();
        when(userRepository.findByAuthProviderAndProviderUserId(AuthProvider.GOOGLE, "sub-a")).thenReturn(Optional.of(deactivated));
        when(userRepository.findByAuthProviderAndProviderUserId(AuthProvider.GOOGLE, "sub-b")).thenReturn(Optional.of(suspended));
        when(userRepository.findByAuthProviderAndProviderUserId(AuthProvider.GOOGLE, "sub-c")).thenReturn(Optional.of(deleted));

        for (String sub : new String[]{"sub-a", "sub-b", "sub-c"}) {
            assertBusiness(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED",
                    () -> authService.authenticateExternalIdentity(google(sub, "x@gmail.com"), null, null));
        }
        verify(authSessionRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("E-mail de conta local existente: 409 sem vinculação nem criação de conta")
    void emailOfLocalAccountIsNotLinked() {
        when(userRepository.findByAuthProviderAndProviderUserId(any(), any())).thenReturn(Optional.empty());
        when(userRepository.existsByEmail("maria@gmail.com")).thenReturn(true);

        assertBusiness(HttpStatus.CONFLICT, "SOCIAL_ACCOUNT_EMAIL_CONFLICT",
                () -> authService.authenticateExternalIdentity(google("google-sub-novo", "maria@gmail.com"), null, null));
        verify(userRepository, never()).save(any());
        verify(profileRepository, never()).save(any());
        verify(authSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("E-mail reservado de conta excluída e purgada também conflita")
    void reservedEmailConflicts() {
        when(userRepository.findByAuthProviderAndProviderUserId(any(), any())).thenReturn(Optional.empty());
        when(emailReservation.isConfigured()).thenReturn(true);
        when(emailReservation.reservedEmailFor("maria@gmail.com")).thenReturn("hmac@deleted.invalid");
        when(userRepository.existsByEmail("hmac@deleted.invalid")).thenReturn(true);

        assertBusiness(HttpStatus.CONFLICT, "SOCIAL_ACCOUNT_EMAIL_CONFLICT",
                () -> authService.authenticateExternalIdentity(google("sub", "maria@gmail.com"), null, null));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Sem e-mail atestado pelo provedor não há criação de conta")
    void unverifiedOrMissingEmail() {
        when(userRepository.findByAuthProviderAndProviderUserId(any(), any())).thenReturn(Optional.empty());

        assertBusiness(HttpStatus.UNPROCESSABLE_CONTENT, "SOCIAL_EMAIL_NOT_VERIFIED", () -> authService.authenticateExternalIdentity(
                new ExternalIdentity(AuthProvider.APPLE, "sub", "a@b.com", false, null), null, null));
        assertBusiness(HttpStatus.UNPROCESSABLE_CONTENT, "SOCIAL_EMAIL_NOT_VERIFIED", () -> authService.authenticateExternalIdentity(
                new ExternalIdentity(AuthProvider.APPLE, "sub", null, true, null), null, null));
        assertBusiness(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_EMAIL", () -> authService.authenticateExternalIdentity(
                new ExternalIdentity(AuthProvider.APPLE, "sub", "x@deleted.invalid", true, null), null, null));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Identidade sem provedor federado ou sem subject é erro de programação")
    void rejectsLocalOrBlankSubject() {
        assertThrows(IllegalArgumentException.class, () -> authService.authenticateExternalIdentity(
                new ExternalIdentity(AuthProvider.LOCAL, "sub", "a@b.com", true, null), null, null));
        assertThrows(IllegalArgumentException.class, () -> authService.authenticateExternalIdentity(
                new ExternalIdentity(AuthProvider.GOOGLE, " ", "a@b.com", true, null), null, null));
    }

    @Test
    @DisplayName("Nome de exibição: claim normalizada nos limites do cadastro, ou padrão sem claim útil")
    void socialDisplayName() {
        assertEquals("Usuário Rewit", AuthService.socialDisplayName(null));
        assertEquals("Usuário Rewit", AuthService.socialDisplayName(" x "));
        assertEquals("Ana Maria", AuthService.socialDisplayName("  Ana \n  Maria "));
        assertEquals(100, AuthService.socialDisplayName("a".repeat(150)).length());
    }

    @Test
    @DisplayName("Conta federada continua sem acesso pelo login local por senha")
    void federatedAccountCannotUseLocalLogin() {
        User federated = new User(UUID.randomUUID(), "maria@gmail.com", null, AuthProvider.GOOGLE, "sub");
        when(userRepository.findByEmail("maria@gmail.com")).thenReturn(Optional.of(federated));

        assertBusiness(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                () -> authService.login(new LoginCommand("maria@gmail.com", "qualquerSenha1", null, null)));
    }

    private static SocialLoginCommand cmd(AuthProvider provider, String idToken, String nonce) {
        return new SocialLoginCommand(provider, idToken, nonce, "UA", "127.0.0.1");
    }

    private static void assertBusiness(HttpStatus status, String code, org.junit.jupiter.api.function.Executable executable) {
        BusinessException ex = assertThrows(BusinessException.class, executable);
        assertEquals(status, ex.getStatus());
        assertEquals(code, ex.getErrorCode());
    }
}
