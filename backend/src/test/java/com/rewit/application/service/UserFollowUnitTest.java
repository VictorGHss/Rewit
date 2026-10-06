package com.rewit.application.service;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.social.FollowUserSummaryView;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.UserFollowRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;
import com.rewit.domain.model.UserFollow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: UserFollowService e Regras de Conexões Sociais (Step 15.0)")
class UserFollowUnitTest {

    @Mock
    private AccountStatusPolicy accountStatusPolicy;

    @Mock
    private UserFollowRepository userFollowRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProfileRepository profileRepository;

    private UserFollowService userFollowService;

    private UUID followerUserId;
    private UUID targetUserId;
    private User targetUser;

    @BeforeEach
    void setUp() {
        userFollowService = new UserFollowService(userFollowRepository, userRepository, accountStatusPolicy, profileRepository);
        followerUserId = UUID.randomUUID();
        targetUserId = UUID.randomUUID();
        targetUser = new User(targetUserId, "target@rewit.com", "hash", AuthProvider.LOCAL, null);
    }

    @Test
    @DisplayName("1. Follow bem-sucedido estabelece relacionamento")
    void shouldFollowUserSuccessfully() {
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
        when(userFollowRepository.follow(followerUserId, targetUserId)).thenReturn(true);

        boolean followed = userFollowService.followUser(followerUserId, targetUserId);

        assertTrue(followed);
        verify(userFollowRepository).follow(followerUserId, targetUserId);
    }

    @Test
    @DisplayName("2. Follow é idempotente quando usuário já é seguido")
    void shouldBeIdempotentWhenAlreadyFollowing() {
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
        when(userFollowRepository.follow(followerUserId, targetUserId)).thenReturn(false);

        boolean followed = userFollowService.followUser(followerUserId, targetUserId);

        assertFalse(followed);
        verify(userFollowRepository).follow(followerUserId, targetUserId);
    }

    @Test
    @DisplayName("3. Auto-seguir é bloqueado com SELF_FOLLOW_FORBIDDEN")
    void shouldRejectSelfFollow() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                userFollowService.followUser(followerUserId, followerUserId)
        );

        assertEquals("SELF_FOLLOW_FORBIDDEN", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verifyNoInteractions(userFollowRepository);
    }

    @Test
    @DisplayName("4. Seguir usuário inexistente lança 404 USER_NOT_FOUND")
    void shouldThrow404WhenTargetUserNotFound() {
        when(userRepository.findById(targetUserId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                userFollowService.followUser(followerUserId, targetUserId)
        );

        assertEquals("USER_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verifyNoInteractions(userFollowRepository);
    }

    @Test
    @DisplayName("5. Unfollow bem-sucedido remove relacionamento")
    void shouldUnfollowSuccessfully() {
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
        when(userFollowRepository.unfollow(followerUserId, targetUserId)).thenReturn(true);

        boolean unfollowed = userFollowService.unfollowUser(followerUserId, targetUserId);

        assertTrue(unfollowed);
        verify(userFollowRepository).unfollow(followerUserId, targetUserId);
    }

    @Test
    @DisplayName("6. Unfollow é idempotente quando não segue o usuário")
    void shouldBeIdempotentWhenUnfollowingNonFollowedUser() {
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
        when(userFollowRepository.unfollow(followerUserId, targetUserId)).thenReturn(false);

        boolean unfollowed = userFollowService.unfollowUser(followerUserId, targetUserId);

        assertFalse(unfollowed);
        verify(userFollowRepository).unfollow(followerUserId, targetUserId);
    }

    @Test
    @DisplayName("7. Consulta isFollowing retorna estado do repositório")
    void shouldReturnIsFollowingStatus() {
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
        when(userFollowRepository.isFollowing(followerUserId, targetUserId)).thenReturn(true);

        boolean following = userFollowService.isFollowing(followerUserId, targetUserId);

        assertTrue(following);
    }

    @Test
    @DisplayName("8. Consulta isFollowing para mesmo usuário retorna false")
    void shouldReturnFalseForSelfFollowingCheck() {
        boolean following = userFollowService.isFollowing(followerUserId, followerUserId);

        assertFalse(following);
        verifyNoInteractions(userRepository);
        verifyNoInteractions(userFollowRepository);
    }

    @Test
    @DisplayName("9. Listagem de following resolve perfis em lote sem N+1")
    void shouldListFollowingWithBatchProfileResolution() {
        when(userRepository.findById(followerUserId)).thenReturn(Optional.of(new User(followerUserId, "me@rewit.com", "hash", AuthProvider.LOCAL, null)));

        UUID followedId1 = UUID.randomUUID();
        UUID followedId2 = UUID.randomUUID();
        UserFollow f1 = new UserFollow(UUID.randomUUID(), followerUserId, followedId1, Instant.now());
        UserFollow f2 = new UserFollow(UUID.randomUUID(), followerUserId, followedId2, Instant.now());

        when(userFollowRepository.findFollowing(eq(followerUserId), eq(0), eq(10)))
                .thenReturn(PageResult.of(List.of(f1, f2), 0, 10, 2));

        Profile p1 = new Profile(UUID.randomUUID(), followedId1, "user_one", "User One", "Bio 1", null);
        Profile p2 = new Profile(UUID.randomUUID(), followedId2, "user_two", "User Two", "Bio 2", null);
        when(profileRepository.findByUserIdIn(Set.of(followedId1, followedId2)))
                .thenReturn(List.of(p1, p2));

        PageResult<FollowUserSummaryView> result = userFollowService.getFollowing(followerUserId, 0, 10);

        assertEquals(2, result.content().size());
        assertEquals("user_one", result.content().get(0).handle());
        assertEquals("User One", result.content().get(0).displayName());
        assertEquals("user_two", result.content().get(1).handle());
        assertEquals("User Two", result.content().get(1).displayName());
    }

    @Test
    @DisplayName("10. Listagem de followers com página vazia")
    void shouldReturnEmptyPageWhenNoFollowers() {
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
        when(userFollowRepository.findFollowers(eq(targetUserId), eq(0), eq(10)))
                .thenReturn(PageResult.of(List.of(), 0, 10, 0));

        PageResult<FollowUserSummaryView> result = userFollowService.getFollowers(targetUserId, 0, 10);

        assertTrue(result.content().isEmpty());
        assertEquals(0, result.totalElements());
        verifyNoInteractions(profileRepository);
    }

    @Test
    @DisplayName("11. Rejeita parâmetros de paginação inválidos")
    void shouldRejectInvalidPaginationParameters() {
        BusinessException exPage = assertThrows(BusinessException.class, () ->
                userFollowService.getFollowing(followerUserId, -1, 10)
        );
        assertEquals("INVALID_PAGE", exPage.getErrorCode());

        BusinessException exSizeZero = assertThrows(BusinessException.class, () ->
                userFollowService.getFollowing(followerUserId, 0, 0)
        );
        assertEquals("INVALID_SIZE", exSizeZero.getErrorCode());

        BusinessException exSizeExcessive = assertThrows(BusinessException.class, () ->
                userFollowService.getFollowing(followerUserId, 0, 51)
        );
        assertEquals("PAGE_SIZE_EXCEEDED", exSizeExcessive.getErrorCode());
    }
}
