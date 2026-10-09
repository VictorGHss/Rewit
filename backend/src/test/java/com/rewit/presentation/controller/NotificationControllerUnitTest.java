package com.rewit.presentation.controller;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.notification.NotificationDtos.NotificationView;
import com.rewit.application.dto.notification.NotificationDtos.UnreadCountView;
import com.rewit.application.service.NotificationService;
import com.rewit.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: NotificationController (Step 22.0, C5.12)")
class NotificationControllerUnitTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private NotificationController notificationController;

    private MockMvc mockMvc;

    private UUID userId;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(notificationController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        userId = UUID.randomUUID();
        authentication = new UsernamePasswordAuthenticationToken(userId.toString(), "password");
    }

    @Test
    @DisplayName("Deve listar notificações mapeando reviewId e discussionId corretamente")
    void shouldListNotificationsWithContextualFields() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID reviewId1 = UUID.randomUUID();
        UUID reviewId2 = UUID.randomUUID();
        UUID discussionId2 = UUID.randomUUID();
        UUID discussionId3 = UUID.randomUUID();
        UUID reviewId3 = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-09T10:00:00Z");

        List<NotificationView> items = List.of(
                // 0: NEW_FOLLOWER - sem reviewId, sem discussionId
                new NotificationView(
                        UUID.randomUUID(), "NEW_FOLLOWER", actorId, actorId,
                        null, null, null, null, now
                ),
                // 1: REVIEW_HELPFUL - com reviewId, sem discussionId
                new NotificationView(
                        UUID.randomUUID(), "REVIEW_HELPFUL", actorId, reviewId1,
                        reviewId1, null, null, null, now
                ),
                // 2: NEW_DISCUSSION - com reviewId e discussionId (raiz)
                new NotificationView(
                        UUID.randomUUID(), "NEW_DISCUSSION", actorId, reviewId2,
                        reviewId2, discussionId2, null, null, now
                ),
                // 3: DISCUSSION_REPLY - com reviewId e discussionId (resposta)
                new NotificationView(
                        UUID.randomUUID(), "DISCUSSION_REPLY", actorId, discussionId3,
                        reviewId3, discussionId3, null, null, now
                )
        );

        PageResult<NotificationView> pageResult = new PageResult<>(items, 0, 10, 4, 1, true);
        when(notificationService.findMyNotifications(userId, 0, 10)).thenReturn(pageResult);

        mockMvc.perform(get("/api/v1/me/notifications")
                        .principal(authentication)
                        .param("page", "0")
                        .param("size", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(4))
                // NEW_FOLLOWER
                .andExpect(jsonPath("$.content[0].type").value("NEW_FOLLOWER"))
                .andExpect(jsonPath("$.content[0].actorId").value(actorId.toString()))
                .andExpect(jsonPath("$.content[0].reviewId").doesNotExist())
                .andExpect(jsonPath("$.content[0].discussionId").doesNotExist())
                // REVIEW_HELPFUL
                .andExpect(jsonPath("$.content[1].type").value("REVIEW_HELPFUL"))
                .andExpect(jsonPath("$.content[1].reviewId").value(reviewId1.toString()))
                .andExpect(jsonPath("$.content[1].discussionId").doesNotExist())
                // NEW_DISCUSSION
                .andExpect(jsonPath("$.content[2].type").value("NEW_DISCUSSION"))
                .andExpect(jsonPath("$.content[2].reviewId").value(reviewId2.toString()))
                .andExpect(jsonPath("$.content[2].discussionId").value(discussionId2.toString()))
                // DISCUSSION_REPLY
                .andExpect(jsonPath("$.content[3].type").value("DISCUSSION_REPLY"))
                .andExpect(jsonPath("$.content[3].reviewId").value(reviewId3.toString()))
                .andExpect(jsonPath("$.content[3].discussionId").value(discussionId3.toString()));
    }

    @Test
    @DisplayName("Deve retornar contagem de não lidas")
    void shouldReturnUnreadCount() throws Exception {
        when(notificationService.countUnread(userId)).thenReturn(new UnreadCountView(5L));

        mockMvc.perform(get("/api/v1/me/notifications/unread-count")
                        .principal(authentication)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(5));
    }

    @Test
    @DisplayName("Deve marcar notificação como lida")
    void shouldMarkAsRead() throws Exception {
        UUID notifId = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/me/notifications/{id}/read", notifId)
                        .principal(authentication))
                .andExpect(status().isNoContent());

        verify(notificationService).markAsRead(notifId, userId);
    }

    @Test
    @DisplayName("Deve marcar todas as notificações como lidas")
    void shouldMarkAllAsRead() throws Exception {
        mockMvc.perform(patch("/api/v1/me/notifications/read-all")
                        .principal(authentication))
                .andExpect(status().isNoContent());

        verify(notificationService).markAllAsRead(userId);
    }
}
