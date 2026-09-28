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

    private UserService userService;

    private final UUID userId = UUID.randomUUID();
    private User testUser;
    private Profile testProfile;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, profileRepository);

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
}
