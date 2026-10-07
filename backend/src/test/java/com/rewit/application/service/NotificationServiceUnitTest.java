package com.rewit.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.notification.NotificationDtos.NotificationView;
import com.rewit.application.dto.notification.NotificationDtos.UnreadCountView;
import com.rewit.application.port.NotificationRepository;
import com.rewit.application.port.OutboxRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.NotificationType;
import com.rewit.domain.enums.OutboxStatus;
import com.rewit.domain.model.Notification;
import com.rewit.domain.model.OutboxMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários - Subsistema de Notificações In-App (Step 22.0)")
class NotificationServiceUnitTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private AccountStatusPolicy accountStatusPolicy;

    @Mock
    private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private NotificationService notificationService;

    private final UUID userA = UUID.randomUUID();
    private final UUID userB = UUID.randomUUID();
    private final UUID reviewId = UUID.randomUUID();
    private final UUID discussionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(notificationRepository, outboxRepository, objectMapper, accountStatusPolicy,
                userRepository);
    }

    @Nested
    @DisplayName("Cenários de Follow")
    class FollowNotifications {

        @Test
        @DisplayName("1. Follow novo deve gerar notificação NEW_FOLLOWER para o usuário seguido")
        void shouldGenerateNewFollowerNotification() {
            notificationService.notifyNewFollower(userA, userB);

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(captor.capture());

            Notification n = captor.getValue();
            assertEquals(userB, n.getUserId());
            assertEquals(NotificationType.NEW_FOLLOWER.name(), n.getNotificationType());
            assertTrue(n.getMetadataJson().contains(userA.toString()));
            assertTrue(n.getActionUrl().contains(userA.toString()));
        }

        @Test
        @DisplayName("2. Self-follow não deve gerar notificação")
        void selfFollowShouldNotGenerateNotification() {
            notificationService.notifyNewFollower(userA, userA);
            verify(notificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("3. Parâmetros nulos não devem gerar notificação")
        void nullParametersShouldNotGenerateNotification() {
            notificationService.notifyNewFollower(null, userB);
            notificationService.notifyNewFollower(userA, null);
            verify(notificationRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Cenários de Helpful")
    class HelpfulNotifications {

        @Test
        @DisplayName("4. Novo Helpful deve gerar notificação genérica sem expor identidade do votante")
        void shouldGenerateReviewHelpfulNotificationWithoutVoterIdentity() {
            notificationService.notifyReviewHelpful(reviewId, userB);

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(captor.capture());

            Notification n = captor.getValue();
            assertEquals(userB, n.getUserId());
            assertEquals(NotificationType.REVIEW_HELPFUL.name(), n.getNotificationType());
            assertTrue(n.getMetadataJson().contains("\"actorId\":null"));
            assertTrue(n.getMetadataJson().contains(reviewId.toString()));
        }

        @Test
        @DisplayName("5. Parâmetros nulos em Helpful não devem gerar notificação")
        void nullHelpfulParametersShouldNotGenerateNotification() {
            notificationService.notifyReviewHelpful(null, userB);
            notificationService.notifyReviewHelpful(reviewId, null);
            verify(notificationRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Cenários de Discussions e Replies")
    class DiscussionNotifications {

        @Test
        @DisplayName("8. Novo comentário raiz deve gerar NEW_DISCUSSION para o autor da Review")
        void shouldGenerateNewDiscussionNotification() {
            notificationService.notifyNewDiscussion(reviewId, userB, userA, discussionId);

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(captor.capture());

            Notification n = captor.getValue();
            assertEquals(userB, n.getUserId());
            assertEquals(NotificationType.NEW_DISCUSSION.name(), n.getNotificationType());
            assertTrue(n.getMetadataJson().contains(userA.toString()));
            assertTrue(n.getMetadataJson().contains(reviewId.toString()));
        }

        @Test
        @DisplayName("9. Comentário do próprio autor da Review não deve notificar o autor")
        void ownerCommentingOnOwnReviewShouldNotNotify() {
            notificationService.notifyNewDiscussion(reviewId, userB, userB, discussionId);
            verify(notificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("10. Resposta deve gerar DISCUSSION_REPLY para o autor do comentário pai")
        void shouldGenerateDiscussionReplyNotification() {
            notificationService.notifyDiscussionReply(reviewId, userB, userA, discussionId, false);

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(captor.capture());

            Notification n = captor.getValue();
            assertEquals(userB, n.getUserId());
            assertEquals(NotificationType.DISCUSSION_REPLY.name(), n.getNotificationType());
            assertTrue(n.getMetadataJson().contains(userA.toString()));
            assertTrue(n.getMetadataJson().contains(discussionId.toString()));
        }

        @Test
        @DisplayName("11. Resposta ao próprio comentário não deve gerar notificação")
        void replyingToOwnCommentShouldNotNotify() {
            notificationService.notifyDiscussionReply(reviewId, userB, userB, discussionId, false);
            verify(notificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("12. Resposta de autor em Review anônima deve mascarar actorId (actorId = null)")
        void anonymousOwnerReplyShouldMaskActorId() {
            notificationService.notifyDiscussionReply(reviewId, userB, userA, discussionId, true);

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(captor.capture());

            Notification n = captor.getValue();
            assertEquals(userB, n.getUserId());
            assertEquals(NotificationType.DISCUSSION_REPLY.name(), n.getNotificationType());
            assertTrue(n.getMetadataJson().contains("\"actorId\":null"));
            assertFalse(n.getMetadataJson().contains(userA.toString()));
        }
    }

    @Nested
    @DisplayName("Cenários de Consulta, Leitura e IDOR")
    class QueryAndReadNotifications {

        @Test
        @DisplayName("15. Listagem deve validar paginação e retornar apenas notificações do recipient")
        void shouldListMyNotificationsWithCorrectPagination() {
            Notification item = new Notification(
                    UUID.randomUUID(), userA, "NEW_FOLLOWER", "Novo", "Conteudo",
                    "/url", "{\"actorId\":\"" + userB + "\",\"referenceId\":\"" + userB + "\"}",
                    null, Instant.now()
            );

            when(notificationRepository.findByUserId(userA, 0, 20))
                    .thenReturn(PageResult.of(List.of(item), 0, 20, 1));

            PageResult<NotificationView> result = notificationService.findMyNotifications(userA, 0, 20);

            assertNotNull(result);
            assertEquals(1, result.totalElements());
            assertEquals(userB, result.content().get(0).actorId());
            assertEquals(userB, result.content().get(0).referenceId());
            assertEquals("NEW_FOLLOWER", result.content().get(0).type());
        }

        @Test
        @DisplayName("16. Paginação inválida deve lançar BusinessException com HTTP 400")
        void shouldRejectInvalidPagination() {
            BusinessException ex1 = assertThrows(BusinessException.class, () ->
                    notificationService.findMyNotifications(userA, -1, 20));
            assertEquals("INVALID_PAGE", ex1.getErrorCode());
            assertEquals(HttpStatus.BAD_REQUEST, ex1.getStatus());

            BusinessException ex2 = assertThrows(BusinessException.class, () ->
                    notificationService.findMyNotifications(userA, 0, 0));
            assertEquals("INVALID_PAGE_SIZE", ex2.getErrorCode());

            BusinessException ex3 = assertThrows(BusinessException.class, () ->
                    notificationService.findMyNotifications(userA, 0, 51));
            assertEquals("PAGE_SIZE_EXCEEDED", ex3.getErrorCode());
        }

        @Test
        @DisplayName("17. Contagem de não lidas deve delegar ao repositório")
        void shouldCountUnreadNotifications() {
            when(notificationRepository.countUnreadByUserId(userA)).thenReturn(5L);

            UnreadCountView view = notificationService.countUnread(userA);

            assertEquals(5L, view.count());
            verify(notificationRepository).countUnreadByUserId(userA);
        }

        @Test
        @DisplayName("18. Marcar como lida deve atualizar status e ser idempotente")
        void shouldMarkAsReadIdempotently() {
            UUID notifId = UUID.randomUUID();
            Notification unread = new Notification(
                    notifId, userA, "NEW_FOLLOWER", "Novo", "Conteudo",
                    "/url", null, null, Instant.now()
            );

            when(notificationRepository.findByIdAndUserId(notifId, userA)).thenReturn(Optional.of(unread));

            notificationService.markAsRead(notifId, userA);

            assertNotNull(unread.getReadAt());
            verify(notificationRepository).save(unread);

            // Segunda chamada (já lida) deve ser idempotente
            notificationService.markAsRead(notifId, userA);
            verify(notificationRepository, times(1)).save(unread);

            // Leitura não gera efeito externo: nenhum push é enfileirado (Parte X)
            verify(outboxRepository, never()).save(any());
        }

        @Test
        @DisplayName("19. Marcar notificação de outro usuário ou inexistente deve falhar com NOTIFICATION_NOT_FOUND (Anti-IDOR)")
        void shouldPreventIdorWhenMarkingAsRead() {
            UUID notifId = UUID.randomUUID();
            when(notificationRepository.findByIdAndUserId(notifId, userA)).thenReturn(Optional.empty());

            BusinessException ex = assertThrows(BusinessException.class, () ->
                    notificationService.markAsRead(notifId, userA));

            assertEquals("NOTIFICATION_NOT_FOUND", ex.getErrorCode());
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            verify(notificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("20. Marcar todas como lidas deve delegar ao repositório")
        void shouldMarkAllAsRead() {
            notificationService.markAllAsRead(userA);
            verify(notificationRepository).markAllAsReadByUserId(userA);
        }
    }

    @Nested
    @DisplayName("Enfileiramento de PUSH_NOTIFICATION no Outbox (Step 27.3)")
    class PushOutboxEnqueue {

        @Test
        @DisplayName("21. Novo follow enfileira exatamente 1 PUSH_NOTIFICATION com payload mínimo e PENDING")
        void shouldEnqueuePushOnNewFollower() {
            notificationService.notifyNewFollower(userA, userB);

            ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(notificationCaptor.capture());
            ArgumentCaptor<OutboxMessage> outboxCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
            verify(outboxRepository).save(outboxCaptor.capture());

            OutboxMessage message = outboxCaptor.getValue();
            assertEquals("PUSH_NOTIFICATION", message.getMessageType());
            assertEquals(OutboxStatus.PENDING, message.getStatus());
            assertEquals("{\"notificationId\":\"" + notificationCaptor.getValue().getId() + "\"}", message.getPayload());
        }

        @Test
        @DisplayName("22. Helpful enfileira PUSH_NOTIFICATION com payload mínimo")
        void shouldEnqueuePushOnReviewHelpful() {
            notificationService.notifyReviewHelpful(reviewId, userB);

            ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(notificationCaptor.capture());
            ArgumentCaptor<OutboxMessage> outboxCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
            verify(outboxRepository).save(outboxCaptor.capture());

            OutboxMessage message = outboxCaptor.getValue();
            assertEquals("PUSH_NOTIFICATION", message.getMessageType());
            assertEquals("{\"notificationId\":\"" + notificationCaptor.getValue().getId() + "\"}", message.getPayload());
        }

        @Test
        @DisplayName("23. Nova discussion enfileira PUSH_NOTIFICATION com payload mínimo")
        void shouldEnqueuePushOnNewDiscussion() {
            notificationService.notifyNewDiscussion(reviewId, userA, userB, discussionId);

            ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(notificationCaptor.capture());
            ArgumentCaptor<OutboxMessage> outboxCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
            verify(outboxRepository).save(outboxCaptor.capture());

            OutboxMessage message = outboxCaptor.getValue();
            assertEquals("PUSH_NOTIFICATION", message.getMessageType());
            assertEquals("{\"notificationId\":\"" + notificationCaptor.getValue().getId() + "\"}", message.getPayload());
        }

        @Test
        @DisplayName("24. Reply enfileira PUSH_NOTIFICATION com payload mínimo")
        void shouldEnqueuePushOnDiscussionReply() {
            notificationService.notifyDiscussionReply(reviewId, userB, userA, discussionId, false);

            ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(notificationCaptor.capture());
            ArgumentCaptor<OutboxMessage> outboxCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
            verify(outboxRepository).save(outboxCaptor.capture());

            OutboxMessage message = outboxCaptor.getValue();
            assertEquals("PUSH_NOTIFICATION", message.getMessageType());
            assertEquals("{\"notificationId\":\"" + notificationCaptor.getValue().getId() + "\"}", message.getPayload());
        }

        @Test
        @DisplayName("25. Self-follow não enfileira push no outbox")
        void shouldNotEnqueueOnSelfFollow() {
            notificationService.notifyNewFollower(userA, userA);
            verify(outboxRepository, never()).save(any());
        }

        @Test
        @DisplayName("26. Parâmetros nulos não enfileiram push no outbox")
        void shouldNotEnqueueOnNullParameters() {
            notificationService.notifyNewFollower(null, userB);
            notificationService.notifyNewFollower(userA, null);
            notificationService.notifyReviewHelpful(null, userB);
            notificationService.notifyNewDiscussion(reviewId, userA, null, discussionId);
            notificationService.notifyDiscussionReply(reviewId, userB, null, discussionId, false);

            verify(outboxRepository, never()).save(any());
            verify(notificationRepository, never()).save(any());
        }
    }
}
