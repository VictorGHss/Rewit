package com.rewit.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.notification.NotificationDtos.NotificationView;
import com.rewit.application.dto.notification.NotificationDtos.UnreadCountView;
import com.rewit.application.port.NotificationRepository;
import com.rewit.application.port.OutboxRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.NotificationType;
import com.rewit.domain.enums.OutboxMessageType;
import com.rewit.domain.model.Notification;
import com.rewit.domain.model.OutboxMessage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Serviço de aplicação para gerenciamento e disparo de notificações internas in-app (Step 22.0).
 */
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final AccountStatusPolicy accountStatusPolicy;
    private final UserRepository userRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public NotificationService(NotificationRepository notificationRepository, OutboxRepository outboxRepository,
                               AccountStatusPolicy accountStatusPolicy, UserRepository userRepository) {
        this(notificationRepository, outboxRepository, new ObjectMapper(), accountStatusPolicy, userRepository);
    }

    public NotificationService(
            NotificationRepository notificationRepository,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            AccountStatusPolicy accountStatusPolicy,
            UserRepository userRepository
    ) {
        this.notificationRepository = Objects.requireNonNull(notificationRepository, "NotificationRepository must not be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "OutboxRepository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper must not be null");
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository must not be null");
    }

    /**
     * Dispara notificação de novo seguidor (NEW_FOLLOWER).
     */
    @Transactional
    public void notifyNewFollower(UUID followerUserId, UUID targetUserId) {
        if (followerUserId == null || targetUserId == null || followerUserId.equals(targetUserId)) {
            return;
        }

        String metadataJson = buildMetadataJson(followerUserId, followerUserId, null);
        Notification notification = new Notification(
                null,
                targetUserId,
                NotificationType.NEW_FOLLOWER.name(),
                "Novo seguidor",
                "Você tem um novo seguidor.",
                "/api/v1/users/" + followerUserId,
                metadataJson
        );

        notificationRepository.save(notification);
        enqueuePush(notification);
    }

    /**
     * Dispara notificação de avaliação marcada como útil (REVIEW_HELPFUL).
     * Preserva o anonimato do votante: actorId é estritamente null.
     */
    @Transactional
    public void notifyReviewHelpful(UUID reviewId, UUID reviewAuthorUserId) {
        if (reviewId == null || reviewAuthorUserId == null) {
            return;
        }

        String metadataJson = buildMetadataJson(null, reviewId, null);
        Notification notification = new Notification(
                null,
                reviewAuthorUserId,
                NotificationType.REVIEW_HELPFUL.name(),
                "Avaliação útil",
                "Sua avaliação recebeu uma marcação como útil.",
                "/api/v1/reviews/" + reviewId,
                metadataJson
        );

        notificationRepository.save(notification);
        enqueuePush(notification);
    }

    /**
     * Dispara notificação de novo comentário raiz em avaliação (NEW_DISCUSSION).
     */
    @Transactional
    public void notifyNewDiscussion(UUID reviewId, UUID reviewAuthorUserId, UUID commenterUserId, UUID discussionId) {
        if (reviewId == null || reviewAuthorUserId == null || commenterUserId == null) {
            return;
        }
        if (commenterUserId.equals(reviewAuthorUserId)) {
            return; // Autor da avaliação comentando na própria avaliação não deve ser notificado
        }

        Map<String, Object> extra = discussionId != null ? Map.of("discussionId", discussionId.toString()) : null;
        String metadataJson = buildMetadataJson(commenterUserId, reviewId, extra);

        Notification notification = new Notification(
                null,
                reviewAuthorUserId,
                NotificationType.NEW_DISCUSSION.name(),
                "Novo comentário",
                "Sua avaliação recebeu um novo comentário.",
                "/api/v1/reviews/" + reviewId + "/discussions",
                metadataJson
        );

        notificationRepository.save(notification);
        enqueuePush(notification);
    }

    /**
     * Dispara notificação de resposta a um comentário (DISCUSSION_REPLY).
     * Se maskActor for true (ex: autor da review respondendo em review anônima), actorId é omitido.
     */
    @Transactional
    public void notifyDiscussionReply(UUID reviewId, UUID parentAuthorUserId, UUID replierUserId,
                                      UUID discussionId, boolean maskActor) {
        if (reviewId == null || parentAuthorUserId == null || replierUserId == null) {
            return;
        }
        if (replierUserId.equals(parentAuthorUserId)) {
            return; // Resposta ao próprio comentário não deve gerar notificação
        }

        UUID actorId = maskActor ? null : replierUserId;
        Map<String, Object> extra = reviewId != null ? Map.of("reviewId", reviewId.toString()) : null;
        String metadataJson = buildMetadataJson(actorId, discussionId, extra);

        Notification notification = new Notification(
                null,
                parentAuthorUserId,
                NotificationType.DISCUSSION_REPLY.name(),
                "Nova resposta",
                "Seu comentário recebeu uma resposta.",
                "/api/v1/reviews/" + reviewId + "/discussions",
                metadataJson
        );

        notificationRepository.save(notification);
        enqueuePush(notification);
    }

    /**
     * Enfileira o efeito externo de push (Step 27.3) na MESMA transação da
     * Notification. O payload carrega exclusivamente o notificationId — nenhum
     * conteúdo de negócio ou dado sensível é persistido no Outbox; o handler
     * resolve o conteúdo a partir da Notification já persistida. A chamada
     * externa ao provider NUNCA ocorre aqui: ela é assíncrona, feita pelo
     * dispatcher fora da transação do produtor.
     */
    private void enqueuePush(Notification notification) {
        String payload = "{\"notificationId\":\"" + notification.getId() + "\"}";
        outboxRepository.save(new OutboxMessage(OutboxMessageType.PUSH_NOTIFICATION.name(), payload));
    }

    /**
     * Lista notificações do usuário autenticado de forma paginada e cronológica reversa.
     */
    @Transactional(readOnly = true)
    public PageResult<NotificationView> findMyNotifications(UUID userId, int page, int size) {
        validatePagination(userId, page, size);

        PageResult<Notification> paged = notificationRepository.findByUserId(userId, page, size);

        List<NotificationView> views = withoutDeletedIdentities(paged.content().stream()
                .map(this::toView)
                .toList());

        return new PageResult<>(
                views,
                paged.pageNumber(),
                paged.pageSize(),
                paged.totalElements(),
                paged.totalPages(),
                paged.isLast()
        );
    }

    /**
     * Retorna a quantidade de notificações não lidas do usuário autenticado.
     */
    @Transactional(readOnly = true)
    public UnreadCountView countUnread(UUID userId) {
        if (userId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        long count = notificationRepository.countUnreadByUserId(userId);
        return new UnreadCountView(count);
    }

    /**
     * Marca uma notificação como lida de forma segura contra IDOR e idempotente.
     */
    @Transactional
    public void markAsRead(UUID notificationId, UUID userId) {
        if (userId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (notificationId == null) {
            throw new BusinessException("Identificador de notificação obrigatório", HttpStatus.BAD_REQUEST, "MISSING_NOTIFICATION_ID");
        }

        // Mutação do próprio usuário: o access token pode ser anterior a uma mudança de estado da conta (C2)
        accountStatusPolicy.requireOperational(userId);

        Notification notification = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new BusinessException("Notificação não encontrada", HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND"));

        if (notification.getReadAt() == null) {
            notification.markAsRead();
            notificationRepository.save(notification);
        }
    }

    /**
     * Marca todas as notificações do usuário autenticado como lidas em lote (atômico e idempotente).
     */
    @Transactional
    public void markAllAsRead(UUID userId) {
        if (userId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        accountStatusPolicy.requireOperational(userId);
        notificationRepository.markAllAsReadByUserId(userId);
    }

    private void validatePagination(UUID userId, int page, int size) {
        if (userId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (page < 0) {
            throw new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        if (size < 1) {
            throw new BusinessException("O tamanho da página deve ser maior que zero", HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE");
        }
        if (size > 50) {
            throw new BusinessException("O tamanho da página não pode ser superior a 50", HttpStatus.BAD_REQUEST, "PAGE_SIZE_EXCEEDED");
        }
    }

    /**
     * O UUID de um ator com conta {@code DELETED} (C2) não chega ao destinatário: actorId e, em NEW_FOLLOWER,
     * referenceId (o próprio seguidor) saem nulos, o mesmo contrato do ator mascarado. A notificação continua.
     */
    private List<NotificationView> withoutDeletedIdentities(List<NotificationView> views) {
        Set<UUID> candidates = views.stream()
                .flatMap(view -> Stream.of(view.actorId(), view.referenceId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> deleted = candidates.isEmpty() ? Set.of() : userRepository.findDeletedUserIds(candidates);
        if (deleted.isEmpty()) {
            return views;
        }
        return views.stream()
                .map(view -> new NotificationView(
                        view.id(),
                        view.type(),
                        deleted.contains(view.actorId()) ? null : view.actorId(),
                        deleted.contains(view.referenceId()) ? null : view.referenceId(),
                        view.readAt(),
                        view.createdAt()))
                .toList();
    }

    private NotificationView toView(Notification n) {
        ParsedMetadata meta = parseMetadata(n.getMetadataJson());
        return new NotificationView(
                n.getId(),
                n.getNotificationType(),
                meta.actorId(),
                meta.referenceId(),
                n.getReadAt(),
                n.getCreatedAt()
        );
    }

    private String buildMetadataJson(UUID actorId, UUID referenceId, Map<String, Object> extra) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("actorId", actorId != null ? actorId.toString() : null);
            map.put("referenceId", referenceId != null ? referenceId.toString() : null);
            if (extra != null) {
                map.putAll(extra);
            }
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private record ParsedMetadata(UUID actorId, UUID referenceId) {}

    private ParsedMetadata parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new ParsedMetadata(null, null);
        }
        try {
            JsonNode node = objectMapper.readTree(metadataJson);
            UUID actorId = node.hasNonNull("actorId") ? UUID.fromString(node.get("actorId").asText()) : null;
            UUID referenceId = node.hasNonNull("referenceId") ? UUID.fromString(node.get("referenceId").asText()) : null;
            return new ParsedMetadata(actorId, referenceId);
        } catch (Exception e) {
            return new ParsedMetadata(null, null);
        }
    }
}
