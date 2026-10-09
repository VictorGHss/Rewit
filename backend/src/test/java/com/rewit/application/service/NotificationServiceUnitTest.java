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
    private final UUID rootId = UUID.randomUUID();

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
            notificationService.notifyDiscussionReply(reviewId, userB, userA, discussionId, rootId, false);

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
            notificationService.notifyDiscussionReply(reviewId, userB, userB, discussionId, rootId, false);
            verify(notificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("12. Resposta de autor em Review anônima deve mascarar actorId (actorId = null)")
        void anonymousOwnerReplyShouldMaskActorId() {
            notificationService.notifyDiscussionReply(reviewId, userB, userA, discussionId, rootId, true);

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
            notificationService.notifyDiscussionReply(reviewId, userB, userA, discussionId, rootId, false);

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
            notificationService.notifyDiscussionReply(reviewId, userB, null, discussionId, rootId, false);

            verify(outboxRepository, never()).save(any());
            verify(notificationRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Contexto de navegação: reviewId e discussionId na listagem (C5.12)")
    class NavigationContext {

        private final UUID replyId = UUID.randomUUID();

        /** Notificação gravada pelo método de criação real, para projetar o metadata que a produção grava. */
        private Notification saved() {
            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(captor.capture());
            return captor.getValue();
        }

        private NotificationView listed(Notification notification) {
            when(notificationRepository.findByUserId(userA, 0, 20))
                    .thenReturn(PageResult.of(List.of(notification), 0, 20, 1));
            return notificationService.findMyNotifications(userA, 0, 20).content().get(0);
        }

        private NotificationView listedWithMetadata(String type, String metadataJson) {
            return listed(new Notification(UUID.randomUUID(), userA, type, "Título", "Conteúdo", "/url", metadataJson,
                    null, Instant.now()));
        }

        private void assertNoUserUuid(NotificationView view, UUID user) {
            assertNotEquals(user, view.actorId());
            assertNotEquals(user, view.referenceId());
            assertNotEquals(user, view.reviewId());
            assertNotEquals(user, view.discussionId());
            assertNotEquals(user, view.rootDiscussionId());
        }

        @Test
        @DisplayName("REVIEW_HELPFUL: referenceId e reviewId são a avaliação; sem discussionId; votante anônimo")
        void reviewHelpful() {
            notificationService.notifyReviewHelpful(reviewId, userA);
            NotificationView view = listed(saved());

            assertEquals(reviewId, view.referenceId());
            assertEquals(reviewId, view.reviewId());
            assertNull(view.discussionId());
            assertNull(view.rootDiscussionId());
            assertNull(view.actorId());
        }

        @Test
        @DisplayName("NEW_DISCUSSION: referenceId e reviewId são a avaliação; discussionId é o comentário raiz")
        void newDiscussion() {
            notificationService.notifyNewDiscussion(reviewId, userA, userB, discussionId);
            NotificationView view = listed(saved());

            assertEquals(reviewId, view.referenceId());
            assertEquals(reviewId, view.reviewId());
            assertEquals(discussionId, view.discussionId());
            assertNull(view.rootDiscussionId(), "o próprio comentário já é a raiz");
            assertEquals(userB, view.actorId());
        }

        @Test
        @DisplayName("DISCUSSION_REPLY: referenceId e discussionId são a resposta; reviewId vem do metadata")
        void discussionReply() {
            notificationService.notifyDiscussionReply(reviewId, userA, userB, replyId, discussionId, false);
            NotificationView view = listed(saved());

            assertEquals(replyId, view.referenceId());
            assertEquals(reviewId, view.reviewId());
            assertEquals(replyId, view.discussionId());
            assertEquals(discussionId, view.rootDiscussionId(), "raiz da thread, distinta da resposta");
            assertEquals(userB, view.actorId());
        }

        @Test
        @DisplayName("DISCUSSION_REPLY com ator mascarado: actorId continua null e nenhum campo novo traz o ator")
        void maskedReplyKeepsActorHidden() {
            notificationService.notifyDiscussionReply(reviewId, userA, userB, replyId, discussionId, true);
            NotificationView view = listed(saved());

            assertNull(view.actorId());
            assertEquals(reviewId, view.reviewId());
            assertEquals(replyId, view.discussionId());
            assertEquals(discussionId, view.rootDiscussionId());
            assertNoUserUuid(view, userB);
        }

        @Test
        @DisplayName("NEW_FOLLOWER: referenceId é o seguidor; reviewId e discussionId nulos")
        void newFollower() {
            notificationService.notifyNewFollower(userB, userA);
            NotificationView view = listed(saved());

            assertEquals(userB, view.referenceId());
            assertNull(view.reviewId());
            assertNull(view.discussionId());
            assertNull(view.rootDiscussionId());
        }

        @Test
        @DisplayName("NEW_FOLLOWER com metadata legado contendo reviewId/discussionId: campos novos continuam nulos")
        void newFollowerIgnoresContextKeys() {
            NotificationView view = listedWithMetadata("NEW_FOLLOWER", "{\"actorId\":\"" + userB + "\",\"referenceId\":\""
                    + userB + "\",\"reviewId\":\"" + userB + "\",\"discussionId\":\"" + userB
                    + "\",\"rootDiscussionId\":\"" + userB + "\"}");

            assertNull(view.reviewId());
            assertNull(view.discussionId());
            assertNull(view.rootDiscussionId());
        }

        @Test
        @DisplayName("Ator DELETED em NEW_DISCUSSION: actorId mascarado; avaliação e comentário preservados")
        void deletedActorStaysMaskedWithContext() {
            notificationService.notifyNewDiscussion(reviewId, userA, userB, discussionId);
            Notification notification = saved();
            when(userRepository.findDeletedUserIds(any())).thenReturn(java.util.Set.of(userB));

            NotificationView view = listed(notification);

            assertNull(view.actorId());
            assertEquals(reviewId, view.reviewId());
            assertEquals(discussionId, view.discussionId());
            assertNoUserUuid(view, userB);
        }

        @Test
        @DisplayName("Seguidor DELETED em NEW_FOLLOWER: actorId e referenceId nulos; nenhum campo novo o revela")
        void deletedFollowerStaysHidden() {
            notificationService.notifyNewFollower(userB, userA);
            Notification notification = saved();
            when(userRepository.findDeletedUserIds(any())).thenReturn(java.util.Set.of(userB));

            NotificationView view = listed(notification);

            assertNull(view.actorId());
            assertNull(view.referenceId());
            assertNoUserUuid(view, userB);
        }

        @Test
        @DisplayName("Página com ator DELETED e notificação sem ator: a listagem não falha (Set imutável e campos nulos)")
        void deletedActorAlongsideNotificationsWithoutActor() {
            Notification fromDeletedFollower = new Notification(UUID.randomUUID(), userA, "NEW_FOLLOWER", "Novo", "Conteúdo",
                    "/url", "{\"actorId\":\"" + userB + "\",\"referenceId\":\"" + userB + "\"}", null, Instant.now());
            Notification helpful = new Notification(UUID.randomUUID(), userA, "REVIEW_HELPFUL", "Útil", "Conteúdo",
                    "/url", "{\"actorId\":null,\"referenceId\":\"" + reviewId + "\"}", null, Instant.now());
            when(notificationRepository.findByUserId(userA, 0, 20))
                    .thenReturn(PageResult.of(List.of(fromDeletedFollower, helpful), 0, 20, 2));
            // Como o adapter real: Set.copyOf, imutável
            when(userRepository.findDeletedUserIds(any())).thenReturn(java.util.Set.copyOf(List.of(userB)));

            List<NotificationView> views = notificationService.findMyNotifications(userA, 0, 20).content();

            assertNull(views.get(0).actorId());
            assertNull(views.get(0).referenceId());
            assertNull(views.get(1).actorId());
            assertEquals(reviewId, views.get(1).reviewId());
        }

        @Test
        @DisplayName("Metadata ausente, vazio, malformado ou não-objeto: listagem não falha e o contexto é nulo")
        void missingOrMalformedMetadata() {
            for (String metadata : java.util.Arrays.asList(null, "", "   ", "{}", "{nao-e-json", "[]", "\"texto\"")) {
                org.mockito.Mockito.reset(notificationRepository);
                NotificationView view = listedWithMetadata("NEW_DISCUSSION", metadata);

                assertNull(view.actorId(), String.valueOf(metadata));
                assertNull(view.referenceId(), String.valueOf(metadata));
                assertNull(view.reviewId(), String.valueOf(metadata));
                assertNull(view.discussionId(), String.valueOf(metadata));
                assertNull(view.rootDiscussionId(), String.valueOf(metadata));
            }
        }

        @Test
        @DisplayName("UUID inválido em campo novo anula só esse campo; actorId e referenceId seguem como antes")
        void invalidContextUuid() {
            NotificationView newDiscussion = listedWithMetadata("NEW_DISCUSSION", "{\"actorId\":\"" + userB
                    + "\",\"referenceId\":\"" + reviewId + "\",\"discussionId\":\"nao-e-uuid\"}");
            assertEquals(userB, newDiscussion.actorId());
            assertEquals(reviewId, newDiscussion.referenceId());
            assertEquals(reviewId, newDiscussion.reviewId());
            assertNull(newDiscussion.discussionId());

            org.mockito.Mockito.reset(notificationRepository);
            NotificationView reply = listedWithMetadata("DISCUSSION_REPLY", "{\"actorId\":\"" + userB
                    + "\",\"referenceId\":\"" + replyId + "\",\"reviewId\":42}");
            assertEquals(userB, reply.actorId());
            assertEquals(replyId, reply.discussionId());
            assertNull(reply.reviewId());
        }

        @Test
        @DisplayName("UUID inválido em actorId/referenceId: mesmo comportamento de antes (ambos nulos), sem falhar")
        void invalidLegacyUuidKeepsPreviousBehavior() {
            NotificationView view = listedWithMetadata("DISCUSSION_REPLY", "{\"actorId\":\"invalido\",\"referenceId\":\""
                    + replyId + "\",\"reviewId\":\"" + reviewId + "\"}");

            assertNull(view.actorId());
            assertNull(view.referenceId());
            assertNull(view.discussionId(), "derivado de referenceId");
            assertEquals(reviewId, view.reviewId());
        }

        @Test
        @DisplayName("Metadata parcial: DISCUSSION_REPLY sem reviewId e NEW_DISCUSSION sem discussionId")
        void partialMetadata() {
            NotificationView reply = listedWithMetadata("DISCUSSION_REPLY",
                    "{\"actorId\":null,\"referenceId\":\"" + replyId + "\"}");
            assertNull(reply.reviewId());
            assertEquals(replyId, reply.discussionId());

            org.mockito.Mockito.reset(notificationRepository);
            NotificationView discussion = listedWithMetadata("NEW_DISCUSSION",
                    "{\"actorId\":\"" + userB + "\",\"referenceId\":\"" + reviewId + "\"}");
            assertEquals(reviewId, discussion.reviewId());
            assertNull(discussion.discussionId());
        }

        @Test
        @DisplayName("Tipo desconhecido (legado): sem contexto, mesmo com chaves no metadata")
        void unknownTypeHasNoContext() {
            NotificationView view = listedWithMetadata("TIPO_LEGADO", "{\"referenceId\":\"" + reviewId
                    + "\",\"reviewId\":\"" + reviewId + "\",\"discussionId\":\"" + discussionId + "\"}");

            assertEquals(reviewId, view.referenceId());
            assertNull(view.reviewId());
            assertNull(view.discussionId());
            assertNull(view.rootDiscussionId());
        }

        @Test
        @DisplayName("DISCUSSION_REPLY legado (sem rootDiscussionId): listável, raiz nula e demais campos intactos")
        void legacyReplyWithoutRoot() {
            NotificationView view = listedWithMetadata("DISCUSSION_REPLY", "{\"actorId\":\"" + userB
                    + "\",\"referenceId\":\"" + replyId + "\",\"reviewId\":\"" + reviewId + "\"}");

            assertEquals(userB, view.actorId());
            assertEquals(replyId, view.referenceId());
            assertEquals(reviewId, view.reviewId());
            assertEquals(replyId, view.discussionId());
            assertNull(view.rootDiscussionId());
        }

        @Test
        @DisplayName("rootDiscussionId inválido ou nulo anula só a raiz; parcial sem reviewId mantém a raiz")
        void invalidOrPartialRoot() {
            for (String root : List.of("\"nao-e-uuid\"", "42", "null", "{}")) {
                org.mockito.Mockito.reset(notificationRepository);
                NotificationView view = listedWithMetadata("DISCUSSION_REPLY", "{\"actorId\":\"" + userB
                        + "\",\"referenceId\":\"" + replyId + "\",\"reviewId\":\"" + reviewId
                        + "\",\"rootDiscussionId\":" + root + "}");

                assertNull(view.rootDiscussionId(), root);
                assertEquals(userB, view.actorId(), root);
                assertEquals(reviewId, view.reviewId(), root);
                assertEquals(replyId, view.discussionId(), root);
            }

            org.mockito.Mockito.reset(notificationRepository);
            NotificationView partial = listedWithMetadata("DISCUSSION_REPLY", "{\"referenceId\":\"" + replyId
                    + "\",\"rootDiscussionId\":\"" + discussionId + "\"}");
            assertNull(partial.reviewId());
            assertEquals(replyId, partial.discussionId());
            assertEquals(discussionId, partial.rootDiscussionId());
        }

        @Test
        @DisplayName("rootDiscussionId só existe em DISCUSSION_REPLY: em NEW_DISCUSSION a chave é ignorada")
        void rootOnlyForReplies() {
            NotificationView view = listedWithMetadata("NEW_DISCUSSION", "{\"actorId\":\"" + userB
                    + "\",\"referenceId\":\"" + reviewId + "\",\"discussionId\":\"" + discussionId
                    + "\",\"rootDiscussionId\":\"" + rootId + "\"}");

            assertEquals(discussionId, view.discussionId());
            assertNull(view.rootDiscussionId());
        }
    }
}
