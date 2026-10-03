package com.rewit.infrastructure.outbox;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.ReviewDetailView;
import com.rewit.application.outbox.OutboxErrorSanitizer;
import com.rewit.application.outbox.OutboxRetryPolicy;
import com.rewit.application.outbox.OutboxTransientException;
import com.rewit.application.outbox.PushNotificationHandler;
import com.rewit.application.port.NotificationProvider;
import com.rewit.application.port.NotificationRepository;
import com.rewit.application.port.OutboxHandler;
import com.rewit.application.port.OutboxRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.ReviewHelpfulService;
import com.rewit.application.service.ReviewService;
import com.rewit.application.service.UserFollowService;
import com.rewit.application.usecase.ProcessOutboxBatchUseCase;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.OutboxStatus;
import com.rewit.domain.model.OutboxMessage;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.persistence.repository.UserFollowJpaRepository;

/**
 * E2E do push externo contra PostgreSQL real (Step 27.3, Partes R/S/T/U/V):
 * ação de negócio → Notification in-app → PUSH_NOTIFICATION no outbox (mesma
 * transação) → dispatcher existente → PushNotificationHandler → provider.
 *
 * <p>O caminho feliz usa o handler real do contexto (wiring do
 * OutboxDispatcherConfig com o MockNotificationAdapter). Para retry, falha
 * permanente e isolamento, o handler é montado manualmente com um provider
 * gravável e controlável — nenhuma espera real (sleep): a elegibilidade do
 * retry é forçada deterministicamente no banco.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes Reais de Integração E2E: Push via Outbox (Step 27.3)")
class NotificationPushDeliveryIntegrationTest {

    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxMetrics outboxMetrics;

    @Autowired
    private OutboxRetryPolicy outboxRetryPolicy;

    @Autowired
    private OutboxErrorSanitizer outboxErrorSanitizer;

    @Autowired
    private PushNotificationHandler realPushHandler;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserFollowService userFollowService;

    @Autowired
    private ReviewHelpfulService reviewHelpfulService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private UserFollowJpaRepository userFollowJpaRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String workerId = "worker-push-e2e-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    @SuppressWarnings("unused")
    void cleanOutboxBefore() {
        jdbcTemplate.update("DELETE FROM outbox_messages");
    }

    @AfterEach
    @SuppressWarnings("unused")
    void cleanOutboxAfter() {
        jdbcTemplate.update("DELETE FROM outbox_messages");
    }

    // =========================================================================
    // PARTE R — E2E completo até o MockNotificationAdapter
    // =========================================================================

    @Test
    @DisplayName("R: follow commitado → Notification + PUSH_NOTIFICATION → poller com handler real → COMPLETED")
    void shouldDeliverPushEndToEndThroughRealWiring() {
        User follower = createAndPersistUser("e2e_follow_a");
        User target = createAndPersistUser("e2e_follow_b");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.execute(status -> {
            userFollowService.followUser(follower.getId(), target.getId());
            return null;
        });

        assertEquals(1, pushMessageCount(), "Commit do produtor → mensagem enfileirada");

        ProcessOutboxBatchUseCase useCase = useCaseWith(realPushHandler);
        new OutboxDispatcherPoller(useCase, outboxMetrics).dispatchPendingMessages();

        Optional<OutboxMessage> reloaded = outboxRepository.findById(solePushMessageId());
        assertTrue(reloaded.isPresent());
        assertEquals(OutboxStatus.COMPLETED, reloaded.get().getStatus(), "MockNotificationAdapter nunca falha → COMPLETED");
        assertEquals(1, reloaded.get().getAttempts());
        assertNull(reloaded.get().getLockedAt());
        assertNull(reloaded.get().getLockedBy());
    }

    @Test
    @DisplayName("R-extra: provider recebe recipient, título, corpo e metadados fiéis à Notification (follow)")
    void shouldForwardNotificationContentToProviderFaithfully() {
        User follower = createAndPersistUser("e2e_content_a");
        User target = createAndPersistUser("e2e_content_b");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.execute(status -> {
            userFollowService.followUser(follower.getId(), target.getId());
            return null;
        });

        RecordingProvider provider = new RecordingProvider();
        ProcessOutboxBatchUseCase useCase = useCaseWith(handlerWith(provider));
        ProcessOutboxBatchUseCase.ProcessOutboxBatchResult result = useCase.processPendingBatch();

        assertEquals(1, result.completedCount());
        assertEquals(1, provider.calls.size());
        RecordingProvider.PushCall call = provider.calls.get(0);
        assertEquals(target.getId(), call.recipient());
        assertEquals("Novo seguidor", call.title());
        assertEquals("Você tem um novo seguidor.", call.body());
        assertEquals(follower.getId().toString(), call.metadata().get("actorId"), "actorId fiel à Notification");
        assertEquals(follower.getId().toString(), call.metadata().get("referenceId"));
    }

    // =========================================================================
    // PARTE S — Retry E2E sem espera real
    // =========================================================================

    @Test
    @DisplayName("S: falha transiente → PENDING com next_attempt_at futuro → nova tentativa → COMPLETED (sem sleep)")
    void shouldRetryTransientFailureAndCompleteWithoutRealSleep() {
        User follower = createAndPersistUser("e2e_retry_a");
        User target = createAndPersistUser("e2e_retry_b");
        enqueueFollow(follower, target);

        RecordingProvider provider = new RecordingProvider();
        provider.remainingTransientFailures = 1;
        ProcessOutboxBatchUseCase useCase = useCaseWith(handlerWith(provider));

        ProcessOutboxBatchUseCase.ProcessOutboxBatchResult firstCycle = useCase.processPendingBatch();
        assertEquals(1, firstCycle.retriedCount(), "Falha transiente reagenda a mensagem");

        Optional<OutboxMessage> afterFirstCycle = outboxRepository.findById(solePushMessageId());
        assertTrue(afterFirstCycle.isPresent());
        assertEquals(OutboxStatus.PENDING, afterFirstCycle.get().getStatus());
        assertEquals(1, afterFirstCycle.get().getAttempts(), "Claim incrementa attempts para a primeira tentativa");
        assertTrue(afterFirstCycle.get().getNextAttemptAt().isAfter(Instant.now()),
                "next_attempt_at deve estar no futuro (backoff)");
        assertNotNull(afterFirstCycle.get().getLastError());
        assertFalse(afterFirstCycle.get().getLastError().contains("\n"), "last_error sanitizado é linha única");

        jdbcTemplate.update("UPDATE outbox_messages SET next_attempt_at = now() - interval '1 second' "
                + "WHERE message_type = 'PUSH_NOTIFICATION'");

        ProcessOutboxBatchUseCase.ProcessOutboxBatchResult secondCycle = useCase.processPendingBatch();
        assertEquals(1, secondCycle.completedCount(), "Segunda tentativa com provider recuperado conclui");

        Optional<OutboxMessage> afterSecondCycle = outboxRepository.findById(solePushMessageId());
        assertTrue(afterSecondCycle.isPresent());
        assertEquals(OutboxStatus.COMPLETED, afterSecondCycle.get().getStatus());
        assertEquals(2, afterSecondCycle.get().getAttempts(), "Cada tentativa incrementa attempts");
        assertEquals(2, provider.calls.size(), "Provider foi invocado exatamente nas duas tentativas");
    }

    // =========================================================================
    // PARTE T — Falha permanente E2E
    // =========================================================================

    @Test
    @DisplayName("T: Notification removida → FAILED terminal, attempts consistente, sem lock e sem volta a PENDING")
    void shouldFailPermanentlyWhenNotificationNoLongerExists() {
        User follower = createAndPersistUser("e2e_perm_a");
        User target = createAndPersistUser("e2e_perm_b");
        enqueueFollow(follower, target);

        String notificationId = jdbcTemplate.queryForObject(
                "SELECT payload->>'notificationId' FROM outbox_messages WHERE message_type = 'PUSH_NOTIFICATION'",
                String.class);
        jdbcTemplate.update("DELETE FROM notifications WHERE id = ?::uuid", notificationId);

        ProcessOutboxBatchUseCase useCase = useCaseWith(realPushHandler);
        ProcessOutboxBatchUseCase.ProcessOutboxBatchResult firstCycle = useCase.processPendingBatch();

        assertEquals(1, firstCycle.failedCount(), "Notificação inexistente é falha PERMANENTE");
        Optional<OutboxMessage> failed = outboxRepository.findById(solePushMessageId());
        assertTrue(failed.isPresent());
        assertEquals(OutboxStatus.FAILED, failed.get().getStatus());
        assertEquals(1, failed.get().getAttempts(), "FAILED na primeira tentativa (producer semeou 0 + 1 claim)");
        assertNotNull(failed.get().getLastError());
        assertTrue(failed.get().getLastError().contains("inexistente"));
        assertFalse(failed.get().getLastError().contains("\n"), "last_error sanitizado é linha única");
        assertNull(failed.get().getLockedAt());
        assertNull(failed.get().getLockedBy());

        ProcessOutboxBatchUseCase.ProcessOutboxBatchResult secondCycle = useCase.processPendingBatch();
        assertEquals(0, secondCycle.claimedCount(), "FAILED é terminal: nova tentativa não reivindica a mensagem");
        assertEquals(OutboxStatus.FAILED, outboxRepository.findById(solePushMessageId()).get().getStatus(),
                "Mensagem não volta a PENDING");
    }

    // =========================================================================
    // PARTE U — Isolamento: falha do provider não desfaz a ação commitada
    // =========================================================================

    @Test
    @DisplayName("U: provider indisponível após commit → Notification, follow e mensagem PENDING permanecem")
    void shouldNotRollBackCommittedActionWhenProviderFails() {
        User follower = createAndPersistUser("e2e_iso_a");
        User target = createAndPersistUser("e2e_iso_b");
        long followsBefore = userFollowJpaRepository.count();
        enqueueFollow(follower, target);

        Long notificationRowsAfterCommit = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ?::uuid", Long.class, target.getId().toString());
        assertEquals(1L, notificationRowsAfterCommit, "Notification persistida no commit do produtor");

        RecordingProvider provider = new RecordingProvider();
        provider.remainingTransientFailures = 999;
        ProcessOutboxBatchUseCase useCase = useCaseWith(handlerWith(provider));
        ProcessOutboxBatchUseCase.ProcessOutboxBatchResult result = useCase.processPendingBatch();

        assertEquals(1, result.retriedCount(), "Falha do provider é transiente → volta a PENDING");
        assertEquals(followsBefore + 1, userFollowJpaRepository.count(),
                "Falha do push NÃO desfaz o follow commitado");
        assertEquals(1L, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM notifications WHERE user_id = ?::uuid", Long.class, target.getId().toString()),
                "Falha do push NÃO remove a Notification in-app");
        Optional<OutboxMessage> reloaded = outboxRepository.findById(solePushMessageId());
        assertTrue(reloaded.isPresent());
        assertEquals(OutboxStatus.PENDING, reloaded.get().getStatus(), "Mensagem aguarda nova tentativa pelo dispatcher");
        assertEquals(1, provider.calls.size(), "Provider chegou a ser invocado (at-least-once)");
    }

    // =========================================================================
    // PARTE V — Anonimato no push
    // =========================================================================

    @Test
    @DisplayName("V: push de Helpful preserva anonimato — actorId null e nenhum ID de usuário nos metadados")
    void shouldPreserveAnonymityInHelpfulPush() {
        User author = createAndPersistUser("e2e_anon_author");
        User voter = createAndPersistUser("e2e_anon_voter");
        Place place = createPlace();
        ReviewDetailView review = reviewService.createReview(new CreateReviewCommand(
                author.getId(), place.getId(), "Review anonima testada", false, "PUBLIC",
                List.of(new CreateReviewTargetCommand(place.getId(), BigDecimal.valueOf(4.0), "Bom"))
        ));

        reviewHelpfulService.addHelpful(review.id(), voter.getId());

        RecordingProvider provider = new RecordingProvider();
        ProcessOutboxBatchUseCase useCase = useCaseWith(handlerWith(provider));
        ProcessOutboxBatchUseCase.ProcessOutboxBatchResult result = useCase.processPendingBatch();

        assertEquals(1, result.completedCount());
        assertEquals(1, provider.calls.size());
        RecordingProvider.PushCall call = provider.calls.get(0);
        assertEquals(author.getId(), call.recipient(), "Recipient é o autor da review");
        assertTrue(call.metadata().containsKey("actorId"), "Chave actorId preservada");
        assertNull(call.metadata().get("actorId"), "actorId permanece null: push não cria identidade");
        assertEquals(review.id().toString(), call.metadata().get("referenceId"));
        assertFalse(call.metadata().containsValue(voter.getId().toString()),
                "Identidade do votante não pode vazar para o push");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private ProcessOutboxBatchUseCase useCaseWith(OutboxHandler handler) {
        return new ProcessOutboxBatchUseCase(
                outboxRepository, List.of(handler), outboxRetryPolicy, outboxErrorSanitizer,
                workerId, 10, LEASE_DURATION);
    }

    private PushNotificationHandler handlerWith(NotificationProvider provider) {
        return new PushNotificationHandler(notificationRepository, provider, new ObjectMapper());
    }

    private void enqueueFollow(User follower, User target) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.execute(status -> {
            userFollowService.followUser(follower.getId(), target.getId());
            return null;
        });
    }

    private long pushMessageCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE message_type = 'PUSH_NOTIFICATION'", Long.class);
        return count == null ? 0 : count;
    }

    private UUID solePushMessageId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM outbox_messages WHERE message_type = 'PUSH_NOTIFICATION'", UUID.class);
    }

    private User createAndPersistUser(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "_" + suffix + "@outbox.test";
        User user = new User(null, email, "$2a$10$abcdefghijklmnopqrstuvwxyzABCDEF1234567890", AuthProvider.LOCAL, null);
        user = userRepository.save(user);
        Profile profile = new Profile(UUID.randomUUID(), user.getId(), "e_" + suffix, "E2E " + suffix, "Bio", null);
        profileRepository.save(profile);
        return user;
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
                null, "E2E Restaurante " + suffix, "e2e-rest-" + suffix,
                "RESTAURANTE", "Descricao", "Rua E2E, 1", "1", "Bairro", "Cidade", "UF", "BR",
                -23.5505, -46.6333, 50, "USER", false, null, "ACTIVE"
        );
        return placeRepository.save(place);
    }

    /**
     * Provider gravável de teste: registra cada chamada e permite simular
     * falhas transitentes controladas (as permanentes bastam ao handler via
     * OutboxPermanentException, testado nos testes unitários).
     */
    private static final class RecordingProvider implements NotificationProvider {

        private final List<PushCall> calls = new ArrayList<>();
        private volatile int remainingTransientFailures = 0;

        @Override
        public void sendPushNotification(UUID recipientUserId, String title, String body, Map<String, String> metadata) {
            calls.add(new PushCall(recipientUserId, title, body, metadata));
            if (remainingTransientFailures > 0) {
                remainingTransientFailures--;
                throw new OutboxTransientException("provider indisponível");
            }
        }

        private record PushCall(UUID recipient, String title, String body, Map<String, String> metadata) {}
    }
}
