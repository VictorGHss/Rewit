package com.rewit.application.port;

import java.util.Map;
import java.util.UUID;

/**
 * Porta de aplicação para envio de notificações push externas.
 */
public interface NotificationProvider {

    void sendPushNotification(UUID recipientUserId, String title, String body, Map<String, String> metadata);
}
