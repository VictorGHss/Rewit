package com.rewit.presentation.controller;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.social.FollowUserSummaryView;
import com.rewit.application.service.UserFollowService;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.common.PagedResponse;
import com.rewit.presentation.dto.social.FollowStatusResponse;
import com.rewit.presentation.dto.social.FollowUserSummaryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Controlador REST para o subsistema social de conexões entre usuários e seguidores (Step 15.0).
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserFollowController {

    private final UserFollowService userFollowService;

    public UserFollowController(UserFollowService userFollowService) {
        this.userFollowService = userFollowService;
    }

    @PostMapping("/{id}/follow")
    public ResponseEntity<FollowStatusResponse> followUser(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        UUID requesterUserId = extractAuthenticatedUserId(authentication);
        userFollowService.followUser(requesterUserId, id);
        return ResponseEntity.ok(new FollowStatusResponse(true));
    }

    @DeleteMapping("/{id}/follow")
    public ResponseEntity<FollowStatusResponse> unfollowUser(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        UUID requesterUserId = extractAuthenticatedUserId(authentication);
        userFollowService.unfollowUser(requesterUserId, id);
        return ResponseEntity.ok(new FollowStatusResponse(false));
    }

    @GetMapping("/{id}/follow")
    public ResponseEntity<FollowStatusResponse> getFollowStatus(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        UUID requesterUserId = extractAuthenticatedUserId(authentication);
        boolean following = userFollowService.isFollowing(requesterUserId, id);
        return ResponseEntity.ok(new FollowStatusResponse(following));
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return UUID.fromString(authentication.getName());
    }

    @GetMapping("/{id}/following")
    public ResponseEntity<PagedResponse<FollowUserSummaryResponse>> getFollowing(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        PageResult<FollowUserSummaryView> pageResult = userFollowService.getFollowing(id, page, size);
        return ResponseEntity.ok(toPagedResponse(pageResult));
    }

    @GetMapping("/{id}/followers")
    public ResponseEntity<PagedResponse<FollowUserSummaryResponse>> getFollowers(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        PageResult<FollowUserSummaryView> pageResult = userFollowService.getFollowers(id, page, size);
        return ResponseEntity.ok(toPagedResponse(pageResult));
    }

    public static PagedResponse<FollowUserSummaryResponse> toPagedResponse(PageResult<FollowUserSummaryView> pageResult) {
        List<FollowUserSummaryResponse> content = pageResult.content().stream()
                .map(v -> new FollowUserSummaryResponse(
                        v.userId(),
                        v.handle(),
                        v.displayName(),
                        v.avatarUrl(),
                        v.followedAt()
                ))
                .toList();

        return new PagedResponse<>(
                content,
                pageResult.pageNumber(),
                pageResult.pageSize(),
                pageResult.totalElements(),
                pageResult.totalPages(),
                pageResult.isLast()
        );
    }
}
