package com.rewit.application.service;

import com.rewit.application.dto.user.UserDtos.PublicUserProfileView;
import com.rewit.application.port.*;
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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: UserService.getPublicProfile e Estatísticas Factuais (Step 18.0)")
class UserPublicProfileUnitTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProfileRepository profileRepository;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private AuthSessionRepository authSessionRepository;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private UserFollowRepository userFollowRepository;

    @Mock
    private ReviewReactionRepository reviewReactionRepository;

    private UserService userService;

    private final UUID targetUserId = UUID.randomUUID();
    private final UUID requesterUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository,
                profileRepository,
                passwordHasher,
                authSessionRepository,
                reviewRepository,
                userFollowRepository,
                reviewReactionRepository
        );
    }

    private User createActiveUser(UUID userId) {
        return new User(userId, "vitin@rewit.com", "hashed_pwd", AuthProvider.LOCAL, null);
    }

    private Profile createProfile(UUID userId) {
        return new Profile(UUID.randomUUID(), userId, "vitin", "Vitor Silva", "Engenheiro de Software", "https://cdn.rewit.com/avatars/vitin.png");
    }

    @Test
    @DisplayName("1. Rejeita targetUserId nulo com 400 Bad Request")
    void shouldRejectNullTargetUserId() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                userService.getPublicProfile(null, requesterUserId));

        assertEquals("MISSING_USER_ID", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("2. Retorna 404 USER_NOT_FOUND para usuário inexistente")
    void shouldReturn404WhenUserNotFound() {
        when(userRepository.findById(targetUserId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                userService.getPublicProfile(targetUserId, requesterUserId));

        assertEquals("USER_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("3. Retorna 404 USER_NOT_FOUND para usuário inativo")
    void shouldReturn404WhenUserIsInactive() {
        User user = User.rehydrate(
                targetUserId,
                "target@example.com",
                "hash",
                AuthProvider.LOCAL,
                null,
                false,
                false,
                null,
                Instant.now(),
                Instant.now()
        );
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(user));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                userService.getPublicProfile(targetUserId, requesterUserId));

        assertEquals("USER_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("4. Retorna 404 USER_NOT_FOUND para usuário com soft delete")
    void shouldReturn404WhenUserIsSoftDeleted() {
        User user = createActiveUser(targetUserId);
        user.softDelete();
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(user));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                userService.getPublicProfile(targetUserId, requesterUserId));

        assertEquals("USER_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("5. Retorna 404 USER_NOT_FOUND quando perfil não for encontrado")
    void shouldReturn404WhenProfileNotFound() {
        User user = createActiveUser(targetUserId);
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(user));
        when(profileRepository.findByUserId(targetUserId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                userService.getPublicProfile(targetUserId, requesterUserId));

        assertEquals("USER_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("6. Retorna perfil público com dados corretos e estatísticas factuais completas")
    void shouldReturnPublicProfileWithFactualStats() {
        User user = createActiveUser(targetUserId);
        Profile profile = createProfile(targetUserId);

        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(user));
        when(profileRepository.findByUserId(targetUserId)).thenReturn(Optional.of(profile));

        when(reviewRepository.countActiveByUserId(targetUserId)).thenReturn(42L);
        when(reviewRepository.countActiveVerifiedByUserId(targetUserId)).thenReturn(18L);
        when(userFollowRepository.countFollowers(targetUserId)).thenReturn(17L);
        when(userFollowRepository.countFollowing(targetUserId)).thenReturn(23L);
        when(reviewReactionRepository.countHelpfulVotesReceivedByUserId(targetUserId)).thenReturn(91L);
        when(userFollowRepository.isFollowing(requesterUserId, targetUserId)).thenReturn(true);

        PublicUserProfileView result = userService.getPublicProfile(targetUserId, requesterUserId);

        assertNotNull(result);
        assertEquals(targetUserId, result.id());
        assertEquals("vitin", result.handle());
        assertEquals("Vitor Silva", result.displayName());
        assertEquals("Engenheiro de Software", result.bio());
        assertEquals("https://cdn.rewit.com/avatars/vitin.png", result.avatarUrl());
        assertTrue(result.isFollowing());

        assertNotNull(result.stats());
        assertEquals(42L, result.stats().totalReviews());
        assertEquals(18L, result.stats().verifiedReviewsCount());
        assertEquals(17L, result.stats().followersCount());
        assertEquals(23L, result.stats().followingCount());
        assertEquals(91L, result.stats().helpfulVotesReceived());
    }

    @Test
    @DisplayName("7. Retorna contadores zerados para usuário sem publicações e sem interações sociais")
    void shouldReturnZeroCountersWhenUserHasNoActivity() {
        User user = createActiveUser(targetUserId);
        Profile profile = createProfile(targetUserId);

        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(user));
        when(profileRepository.findByUserId(targetUserId)).thenReturn(Optional.of(profile));

        when(reviewRepository.countActiveByUserId(targetUserId)).thenReturn(0L);
        when(reviewRepository.countActiveVerifiedByUserId(targetUserId)).thenReturn(0L);
        when(userFollowRepository.countFollowers(targetUserId)).thenReturn(0L);
        when(userFollowRepository.countFollowing(targetUserId)).thenReturn(0L);
        when(reviewReactionRepository.countHelpfulVotesReceivedByUserId(targetUserId)).thenReturn(0L);
        when(userFollowRepository.isFollowing(requesterUserId, targetUserId)).thenReturn(false);

        PublicUserProfileView result = userService.getPublicProfile(targetUserId, requesterUserId);

        assertEquals(0L, result.stats().totalReviews());
        assertEquals(0L, result.stats().verifiedReviewsCount());
        assertEquals(0L, result.stats().followersCount());
        assertEquals(0L, result.stats().followingCount());
        assertEquals(0L, result.stats().helpfulVotesReceived());
        assertFalse(result.isFollowing());
    }

    @Test
    @DisplayName("8. isFollowing é false quando o requester consulta o próprio perfil sem checar repositório")
    void shouldReturnFalseForIsFollowingWhenConsultingOwnProfile() {
        User user = createActiveUser(targetUserId);
        Profile profile = createProfile(targetUserId);

        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(user));
        when(profileRepository.findByUserId(targetUserId)).thenReturn(Optional.of(profile));

        // targetUserId == requesterUserId
        PublicUserProfileView result = userService.getPublicProfile(targetUserId, targetUserId);

        assertFalse(result.isFollowing());
        verify(userFollowRepository, never()).isFollowing(any(), any());
    }

    @Test
    @DisplayName("9. isFollowing é false quando requesterUserId for nulo sem checar repositório")
    void shouldReturnFalseForIsFollowingWhenRequesterIsNull() {
        User user = createActiveUser(targetUserId);
        Profile profile = createProfile(targetUserId);

        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(user));
        when(profileRepository.findByUserId(targetUserId)).thenReturn(Optional.of(profile));

        PublicUserProfileView result = userService.getPublicProfile(targetUserId, null);

        assertFalse(result.isFollowing());
        verify(userFollowRepository, never()).isFollowing(any(), any());
    }
}
