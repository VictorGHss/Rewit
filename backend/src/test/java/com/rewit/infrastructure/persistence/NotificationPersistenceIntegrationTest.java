package com.rewit.infrastructure.persistence;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.NotificationRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.NotificationType;
import com.rewit.domain.model.Notification;
import com.rewit.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração de Persistência no PostgreSQL Real - Notificações (Step 22.0)")
class NotificationPersistenceIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    private User recipientUser;
    private User otherUser;

    @BeforeEach
    void setUp() {
        recipientUser = createAndPersistUser("notif_recip_" + UUID.randomUUID().toString().substring(0, 8) + "@test.com");
        otherUser = createAndPersistUser("notif_other_" + UUID.randomUUID().toString().substring(0, 8) + "@test.com");
    }

    @Test
    @Transactional
    @DisplayName("Deve persistir e recuperar uma notificação no PostgreSQL real")
    void shouldPersistAndRetrieveNotification() {
        String metadata = "{\"actorId\":\"" + otherUser.getId() + "\",\"referenceId\":\"" + otherUser.getId() + "\"}";
        Notification notification = new Notification(
                null,
                recipientUser.getId(),
                NotificationType.NEW_FOLLOWER.name(),
                "Novo seguidor",
                "Você tem um novo seguidor.",
                "/api/v1/users/" + otherUser.getId(),
                metadata
        );

        Notification saved = notificationRepository.save(notification);
        assertNotNull(saved.getId());

        Optional<Notification> retrieved = notificationRepository.findByIdAndUserId(saved.getId(), recipientUser.getId());
        assertTrue(retrieved.isPresent());
        assertEquals(recipientUser.getId(), retrieved.get().getUserId());
        assertEquals("NEW_FOLLOWER", retrieved.get().getNotificationType());
        assertEquals("Novo seguidor", retrieved.get().getTitle());
        assertEquals(metadata, retrieved.get().getMetadataJson());
        assertNull(retrieved.get().getReadAt());
        assertNotNull(retrieved.get().getCreatedAt());
    }

    @Test
    @Transactional
    @DisplayName("Deve listar notificações ordenadas cronologicamente reversa (created_at DESC, id DESC)")
    void shouldListNotificationsOrderedByCreatedAtDescIdDesc() throws InterruptedException {
        Notification n1 = notificationRepository.save(new Notification(
                null, recipientUser.getId(), NotificationType.NEW_FOLLOWER.name(),
                "Primeira", "Conteudo 1", null, null
        ));

        Thread.sleep(10); // Garante diferença de timestamp no clock

        Notification n2 = notificationRepository.save(new Notification(
                null, recipientUser.getId(), NotificationType.REVIEW_HELPFUL.name(),
                "Segunda", "Conteudo 2", null, null
        ));

        PageResult<Notification> paged = notificationRepository.findByUserId(recipientUser.getId(), 0, 10);
        assertEquals(2, paged.totalElements());
        assertEquals(n2.getId(), paged.content().get(0).getId());
        assertEquals(n1.getId(), paged.content().get(1).getId());
    }

    @Test
    @Transactional
    @DisplayName("Deve contar e marcar todas as notificações como lidas em lote (atômico e idempotente)")
    void shouldCountUnreadAndMarkAllAsRead() {
        notificationRepository.save(new Notification(
                null, recipientUser.getId(), NotificationType.NEW_FOLLOWER.name(),
                "Não lida 1", "Conteudo", null, null
        ));
        notificationRepository.save(new Notification(
                null, recipientUser.getId(), NotificationType.NEW_DISCUSSION.name(),
                "Não lida 2", "Conteudo", null, null
        ));

        // Notificação para outro usuário (não deve ser afetada)
        notificationRepository.save(new Notification(
                null, otherUser.getId(), NotificationType.REVIEW_HELPFUL.name(),
                "Outro user", "Conteudo", null, null
        ));

        long unreadCount = notificationRepository.countUnreadByUserId(recipientUser.getId());
        assertEquals(2, unreadCount);

        // Marca todas como lidas
        int updated = notificationRepository.markAllAsReadByUserId(recipientUser.getId());
        assertEquals(2, updated);

        long unreadAfter = notificationRepository.countUnreadByUserId(recipientUser.getId());
        assertEquals(0, unreadAfter);

        // Notificação do outro usuário continua não lida
        long otherUnread = notificationRepository.countUnreadByUserId(otherUser.getId());
        assertEquals(1, otherUnread);

        // Idempotência: rodar novamente não altera nada
        int updatedAgain = notificationRepository.markAllAsReadByUserId(recipientUser.getId());
        assertEquals(0, updatedAgain);
    }

    @Test
    @Transactional
    @DisplayName("Isolamento por recipient: findByIdAndUserId não deve encontrar notificação pertencente a outro usuário")
    void shouldIsolateNotificationsBetweenUsers() {
        Notification savedForOther = notificationRepository.save(new Notification(
                null, otherUser.getId(), NotificationType.NEW_FOLLOWER.name(),
                "Privada", "Conteudo", null, null
        ));

        Optional<Notification> opt = notificationRepository.findByIdAndUserId(savedForOther.getId(), recipientUser.getId());
        assertFalse(opt.isPresent(), "Usuário não deve acessar notificação de terceiro via findByIdAndUserId");
    }

    private User createAndPersistUser(String email) {
        User user = new User(
                null,
                email,
                "$2a$10$abcdefghijklmnopqrstuvwxyzABCDEF1234567890",
                AuthProvider.LOCAL,
                null
        );
        return userRepository.save(user);
    }
}
