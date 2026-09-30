package com.rewit.presentation.controller;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.notification.NotificationDtos.NotificationView;
import com.rewit.application.dto.notification.NotificationDtos.UnreadCountView;
import com.rewit.application.service.NotificationService;
import com.rewit.presentation.dto.common.PagedResponse;
import com.rewit.presentation.dto.notification.NotificationResponse;
import com.rewit.presentation.dto.notification.UnreadCountResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Controller RESTful para consulta e gestão de notificações in-app do usuário autenticado (/api/v1/me/notifications).
 * A identidade é inferida exclusivamente a partir do token JWT do contexto de segurança, prevenindo IDOR.
 */
@RestController
@RequestMapping("/api/v1/me/notifications")
@Tag(name = "Notificações", description = "Endpoints para gerenciamento e leitura de notificações in-app do usuário")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = Objects.requireNonNull(notificationService, "NotificationService must not be null");
    }

    @GetMapping
    @Operation(summary = "Listar notificações do usuário autenticado", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PagedResponse<NotificationResponse>> getMyNotifications(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication
    ) {
        UUID authenticatedUserId = extractAuthenticatedUserId(authentication);
        PageResult<NotificationView> pageResult = notificationService.findMyNotifications(authenticatedUserId, page, size);

        List<NotificationResponse> content = pageResult.content().stream()
                .map(NotificationResponse::fromView)
                .toList();

        return ResponseEntity.ok(new PagedResponse<>(
                content,
                pageResult.pageNumber(),
                pageResult.pageSize(),
                pageResult.totalElements(),
                pageResult.totalPages(),
                pageResult.isLast()
        ));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Obter quantidade de notificações não lidas do usuário autenticado", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<UnreadCountResponse> getUnreadCount(Authentication authentication) {
        UUID authenticatedUserId = extractAuthenticatedUserId(authentication);
        UnreadCountView view = notificationService.countUnread(authenticatedUserId);
        return ResponseEntity.ok(new UnreadCountResponse(view.count()));
    }

    @PatchMapping("/{notificationId}/read")
    @Operation(summary = "Marcar uma notificação específica como lida", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> markAsRead(
            @PathVariable("notificationId") UUID notificationId,
            Authentication authentication
    ) {
        UUID authenticatedUserId = extractAuthenticatedUserId(authentication);
        notificationService.markAsRead(notificationId, authenticatedUserId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/read-all")
    @Operation(summary = "Marcar todas as notificações do usuário autenticado como lidas", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> markAllAsRead(Authentication authentication) {
        UUID authenticatedUserId = extractAuthenticatedUserId(authentication);
        notificationService.markAllAsRead(authenticatedUserId);
        return ResponseEntity.noContent().build();
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new com.rewit.common.exception.BusinessException(
                    "Usuário não autenticado",
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "UNAUTHORIZED"
            );
        }
        return UUID.fromString(authentication.getName());
    }
}
