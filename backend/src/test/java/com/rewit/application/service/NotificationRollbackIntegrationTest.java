package com.rewit.application.service;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.ReviewDetailView;
import com.rewit.application.dto.discussion.DiscussionDtos.CreateDiscussionCommand;
import com.rewit.application.dto.discussion.DiscussionDtos.DiscussionView;
import com.rewit.application.port.NotificationRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.Notification;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.persistence.repository.DiscussionJpaRepository;
import com.rewit.infrastructure.persistence.repository.NotificationJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewReactionJpaRepository;
import com.rewit.infrastructure.persistence.repository.UserFollowJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de rollback transacional real para o subsistema de Notificações (Step 22.1).
 *
 * <p>Estratégia: usa {@link TransactionTemplate} para executar toda a operação
 * (evento originador + persistência de Notification) em uma única transação
 * que é forçada a falhar via RuntimeException, comprovando atomicidade real no PostgreSQL.
 *
 * <p>IMPORTANTE: nenhuma exceção artificial foi inserida no código de produção.
 * A falha simulada ocorre exclusivamente dentro do lambda do TransactionTemplate.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Rollback Transacional Real: Notificações (Step 22.1)")
class NotificationRollbackIntegrationTest {

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
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private UserFollowJpaRepository userFollowJpaRepository;

    @Autowired
    private ReviewReactionJpaRepository reviewReactionJpaRepository;

    @Autowired
    private DiscussionJpaRepository discussionJpaRepository;

    @Autowired
    private NotificationJpaRepository notificationJpaRepository;

    // =========================================================================
    // 1. Rollback de Follow
    // =========================================================================

    @Test
    @DisplayName("Follow: falha após Notification deve reverter Follow e Notification — nada persiste no PostgreSQL")
    void followRollback_whenNotificationFails_nothingPersists() {
        User follower = createAndPersistUser("rb_follow_a");
        User target   = createAndPersistUser("rb_follow_b");

        long initialFollows        = userFollowJpaRepository.count();
        long initialNotifications  = notificationJpaRepository.count();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThrows(RuntimeException.class, () ->
            tx.execute(status -> {
                userFollowService.followUser(follower.getId(), target.getId());
                notificationRepository.save(new Notification(
                    null, target.getId(), "NEW_FOLLOWER", "Seguidor", "Conteudo", null, null
                ));
                throw new RuntimeException("Falha simulada: rollback de Follow + Notification");
            })
        );

        assertEquals(initialFollows, userFollowJpaRepository.count(),
            "Follow deve ser revertido junto com a Notification apos rollback");
        assertEquals(initialNotifications, notificationJpaRepository.count(),
            "Notification de Follow deve ser revertida pelo rollback");
    }

    @Test
    @DisplayName("Follow: falha antes da Notification (alvo inexistente) — nenhuma Notification e criada")
    void followFails_beforeNotification_noNotificationCreated() {
        User follower = createAndPersistUser("rb_follow_c");
        long initialNotifications = notificationJpaRepository.count();

        assertThrows(Exception.class, () ->
            userFollowService.followUser(follower.getId(), UUID.randomUUID())
        );

        assertEquals(initialNotifications, notificationJpaRepository.count(),
            "Nenhuma Notification deve ser criada quando o Follow falha antes do disparo");
    }

    // =========================================================================
    // 2. Rollback de Helpful
    // =========================================================================

    @Test
    @DisplayName("Helpful: falha após Notification deve reverter a reacao e a Notification — nada persiste no PostgreSQL")
    void helpfulRollback_whenNotificationFails_nothingPersists() {
        User voter  = createAndPersistUser("rb_helpful_a");
        User author = createAndPersistUser("rb_helpful_b");
        Place place = createPlace();

        ReviewDetailView review = reviewService.createReview(new CreateReviewCommand(
            author.getId(), place.getId(), "Boa avaliacao", false, "PUBLIC",
            List.of(new CreateReviewTargetCommand(place.getId(), BigDecimal.valueOf(4.0), "Lugar legal"))
        ));

        long initialReactions     = reviewReactionJpaRepository.count();
        long initialNotifications = notificationJpaRepository.count();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThrows(RuntimeException.class, () ->
            tx.execute(status -> {
                reviewHelpfulService.addHelpful(review.id(), voter.getId());
                notificationRepository.save(new Notification(
                    null, author.getId(), "REVIEW_HELPFUL", "Util", "Conteudo", null, null
                ));
                throw new RuntimeException("Falha simulada: rollback de Helpful + Notification");
            })
        );

        assertEquals(initialReactions, reviewReactionJpaRepository.count(),
            "Reacao Helpful deve ser revertida junto com a Notification apos rollback");
        assertEquals(initialNotifications, notificationJpaRepository.count(),
            "Notification de Helpful deve ser revertida pelo rollback");
    }

    @Test
    @DisplayName("Helpful: falha antes da Notification (review inexistente) — nenhuma Notification e criada")
    void helpfulFails_beforeNotification_noNotificationCreated() {
        User voter = createAndPersistUser("rb_helpful_c");
        long initialNotifications = notificationJpaRepository.count();

        assertThrows(Exception.class, () ->
            reviewHelpfulService.addHelpful(UUID.randomUUID(), voter.getId())
        );

        assertEquals(initialNotifications, notificationJpaRepository.count(),
            "Nenhuma Notification deve ser criada quando o Helpful falha antes do disparo");
    }

    // =========================================================================
    // 3. Rollback de Discussion (comentario raiz)
    // =========================================================================

    @Test
    @DisplayName("Discussion: falha apos Notification deve reverter o comentario e a Notification — nada persiste no PostgreSQL")
    void discussionRollback_whenNotificationFails_nothingPersists() {
        User commenter = createAndPersistUser("rb_disc_a");
        User author    = createAndPersistUser("rb_disc_b");
        Place place    = createPlace();

        ReviewDetailView review = reviewService.createReview(new CreateReviewCommand(
            author.getId(), place.getId(), "Avaliacao comentada", false, "PUBLIC",
            List.of(new CreateReviewTargetCommand(place.getId(), BigDecimal.valueOf(5.0), "Excelente"))
        ));

        long initialDiscussions   = discussionJpaRepository.count();
        long initialNotifications = notificationJpaRepository.count();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThrows(RuntimeException.class, () ->
            tx.execute(status -> {
                discussionService.createDiscussion(new CreateDiscussionCommand(
                    review.id(), commenter.getId(), null, "Comentario que sera revertido"
                ));
                notificationRepository.save(new Notification(
                    null, author.getId(), "NEW_DISCUSSION", "Comentario", "Conteudo", null, null
                ));
                throw new RuntimeException("Falha simulada: rollback de Discussion + Notification");
            })
        );

        assertEquals(initialDiscussions, discussionJpaRepository.count(),
            "Discussion deve ser revertida junto com a Notification apos rollback");
        assertEquals(initialNotifications, notificationJpaRepository.count(),
            "Notification de Discussion deve ser revertida pelo rollback");
    }

    @Test
    @DisplayName("Discussion: falha antes da Notification (review inexistente) — nenhuma Notification e criada")
    void discussionFails_beforeNotification_noNotificationCreated() {
        User commenter = createAndPersistUser("rb_disc_c");
        long initialNotifications = notificationJpaRepository.count();

        assertThrows(Exception.class, () ->
            discussionService.createDiscussion(new CreateDiscussionCommand(
                UUID.randomUUID(), commenter.getId(), null, "Comentario em review inexistente"
            ))
        );

        assertEquals(initialNotifications, notificationJpaRepository.count(),
            "Nenhuma Notification deve ser criada quando o Discussion falha antes do disparo");
    }

    // =========================================================================
    // 4. Rollback de Reply (resposta a comentario)
    // =========================================================================

    @Test
    @DisplayName("Reply: falha apos Notification deve reverter a resposta e a Notification — nada persiste no PostgreSQL")
    void replyRollback_whenNotificationFails_nothingPersists() {
        User commenter = createAndPersistUser("rb_reply_a");
        User replier   = createAndPersistUser("rb_reply_b");
        Place place    = createPlace();

        ReviewDetailView review = reviewService.createReview(new CreateReviewCommand(
            replier.getId(), place.getId(), "Avaliacao respondida", false, "PUBLIC",
            List.of(new CreateReviewTargetCommand(place.getId(), BigDecimal.valueOf(4.0), "Bacana"))
        ));

        DiscussionView rootComment = discussionService.createDiscussion(new CreateDiscussionCommand(
            review.id(), commenter.getId(), null, "Comentario raiz"
        ));

        long initialDiscussions   = discussionJpaRepository.count();
        long initialNotifications = notificationJpaRepository.count();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThrows(RuntimeException.class, () ->
            tx.execute(status -> {
                discussionService.createDiscussion(new CreateDiscussionCommand(
                    review.id(), replier.getId(), rootComment.id(), "Resposta que sera revertida"
                ));
                notificationRepository.save(new Notification(
                    null, commenter.getId(), "DISCUSSION_REPLY", "Resposta", "Conteudo", null, null
                ));
                throw new RuntimeException("Falha simulada: rollback de Reply + Notification");
            })
        );

        assertEquals(initialDiscussions, discussionJpaRepository.count(),
            "Reply deve ser revertida junto com a Notification apos rollback");
        assertEquals(initialNotifications, notificationJpaRepository.count(),
            "Notification de Reply deve ser revertida pelo rollback");
    }

    @Test
    @DisplayName("Reply: falha antes da Notification (review inexistente) — nenhuma Notification e criada")
    void replyFails_beforeNotification_noNotificationCreated() {
        User replier = createAndPersistUser("rb_reply_c");
        long initialNotifications = notificationJpaRepository.count();

        assertThrows(Exception.class, () ->
            discussionService.createDiscussion(new CreateDiscussionCommand(
                UUID.randomUUID(), replier.getId(), UUID.randomUUID(), "Resposta a comentario inexistente"
            ))
        );

        assertEquals(initialNotifications, notificationJpaRepository.count(),
            "Nenhuma Notification deve ser criada quando a Reply falha antes do disparo");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private User createAndPersistUser(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email  = prefix + "_" + suffix + "@rollback.test";
        User user = new User(null, email, "$2a$10$abcdefghijklmnopqrstuvwxyzABCDEF1234567890", AuthProvider.LOCAL, null);
        user = userRepository.save(user);
        Profile profile = new Profile(UUID.randomUUID(), user.getId(), "r_" + suffix, "RB " + suffix, "Bio", null);
        profileRepository.save(profile);
        return user;
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
            null, "RB Restaurante " + suffix, "rb-rest-" + suffix,
            "RESTAURANTE", "Descricao", "Rua RB, 1", "1", "Bairro", "Cidade", "UF", "BR",
            -23.5505, -46.6333, 50, "USER", false, null, "ACTIVE"
        );
        return placeRepository.save(place);
    }
}
