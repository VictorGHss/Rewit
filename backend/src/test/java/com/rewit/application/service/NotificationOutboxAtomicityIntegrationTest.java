package com.rewit.application.service;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.ReviewDetailView;
import com.rewit.application.dto.discussion.DiscussionDtos.CreateDiscussionCommand;
import com.rewit.application.dto.discussion.DiscussionDtos.DiscussionView;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Atomicidade transacional real do produtor Notification + Outbox (Step 27.3,
 * Partes D e AB): ação de negócio, Notification in-app e PUSH_NOTIFICATION
 * commitam JUNTOS ou não existem.
 *
 * <p>A prova de "visível somente após o commit" usa uma thread observadora com
 * conexão própria em autocommit: enquanto a transação do produtor está aberta,
 * a linha do outbox é invisível para ela. Nenhuma exceção artificial foi
 * inserida no código de produção — a falha simulada ocorre apenas no lambda do
 * TransactionTemplate.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Atomicidade Transacional Real: Notification + Outbox (Step 27.3, Partes D/AB)")
class NotificationOutboxAtomicityIntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private UserFollowService userFollowService;

    @Autowired
    private ReviewHelpfulService reviewHelpfulService;

    @Autowired
    private DiscussionService discussionService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanOutboxBefore() {
        jdbcTemplate.update("DELETE FROM outbox_messages");
    }

    @AfterEach
    void cleanOutboxAfter() {
        jdbcTemplate.update("DELETE FROM outbox_messages");
    }

    // =========================================================================
    // PARTE AB — Commit: ambos existem; invisibilidade até o commit
    // =========================================================================

    @Test
    @DisplayName("Commit: Notification e PUSH_NOTIFICATION persistem juntos; linha do outbox invisível até o commit")
    void shouldPersistNotificationAndOutboxTogetherOnCommit() throws Exception {
        User follower = createAndPersistUser("ob_commit_follower");
        User target = createAndPersistUser("ob_commit_target");

        AtomicInteger visibleDuringTx = new AtomicInteger(-1);
        AtomicReference<Throwable> observerFailure = new AtomicReference<>();
        CountDownLatch actionDone = new CountDownLatch(1);
        CountDownLatch observationDone = new CountDownLatch(1);

        Thread observer = new Thread(() -> {
            try {
                if (!actionDone.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Ação do produtor não ocorreu a tempo");
                }
                visibleDuringTx.set(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM outbox_messages WHERE message_type = 'PUSH_NOTIFICATION'",
                        Integer.class));
            } catch (Throwable e) {
                observerFailure.set(e);
            } finally {
                observationDone.countDown();
            }
        }, "outbox-observer-commit");
        observer.start();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.execute(status -> {
            userFollowService.followUser(follower.getId(), target.getId());
            actionDone.countDown();
            try {
                if (!observationDone.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Observador externo não concluiu a tempo");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return null;
        });
        observer.join(5000);

        assertNull(observerFailure.get(), "Observador não deve falhar: " + observerFailure.get());
        assertEquals(0, visibleDuringTx.get(), "Antes do commit a linha do outbox é invisível fora da transação");

        Integer outboxRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE message_type = 'PUSH_NOTIFICATION'", Integer.class);
        assertEquals(1, outboxRows, "Commit → exatamente 1 PUSH_NOTIFICATION (1 Notification → no máximo 1)");

        Map<String, Object> message = jdbcTemplate.queryForMap(
                "SELECT status, attempts, locked_at, locked_by FROM outbox_messages WHERE message_type = 'PUSH_NOTIFICATION'");
        assertEquals("PENDING", message.get("status"));
        assertEquals(0, ((Number) message.get("attempts")).intValue());
        assertNull(message.get("locked_at"));
        assertNull(message.get("locked_by"));

        List<String> payloadKeys = jdbcTemplate.queryForList(
                "SELECT DISTINCT k FROM outbox_messages, jsonb_object_keys(payload) AS k WHERE message_type = 'PUSH_NOTIFICATION'",
                String.class);
        assertEquals(List.of("notificationId"), payloadKeys, "Payload carrega SOMENTE o notificationId (Parte C)");

        String notificationId = jdbcTemplate.queryForObject(
                "SELECT payload->>'notificationId' FROM outbox_messages WHERE message_type = 'PUSH_NOTIFICATION'",
                String.class);
        Long notificationRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE id = ?::uuid AND user_id = ?::uuid",
                Long.class, notificationId, target.getId().toString());
        assertEquals(1L, notificationRows, "A Notification referenciada existe e pertence ao recipient");
    }

    // =========================================================================
    // PARTE AB — Rollback: nenhum existe
    // =========================================================================

    @Test
    @DisplayName("Rollback: falha após o enqueue não deixa nem Notification nem PUSH_NOTIFICATION no PostgreSQL")
    void shouldRollbackBothNotificationAndOutboxOnFailure() throws Exception {
        User follower = createAndPersistUser("ob_rb_follower");
        User target = createAndPersistUser("ob_rb_target");

        Long notificationsBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM notifications", Long.class);
        Long outboxBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE message_type = 'PUSH_NOTIFICATION'", Long.class);

        AtomicInteger visibleDuringTx = new AtomicInteger(-1);
        AtomicReference<Throwable> observerFailure = new AtomicReference<>();
        CountDownLatch actionDone = new CountDownLatch(1);
        CountDownLatch observationDone = new CountDownLatch(1);

        Thread observer = new Thread(() -> {
            try {
                if (!actionDone.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Ação do produtor não ocorreu a tempo");
                }
                visibleDuringTx.set(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM outbox_messages WHERE message_type = 'PUSH_NOTIFICATION'",
                        Integer.class));
            } catch (Throwable e) {
                observerFailure.set(e);
            } finally {
                observationDone.countDown();
            }
        }, "outbox-observer-rollback");
        observer.start();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThrows(RuntimeException.class, () -> tx.execute(status -> {
            userFollowService.followUser(follower.getId(), target.getId());
            actionDone.countDown();
            try {
                if (!observationDone.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Observador externo não concluiu a tempo");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            throw new RuntimeException("Falha simulada após enqueue: rollback de Notification + Outbox");
        }));
        observer.join(5000);

        assertNull(observerFailure.get(), "Observador não deve falhar: " + observerFailure.get());
        assertEquals(0, visibleDuringTx.get(), "Nada visível enquanto a transação está aberta");
        assertEquals(outboxBefore, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE message_type = 'PUSH_NOTIFICATION'", Long.class),
                "Rollback → nenhuma mensagem de outbox persiste");
        assertEquals(notificationsBefore, jdbcTemplate.queryForObject("SELECT count(*) FROM notifications", Long.class),
                "Rollback → nenhuma Notification persiste");
    }

    // =========================================================================
    // PARTE E — Cada fluxo conectado enfileira exatamente 1 PUSH_NOTIFICATION
    // =========================================================================

    @Test
    @DisplayName("Fluxos Helpful, Discussion e Reply enfileiram exatamente 1 PUSH_NOTIFICATION cada, com payload mínimo")
    void shouldEnqueueExactlyOnePushForEachConnectedFlow() {
        User author = createAndPersistUser("ob_flow_author");
        User actor = createAndPersistUser("ob_flow_actor");
        Place place = createPlace();

        ReviewDetailView review = reviewService.createReview(new CreateReviewCommand(
                author.getId(), place.getId(), "Avaliacao com push", false, "PUBLIC",
                List.of(new CreateReviewTargetCommand(place.getId(), BigDecimal.valueOf(4.0), "Lugar legal"))
        ));

        Set<String> seenNotificationIds = new HashSet<>();

        reviewHelpfulService.addHelpful(review.id(), actor.getId());
        String helpfulNotificationId = newestUnseenNotificationId(author.getId(), seenNotificationIds);
        assertEquals(1, outboxRowsFor(helpfulNotificationId), "Helpful: 1 Notification → no máximo 1 PUSH_NOTIFICATION");

        DiscussionView rootComment = discussionService.createDiscussion(new CreateDiscussionCommand(
                review.id(), actor.getId(), null, "Comentario raiz com push"
        ));
        String discussionNotificationId = newestUnseenNotificationId(author.getId(), seenNotificationIds);
        assertEquals(1, outboxRowsFor(discussionNotificationId), "Discussion: 1 Notification → no máximo 1 PUSH_NOTIFICATION");

        discussionService.createDiscussion(new CreateDiscussionCommand(
                review.id(), author.getId(), rootComment.id(), "Resposta com push"
        ));
        String replyNotificationId = newestUnseenNotificationId(actor.getId(), seenNotificationIds);
        assertEquals(1, outboxRowsFor(replyNotificationId), "Reply: 1 Notification → no máximo 1 PUSH_NOTIFICATION");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private long outboxRowsFor(String notificationId) {
        Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE message_type = 'PUSH_NOTIFICATION' "
                        + "AND payload->>'notificationId' = ?",
                Long.class, notificationId);
        return rows == null ? 0 : rows;
    }

    private String newestUnseenNotificationId(UUID userId, Set<String> seenNotificationIds) {
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id::text FROM notifications WHERE user_id = ?::uuid", String.class, userId.toString());
        ids.removeAll(seenNotificationIds);
        assertEquals(1, ids.size(), "Cada ação conectada deve gerar exatamente 1 Notification nova");
        String notificationId = ids.get(0);
        seenNotificationIds.add(notificationId);
        return notificationId;
    }

    private User createAndPersistUser(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "_" + suffix + "@outbox.test";
        User user = new User(null, email, "$2a$10$abcdefghijklmnopqrstuvwxyzABCDEF1234567890", AuthProvider.LOCAL, null);
        user = userRepository.save(user);
        Profile profile = new Profile(UUID.randomUUID(), user.getId(), "o_" + suffix, "OB " + suffix, "Bio", null);
        profileRepository.save(profile);
        return user;
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
                null, "OB Restaurante " + suffix, "ob-rest-" + suffix,
                "RESTAURANTE", "Descricao", "Rua OB, 1", "1", "Bairro", "Cidade", "UF", "BR",
                -23.5505, -46.6333, 50, "USER", false, null, "ACTIVE"
        );
        return placeRepository.save(place);
    }
}
