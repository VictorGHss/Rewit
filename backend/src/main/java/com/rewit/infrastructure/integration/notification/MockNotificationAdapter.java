package com.rewit.infrastructure.integration.notification;

import com.rewit.application.port.NotificationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Adaptador agnóstico de notificações push (mock para ambiente de desenvolvimento local).
 */
@Component
public class MockNotificationAdapter implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(MockNotificationAdapter.class);

    @Override
    public void sendPushNotification(UUID recipientUserId, String title, String body, Map<String, String> metadata) {
        // Sem destinatário/título/corpo no log (Step 27.4, Partes F/V): dados do usuário
        // e conteúdo da notificação não podem vazar para observabilidade.
        log.info("[MOCK PUSH] Push simulado entregue pelo provider mock (destinatário e conteúdo não registrados)");
    }
}
