package com.rewit.application.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.NotificationProvider;
import com.rewit.application.port.NotificationRepository;
import com.rewit.application.port.OutboxHandler;
import com.rewit.domain.enums.OutboxMessageType;
import com.rewit.domain.model.Notification;
import com.rewit.domain.model.OutboxMessage;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Handler do Outbox que entrega o efeito externo de push (Step 27.3).
 *
 * <p>Recebe a mensagem já reivindicada pelo dispatcher (FORA de qualquer
 * transação), resolve a Notification a partir do único dado que o payload
 * carrega — o notificationId — e encaminha ao provider exatamente aquilo que
 * a Notification persistida representa: recipient, título, corpo e metadados.
 * O handler não reconstrói conteúdo de negócio, não acessa JPA/controllers,
 * não conhece scheduler e não abre transação própria.
 *
 * <p>Anonimato (PARTE J): o handler apenas repassa os metadados como estão na
 * Notification — onde actorId já é null no REVIEW_HELPFUL e nas respostas
 * mascaradas. Nenhuma identidade é derivada aqui de IDs escondidos.
 *
 * <p>Classificação de falhas (PARTE L, reutilizando o classificador do 27.2):
 * condições irrecuperáveis determinadas pelo próprio handler (payload vazio,
 * malformado ou sem notificationId; Notification inexistente; metadados
 * ilegíveis) lançam {@link OutboxPermanentException}. Exceções do provider
 * propagam sem captura — o classificador decide (não classificadas →
 * transiente com backoff; permanentes do provider → FAILED imediato).
 */
public class PushNotificationHandler implements OutboxHandler {

    private final NotificationRepository notificationRepository;
    private final NotificationProvider notificationProvider;
    private final ObjectMapper objectMapper;

    public PushNotificationHandler(
            NotificationRepository notificationRepository,
            NotificationProvider notificationProvider,
            ObjectMapper objectMapper
    ) {
        this.notificationRepository = Objects.requireNonNull(notificationRepository, "NotificationRepository must not be null");
        this.notificationProvider = Objects.requireNonNull(notificationProvider, "NotificationProvider must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper must not be null");
    }

    @Override
    public boolean supports(String messageType) {
        return OutboxMessageType.PUSH_NOTIFICATION.name().equals(messageType);
    }

    @Override
    public void handle(OutboxMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        UUID notificationId = extractNotificationId(message.getPayload());

        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new OutboxPermanentException(
                        "Notificação inexistente para push (provavelmente removida): " + notificationId));

        notificationProvider.sendPushNotification(
                notification.getUserId(),
                notification.getTitle(),
                notification.getContent(),
                parseMetadata(notification.getMetadataJson()));
    }

    private UUID extractNotificationId(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new OutboxPermanentException("Payload do push está vazio");
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode idNode = node.get("notificationId");
            if (idNode == null || !idNode.isTextual() || idNode.asText().isBlank()) {
                throw new OutboxPermanentException("Payload do push sem notificationId válido");
            }
            return UUID.fromString(idNode.asText());
        } catch (OutboxPermanentException e) {
            throw e;
        } catch (Exception e) {
            throw new OutboxPermanentException("Payload do push malformado", e);
        }
    }

    private Map<String, String> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(metadataJson);
            if (!node.isObject()) {
                throw new OutboxPermanentException("Metadados da notificação não são um objeto JSON");
            }
            Map<String, String> metadata = new LinkedHashMap<>();
            Iterator<String> fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                String field = fieldNames.next();
                JsonNode value = node.get(field);
                metadata.put(field, value == null || value.isNull() ? null : value.asText());
            }
            return metadata;
        } catch (OutboxPermanentException e) {
            throw e;
        } catch (Exception e) {
            throw new OutboxPermanentException("Metadados da notificação malformados", e);
        }
    }
}
