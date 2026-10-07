package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.AccountPurgeRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * SQL nativo do purge de conta excluída (C2.3). Roda obrigatoriamente dentro da transação do caso de uso, que já
 * trava a linha da conta: as tabelas abaixo são tocadas depois de {@code users}, a mesma ordem das transições de
 * ciclo de vida (conta e depois sessões), sem ordem de locks inversa.
 */
@Component
public class AccountPurgeRepositoryAdapter implements AccountPurgeRepository {

    private final EntityManager entityManager;

    public AccountPurgeRepositoryAdapter(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PurgeCounts purgePersonalRelations(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");

        int sessions = update("DELETE FROM auth_sessions WHERE user_id = :userId", userId);
        int follows = update("DELETE FROM user_follows WHERE follower_user_id = :userId OR followed_user_id = :userId", userId);
        int savedItems = update("DELETE FROM saved_items WHERE user_id = :userId", userId);
        int interests = update("DELETE FROM user_interests WHERE user_id = :userId", userId);
        int activities = update("DELETE FROM user_activities WHERE user_id = :userId", userId);
        int ownNotifications = update("DELETE FROM notifications WHERE user_id = :userId", userId);

        // Notificações de outros usuários: o ator excluído vira null (a mesma representação da leitura, C2.2) e o
        // link para o perfil dele sai. A notificação continua com o destinatário.
        int othersNotifications = entityManager.createNativeQuery("""
                UPDATE notifications
                SET metadata_json = CASE
                        WHEN metadata_json IS NULL THEN NULL
                        ELSE jsonb_set(
                                jsonb_set(metadata_json, '{actorId}',
                                        CASE WHEN metadata_json ->> 'actorId' = :userText THEN 'null'::jsonb
                                             ELSE COALESCE(metadata_json -> 'actorId', 'null'::jsonb) END),
                                '{referenceId}',
                                CASE WHEN metadata_json ->> 'referenceId' = :userText THEN 'null'::jsonb
                                     ELSE COALESCE(metadata_json -> 'referenceId', 'null'::jsonb) END)
                    END,
                    action_url = CASE WHEN action_url = :profileUrl THEN NULL ELSE action_url END
                WHERE metadata_json ->> 'actorId' = :userText
                   OR metadata_json ->> 'referenceId' = :userText
                   OR action_url = :profileUrl
                """)
                .setParameter("userText", userId.toString())
                .setParameter("profileUrl", "/api/v1/users/" + userId)
                .executeUpdate();

        // Snapshot derivado: recalculável e sem uso para uma conta excluída (a leitura já responde 404)
        int reputationSnapshots = update("DELETE FROM user_reputation WHERE user_id = :userId", userId);

        // FK ON DELETE SET NULL: a coluna foi desenhada para perder o autor; a presença do produto continua
        int productPresences = update(
                "UPDATE product_presences SET reported_by_user_id = NULL WHERE reported_by_user_id = :userId", userId);

        // Coordenadas que o usuário informou ao avaliar (anuláveis). updated_at não muda: não é uma edição da avaliação
        int reviewLocations = update("""
                UPDATE reviews
                SET user_coordinates = NULL, location_accuracy_meters = NULL
                WHERE user_id = :userId
                  AND (user_coordinates IS NOT NULL OR location_accuracy_meters IS NOT NULL)
                """, userId);

        return new PurgeCounts(sessions, follows, savedItems, interests, activities, ownNotifications,
                othersNotifications, reputationSnapshots, productPresences, reviewLocations);
    }

    private int update(String sql, UUID userId) {
        return entityManager.createNativeQuery(sql).setParameter("userId", userId).executeUpdate();
    }
}
