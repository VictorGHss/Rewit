package com.rewit.application.service;

import com.rewit.application.dto.user.UserDtos.UpdateProfileCommand;
import com.rewit.application.dto.user.UserDtos.UserProfileResult;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: UserService (Step 5)")
class UserServiceUnitTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProfileRepository profileRepository;

    @Mock
    private com.rewit.application.port.PasswordHasher passwordHasher;

    @Mock
    private com.rewit.application.port.AuthSessionRepository authSessionRepository;

    private UserService userService;

    private final UUID userId = UUID.randomUUID();
    private User testUser;
    private Profile testProfile;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, profileRepository, passwordHasher, authSessionRepository);

        testUser = new User(userId, "usuario@rewit.com", "hashSeguro", AuthProvider.LOCAL, null);
        testProfile = new Profile(UUID.randomUUID(), userId, "usuario_atual", "Nome Atual", "Bio antiga", null);
    }

    @Test
    @DisplayName("getMe: Deve retornar User e Profile com sucesso para usuário ativo")
    void shouldReturnUserAndProfileSuccessfully() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));

        UserProfileResult result = userService.getMe(userId);

        assertNotNull(result);
        assertEquals(userId, result.user().getId());
        assertEquals("usuario@rewit.com", result.user().getEmail());
        assertEquals("usuario_atual", result.profile().getHandle());
        assertEquals("Nome Atual", result.profile().getDisplayName());
    }

    @Test
    @DisplayName("getMe: Deve lançar ACCOUNT_DISABLED (401) se usuário não existir")
    void shouldThrowWhenUserNotFound() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.getMe(userId));
        assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    @DisplayName("getMe: Deve lançar ACCOUNT_DISABLED (401) se usuário estiver soft-deleted")
    void shouldThrowWhenUserIsSoftDeleted() {
        testUser.softDelete();
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.getMe(userId));
        assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    @DisplayName("getMe: Deve lançar PROFILE_NOT_FOUND (404) se perfil não for encontrado")
    void shouldThrowWhenProfileNotFound() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.getMe(userId));
        assertEquals("PROFILE_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("updateProfile: Deve atualizar perfil com novo handle normalizado e disponível")
    void shouldUpdateProfileWithNewAvailableHandle() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));
        when(profileRepository.existsByHandle("novo_handle")).thenReturn(false);
        when(profileRepository.save(any(Profile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProfileCommand cmd = new UpdateProfileCommand(
                userId,
                "  @Novo_Handle  ",
                "Novo Nome",
                "Nova Bio",
                true
        );

        UserProfileResult result = userService.updateProfile(cmd);

        assertNotNull(result);
        assertEquals("novo_handle", result.profile().getHandle());
        assertEquals("Novo Nome", result.profile().getDisplayName());
        assertEquals("Nova Bio", result.profile().getBio());
        assertTrue(result.profile().isAnonymousDefault());
        verify(profileRepository).save(any(Profile.class));
    }

    @Test
    @DisplayName("updateProfile: Não deve consultar existsByHandle se o handle for idêntico ao atual")
    void shouldNotCheckExistsIfHandleIsUnchanged() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));
        when(profileRepository.save(any(Profile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProfileCommand cmd = new UpdateProfileCommand(
                userId,
                "@USUARIO_ATUAL",
                "Nome Alterado",
                null,
                null
        );

        UserProfileResult result = userService.updateProfile(cmd);

        assertNotNull(result);
        assertEquals("usuario_atual", result.profile().getHandle());
        assertEquals("Nome Alterado", result.profile().getDisplayName());
        verify(profileRepository, never()).existsByHandle(anyString());
    }

    @Test
    @DisplayName("updateProfile: Deve rejeitar com HANDLE_ALREADY_EXISTS (409) se novo handle já estiver em uso")
    void shouldRejectWhenNewHandleAlreadyExists() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));
        when(profileRepository.existsByHandle("handle_ocupado")).thenReturn(true);

        UpdateProfileCommand cmd = new UpdateProfileCommand(
                userId,
                "handle_ocupado",
                "Nome",
                null,
                null
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.updateProfile(cmd));
        assertEquals("HANDLE_ALREADY_EXISTS", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(profileRepository, never()).save(any(Profile.class));
    }

    @Test
    @DisplayName("updateProfile: Deve preservar campos preexistentes quando valores do comando forem nulos")
    void shouldPreserveExistingFieldsWhenCommandFieldsAreNull() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));
        when(profileRepository.save(any(Profile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProfileCommand cmd = new UpdateProfileCommand(
                userId,
                null,
                null,
                null,
                null
        );

        UserProfileResult result = userService.updateProfile(cmd);

        assertNotNull(result);
        assertEquals("usuario_atual", result.profile().getHandle());
        assertEquals("Nome Atual", result.profile().getDisplayName());
        assertEquals("Bio antiga", result.profile().getBio());
        assertFalse(result.profile().isAnonymousDefault());
    }

    @Test
    @DisplayName("updateProfile: Deve limpar bio (atribuir null) quando string vazia for fornecida")
    void shouldClearBioWhenBioIsEmptyString() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));
        when(profileRepository.save(any(Profile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProfileCommand cmd = new UpdateProfileCommand(
                userId,
                null,
                null,
                "   ",
                null
        );

        UserProfileResult result = userService.updateProfile(cmd);

        assertNotNull(result);
        assertNull(result.profile().getBio());
    }

    @Test
    @DisplayName("updateProfile: Deve preservar bio preexistente quando bio for explicitamente null")
    void shouldPreserveBioWhenBioIsNull() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));
        when(profileRepository.save(any(Profile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProfileCommand cmd = new UpdateProfileCommand(
                userId,
                null,
                null,
                null,
                null
        );

        UserProfileResult result = userService.updateProfile(cmd);

        assertNotNull(result);
        assertEquals("Bio antiga", result.profile().getBio());
    }

    @Test
    @DisplayName("changePassword: Deve alterar senha com sucesso, persistir novo hash e revogar todas as sessões")
    void shouldChangePasswordSuccessfullyWhenCurrentPasswordIsCorrect() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(passwordHasher.matches("SenhaAtual@123", "hashSeguro")).thenReturn(true);
        when(passwordHasher.hash("NovaSenhaForte@456")).thenReturn("novoHashArgon2id");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        com.rewit.application.dto.user.UserDtos.ChangePasswordCommand cmd =
                new com.rewit.application.dto.user.UserDtos.ChangePasswordCommand(
                        userId,
                        "SenhaAtual@123",
                        "NovaSenhaForte@456"
                );

        userService.changePassword(cmd);

        assertEquals("novoHashArgon2id", testUser.getPasswordHash());
        verify(passwordHasher).matches("SenhaAtual@123", "hashSeguro");
        verify(passwordHasher).hash("NovaSenhaForte@456");
        verify(userRepository).save(testUser);
        verify(authSessionRepository).revokeAllByUserId(userId);
    }

    @Test
    @DisplayName("changePassword: Deve rejeitar com INVALID_CREDENTIALS (401) quando senha atual estiver incorreta")
    void shouldRejectWhenCurrentPasswordIsIncorrect() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(passwordHasher.matches("SenhaErrada@123", "hashSeguro")).thenReturn(false);

        com.rewit.application.dto.user.UserDtos.ChangePasswordCommand cmd =
                new com.rewit.application.dto.user.UserDtos.ChangePasswordCommand(
                        userId,
                        "SenhaErrada@123",
                        "NovaSenhaForte@456"
                );

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.changePassword(cmd));

        assertEquals("INVALID_CREDENTIALS", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        assertEquals("Senha atual incorreta", ex.getMessage());

        verify(passwordHasher, never()).hash(anyString());
        verify(userRepository, never()).save(any(User.class));
        verify(authSessionRepository, never()).revokeAllByUserId(any(UUID.class));
    }

    @Test
    @DisplayName("changePassword: Deve rejeitar contas de provedores externos com LOCAL_AUTH_REQUIRED (400)")
    void shouldRejectWhenUserIsNotLocalAuth() {
        User googleUser = new User(userId, "google.user@rewit.com", null, AuthProvider.GOOGLE, null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(googleUser));

        com.rewit.application.dto.user.UserDtos.ChangePasswordCommand cmd =
                new com.rewit.application.dto.user.UserDtos.ChangePasswordCommand(
                        userId,
                        "QualquerSenha123",
                        "NovaSenhaForte@456"
                );

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.changePassword(cmd));

        assertEquals("LOCAL_AUTH_REQUIRED", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(passwordHasher, never()).matches(anyString(), anyString());
        verify(passwordHasher, never()).hash(anyString());
        verify(userRepository, never()).save(any(User.class));
        verify(authSessionRepository, never()).revokeAllByUserId(any(UUID.class));
    }

    @Test
    @DisplayName("changePassword: Deve lançar ACCOUNT_DISABLED (401) se usuário não existir")
    void shouldThrowWhenUserNotFoundOnChangePassword() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        com.rewit.application.dto.user.UserDtos.ChangePasswordCommand cmd =
                new com.rewit.application.dto.user.UserDtos.ChangePasswordCommand(
                        userId,
                        "SenhaAtual@123",
                        "NovaSenhaForte@456"
                );

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.changePassword(cmd));
        assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verify(authSessionRepository, never()).revokeAllByUserId(any());
    }

    @Test
    @DisplayName("changePassword: Deve lançar ACCOUNT_DISABLED (401) se usuário estiver soft-deleted")
    void shouldThrowWhenUserIsSoftDeletedOnChangePassword() {
        testUser.softDelete();
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        com.rewit.application.dto.user.UserDtos.ChangePasswordCommand cmd =
                new com.rewit.application.dto.user.UserDtos.ChangePasswordCommand(
                        userId,
                        "SenhaAtual@123",
                        "NovaSenhaForte@456"
                );

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.changePassword(cmd));
        assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verify(authSessionRepository, never()).revokeAllByUserId(any());
    }

    @Test
    @DisplayName("changePassword: Deve rejeitar nova senha que viola política de tamanho mínimo (422)")
    void shouldRejectWhenNewPasswordViolatesPolicy() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(passwordHasher.matches("SenhaAtual@123", "hashSeguro")).thenReturn(true);

        com.rewit.application.dto.user.UserDtos.ChangePasswordCommand cmd =
                new com.rewit.application.dto.user.UserDtos.ChangePasswordCommand(
                        userId,
                        "SenhaAtual@123",
                        "123"
                );

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.changePassword(cmd));
        assertEquals("INVALID_PASSWORD_POLICY", ex.getErrorCode());
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, ex.getStatus());
        assertEquals(422, ex.getStatus().value());
        verify(passwordHasher, never()).hash(anyString());
        verify(userRepository, never()).save(any());
        verify(authSessionRepository, never()).revokeAllByUserId(any());
    }

    @Test
    @DisplayName("changePassword: Deve rejeitar nova senha que excede 128 caracteres para caller interno (422)")
    void shouldRejectWhenNewPasswordExceeds128Characters() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(passwordHasher.matches("SenhaAtual@123", "hashSeguro")).thenReturn(true);

        String tooLongPassword = "a".repeat(129);
        com.rewit.application.dto.user.UserDtos.ChangePasswordCommand cmd =
                new com.rewit.application.dto.user.UserDtos.ChangePasswordCommand(
                        userId,
                        "SenhaAtual@123",
                        tooLongPassword
                );

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.changePassword(cmd));
        assertEquals("INVALID_PASSWORD_POLICY", ex.getErrorCode());
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, ex.getStatus());
        assertEquals(422, ex.getStatus().value());
        verify(passwordHasher, never()).hash(anyString());
        verify(userRepository, never()).save(any());
        verify(authSessionRepository, never()).revokeAllByUserId(any());
    }

    @Test
    @DisplayName("changePassword: Deve rejeitar nova senha nula para caller interno (422)")
    void shouldRejectWhenNewPasswordIsNull() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(passwordHasher.matches("SenhaAtual@123", "hashSeguro")).thenReturn(true);

        com.rewit.application.dto.user.UserDtos.ChangePasswordCommand cmd =
                new com.rewit.application.dto.user.UserDtos.ChangePasswordCommand(
                        userId,
                        "SenhaAtual@123",
                        null
                );

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.changePassword(cmd));
        assertEquals("INVALID_PASSWORD_POLICY", ex.getErrorCode());
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, ex.getStatus());
        assertEquals(422, ex.getStatus().value());
        verify(passwordHasher, never()).hash(anyString());
        verify(userRepository, never()).save(any());
        verify(authSessionRepository, never()).revokeAllByUserId(any());
    }

    @Test
    @DisplayName("changePassword: Nenhuma senha ou hash deve vazar nas mensagens de exceção")
    void shouldNotLeakPasswordOrHashInExceptionMessages() {
        String sensitiveCurrentPassword = "MinhaSenhaSuperSecreta@999";
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(passwordHasher.matches(sensitiveCurrentPassword, "hashSeguro")).thenReturn(false);

        com.rewit.application.dto.user.UserDtos.ChangePasswordCommand cmd =
                new com.rewit.application.dto.user.UserDtos.ChangePasswordCommand(
                        userId,
                        sensitiveCurrentPassword,
                        "OutraSenhaSuperSecreta@888"
                );

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.changePassword(cmd));
        assertFalse(ex.getMessage().contains(sensitiveCurrentPassword));
        assertFalse(ex.getMessage().contains("OutraSenhaSuperSecreta@888"));
        assertFalse(ex.getMessage().contains("hashSeguro"));
    }
}
