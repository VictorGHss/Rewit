package com.rewit.application.service;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.social.FollowUserSummaryView;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.UserFollowRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;
import com.rewit.domain.model.UserFollow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Serviço de aplicação para gestão de conexões sociais e seguidores (Step
 * 15.0).
 */
@Service
public class UserFollowService {

    private final UserFollowRepository userFollowRepository;
    private final UserRepository userRepository;
    private final AccountStatusPolicy accountStatusPolicy;
    private final ProfileRepository profileRepository;
    private final NotificationService notificationService;

    @org.springframework.beans.factory.annotation.Autowired
    public UserFollowService(
            UserFollowRepository userFollowRepository,
            UserRepository userRepository,
            AccountStatusPolicy accountStatusPolicy,
            ProfileRepository profileRepository,
            NotificationService notificationService) {
        this.userFollowRepository = userFollowRepository;
        this.userRepository = userRepository;
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
        this.profileRepository = profileRepository;
        this.notificationService = notificationService;
    }

    public UserFollowService(
            UserFollowRepository userFollowRepository,
            UserRepository userRepository,
            AccountStatusPolicy accountStatusPolicy,
            ProfileRepository profileRepository) {
        this(userFollowRepository, userRepository, accountStatusPolicy, profileRepository, null);
    }

    /**
     * Segue outro usuário de forma segura e idempotente.
     */
    @Transactional
    public boolean followUser(UUID followerUserId, UUID targetUserId) {
        if (followerUserId == null) {
            throw new BusinessException("Identificador de seguidor obrigatório", HttpStatus.BAD_REQUEST,
                    "MISSING_USER_ID");
        }
        if (targetUserId == null) {
            throw new BusinessException("Identificador de usuário alvo obrigatório", HttpStatus.BAD_REQUEST,
                    "MISSING_TARGET_USER_ID");
        }
        if (followerUserId.equals(targetUserId)) {
            throw new BusinessException("Um usuário não pode seguir a si mesmo", HttpStatus.BAD_REQUEST,
                    "SELF_FOLLOW_FORBIDDEN");
        }

        accountStatusPolicy.requireOperational(followerUserId);

        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(
                        () -> new BusinessException("Usuário não encontrado", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        if (!targetUser.isActive() || targetUser.getDeletedAt() != null) {
            throw new BusinessException("Usuário não encontrado", HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }

        boolean created = userFollowRepository.follow(followerUserId, targetUserId);
        if (created && notificationService != null) {
            notificationService.notifyNewFollower(followerUserId, targetUserId);
        }
        return created;
    }

    /**
     * Deixa de seguir outro usuário de forma segura e idempotente.
     */
    @Transactional
    public boolean unfollowUser(UUID followerUserId, UUID targetUserId) {
        if (followerUserId == null) {
            throw new BusinessException("Identificador de seguidor obrigatório", HttpStatus.BAD_REQUEST,
                    "MISSING_USER_ID");
        }
        if (targetUserId == null) {
            throw new BusinessException("Identificador de usuário alvo obrigatório", HttpStatus.BAD_REQUEST,
                    "MISSING_TARGET_USER_ID");
        }
        if (followerUserId.equals(targetUserId)) {
            throw new BusinessException("Um usuário não pode deixar de seguir a si mesmo", HttpStatus.BAD_REQUEST,
                    "SELF_FOLLOW_FORBIDDEN");
        }

        accountStatusPolicy.requireOperational(followerUserId);

        userRepository.findById(targetUserId)
                .orElseThrow(
                        () -> new BusinessException("Usuário não encontrado", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        return userFollowRepository.unfollow(followerUserId, targetUserId);
    }

    /**
     * Verifica se o usuário autenticado segue o usuário alvo.
     */
    @Transactional(readOnly = true)
    public boolean isFollowing(UUID followerUserId, UUID targetUserId) {
        if (followerUserId == null) {
            throw new BusinessException("Identificador de seguidor obrigatório", HttpStatus.BAD_REQUEST,
                    "MISSING_USER_ID");
        }
        if (targetUserId == null) {
            throw new BusinessException("Identificador de usuário alvo obrigatório", HttpStatus.BAD_REQUEST,
                    "MISSING_TARGET_USER_ID");
        }
        if (followerUserId.equals(targetUserId)) {
            return false;
        }

        userRepository.findById(targetUserId)
                .orElseThrow(
                        () -> new BusinessException("Usuário não encontrado", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        return userFollowRepository.isFollowing(followerUserId, targetUserId);
    }

    /**
     * Retorna a lista paginada de usuários seguidos (following).
     */
    @Transactional(readOnly = true)
    public PageResult<FollowUserSummaryView> getFollowing(UUID userId, int page, int size) {
        validatePagination(userId, page, size);

        PageResult<UserFollow> pageResult = userFollowRepository.findFollowing(userId, page, size);
        if (pageResult.content().isEmpty()) {
            return PageResult.of(List.of(), page, size, pageResult.totalElements());
        }

        Set<UUID> followedUserIds = pageResult.content().stream()
                .map(f -> f.getFollowedUserId())
                .collect(Collectors.toSet());

        Map<UUID, Profile> profiles = resolveProfiles(followedUserIds);

        List<FollowUserSummaryView> views = pageResult.content().stream()
                .map(f -> {
                    Profile profile = profiles.get(f.getFollowedUserId());
                    return new FollowUserSummaryView(
                            f.getFollowedUserId(),
                            profile != null ? profile.getHandle() : null,
                            profile != null ? profile.getDisplayName() : null,
                            profile != null ? profile.getAvatarUrl() : null,
                            f.getCreatedAt());
                })
                .toList();

        return new PageResult<>(
                views,
                pageResult.pageNumber(),
                pageResult.pageSize(),
                pageResult.totalElements(),
                pageResult.totalPages(),
                pageResult.isLast());
    }

    /**
     * Retorna a lista paginada dos seguidores de um usuário (followers).
     */
    @Transactional(readOnly = true)
    public PageResult<FollowUserSummaryView> getFollowers(UUID userId, int page, int size) {
        validatePagination(userId, page, size);

        PageResult<UserFollow> pageResult = userFollowRepository.findFollowers(userId, page, size);
        if (pageResult.content().isEmpty()) {
            return PageResult.of(List.of(), page, size, pageResult.totalElements());
        }

        Set<UUID> followerUserIds = pageResult.content().stream()
                .map(f -> f.getFollowerUserId())
                .collect(Collectors.toSet());

        Map<UUID, Profile> profiles = resolveProfiles(followerUserIds);

        List<FollowUserSummaryView> views = pageResult.content().stream()
                .map(f -> {
                    Profile profile = profiles.get(f.getFollowerUserId());
                    return new FollowUserSummaryView(
                            f.getFollowerUserId(),
                            profile != null ? profile.getHandle() : null,
                            profile != null ? profile.getDisplayName() : null,
                            profile != null ? profile.getAvatarUrl() : null,
                            f.getCreatedAt());
                })
                .toList();

        return new PageResult<>(
                views,
                pageResult.pageNumber(),
                pageResult.pageSize(),
                pageResult.totalElements(),
                pageResult.totalPages(),
                pageResult.isLast());
    }

    private void validatePagination(UUID userId, int page, int size) {
        if (userId == null) {
            throw new BusinessException("Identificador de usuário obrigatório", HttpStatus.BAD_REQUEST,
                    "MISSING_USER_ID");
        }
        if (page < 0) {
            throw new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST,
                    "INVALID_PAGE");
        }
        if (size <= 0) {
            throw new BusinessException("O tamanho da página deve ser maior que zero", HttpStatus.BAD_REQUEST,
                    "INVALID_SIZE");
        }
        if (size > 50) {
            throw new BusinessException("O tamanho da página não pode ser superior a 50", HttpStatus.BAD_REQUEST,
                    "PAGE_SIZE_EXCEEDED");
        }

        userRepository.findById(userId)
                .orElseThrow(
                        () -> new BusinessException("Usuário não encontrado", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    private Map<UUID, Profile> resolveProfiles(Set<UUID> userIds) {
        if (profileRepository == null || userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return profileRepository.findByUserIdIn(userIds).stream()
                .collect(Collectors.toMap(p -> p.getUserId(), p -> p, (a, b) -> a));
    }
}
