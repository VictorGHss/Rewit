package com.rewit.application.service;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.ReviewDetailView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.discussion.DiscussionDtos.CreateDiscussionCommand;
import com.rewit.application.dto.discussion.DiscussionDtos.DiscussionView;
import com.rewit.application.dto.notification.NotificationDtos.NotificationView;
import com.rewit.application.dto.report.ReportDtos.CreateReportCommand;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.NotificationType;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes End-to-End de Disparo de Notificações por Eventos Reais (Step 22.0)")
class NotificationEventsIntegrationTest {

    @Autowired
    private UserFollowService userFollowService;

    @Autowired
    private ReviewHelpfulService reviewHelpfulService;

    @Autowired
    private DiscussionService discussionService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Test
    @DisplayName("Cenário Follow: A segue B -> B recebe exatamente 1 notificação NEW_FOLLOWER; follow duplicado não duplica")
    void shouldTriggerNewFollowerNotification() {
        User userA = createAndPersistUser("follow_a@test.com");
        User userB = createAndPersistUser("follow_b@test.com");

        boolean followedFirst = userFollowService.followUser(userA.getId(), userB.getId());
        assertTrue(followedFirst);

        PageResult<NotificationView> notifsB = notificationService.findMyNotifications(userB.getId(), 0, 10);
        assertEquals(1, notifsB.totalElements());
        assertEquals(NotificationType.NEW_FOLLOWER.name(), notifsB.content().get(0).type());
        assertEquals(userA.getId(), notifsB.content().get(0).actorId());

        // Follow duplicado (idempotente)
        boolean followedSecond = userFollowService.followUser(userA.getId(), userB.getId());
        assertFalse(followedSecond);

        PageResult<NotificationView> notifsBAfter = notificationService.findMyNotifications(userB.getId(), 0, 10);
        assertEquals(1, notifsBAfter.totalElements(), "Follow duplicado não deve criar segunda notificação");
    }

    @Test
    @DisplayName("Cenário Helpful: A dá Helpful na Review de B -> B recebe REVIEW_HELPFUL genérica sem identidade de A")
    void shouldTriggerHelpfulNotificationWithoutVoterIdentity() {
        User userA = createAndPersistUser("helpful_a@test.com");
        User userB = createAndPersistUser("helpful_b@test.com");
        Place place = createPlace();

        ReviewDetailView review = reviewService.createReview(new CreateReviewCommand(
                userB.getId(),
                place.getId(),
                "Excelente experiência!",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(
                        place.getId(),
                        BigDecimal.valueOf(5.0),
                        "Excelente lugar!"
                ))
        ));

        ReviewHelpfulService.HelpfulResult res = reviewHelpfulService.addHelpful(review.id(), userA.getId());
        assertTrue(res.helpful());

        PageResult<NotificationView> notifsB = notificationService.findMyNotifications(userB.getId(), 0, 10);
        assertEquals(1, notifsB.totalElements());
        assertEquals(NotificationType.REVIEW_HELPFUL.name(), notifsB.content().get(0).type());
        assertEquals(review.id(), notifsB.content().get(0).referenceId());
        assertNull(notifsB.content().get(0).actorId(), "Votante nunca deve ser exposto no actorId");
    }

    @Test
    @DisplayName("Cenário Discussion: A comenta Review de B -> B recebe NEW_DISCUSSION com actorId de A")
    void shouldTriggerNewDiscussionNotification() {
        User userA = createAndPersistUser("disc_a@test.com");
        User userB = createAndPersistUser("disc_b@test.com");
        Place place = createPlace();

        ReviewDetailView review = reviewService.createReview(new CreateReviewCommand(
                userB.getId(),
                place.getId(),
                "Muito bom!",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(
                        place.getId(),
                        BigDecimal.valueOf(4.0),
                        "Local agradável"
                ))
        ));

        DiscussionView comment = discussionService.createDiscussion(new CreateDiscussionCommand(
                review.id(),
                userA.getId(),
                null,
                "Concordo totalmente com a avaliação!"
        ));
        assertNotNull(comment);

        PageResult<NotificationView> notifsB = notificationService.findMyNotifications(userB.getId(), 0, 10);
        assertEquals(1, notifsB.totalElements());
        assertEquals(NotificationType.NEW_DISCUSSION.name(), notifsB.content().get(0).type());
        assertEquals(userA.getId(), notifsB.content().get(0).actorId());
        assertEquals(review.id(), notifsB.content().get(0).referenceId());
    }

    @Test
    @DisplayName("Cenário Reply: B responde ao comentário de A -> A recebe DISCUSSION_REPLY")
    void shouldTriggerDiscussionReplyNotification() {
        User userA = createAndPersistUser("reply_a@test.com");
        User userB = createAndPersistUser("reply_b@test.com");
        Place place = createPlace();

        ReviewDetailView review = reviewService.createReview(new CreateReviewCommand(
                userB.getId(),
                place.getId(),
                "Muito bom!",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(
                        place.getId(),
                        BigDecimal.valueOf(4.0),
                        "Local bacana"
                ))
        ));

        // Comentário raiz de A
        DiscussionView commentA = discussionService.createDiscussion(new CreateDiscussionCommand(
                review.id(),
                userA.getId(),
                null,
                "Pergunta sobre o prato principal?"
        ));

        // Resposta de B
        DiscussionView replyB = discussionService.createDiscussion(new CreateDiscussionCommand(
                review.id(),
                userB.getId(),
                commentA.id(),
                "O prato principal é excelente!"
        ));
        assertNotNull(replyB);

        PageResult<NotificationView> notifsA = notificationService.findMyNotifications(userA.getId(), 0, 10);
        assertEquals(1, notifsA.totalElements());
        assertEquals(NotificationType.DISCUSSION_REPLY.name(), notifsA.content().get(0).type());
        assertEquals(userB.getId(), notifsA.content().get(0).actorId());
        assertEquals(replyB.id(), notifsA.content().get(0).referenceId());
    }

    @Test
    @DisplayName("Cenário Anonymous Review: Autor anônimo responde a comentário -> Destinatário recebe DISCUSSION_REPLY com actorId = null")
    void shouldMaskActorIdWhenAnonymousOwnerReplies() {
        User anonOwner = createAndPersistUser("anon_owner@test.com");
        User commenter = createAndPersistUser("commenter@test.com");
        Place place = createPlace();

        // Review ANÔNIMA
        ReviewDetailView review = reviewService.createReview(new CreateReviewCommand(
                anonOwner.getId(),
                place.getId(),
                "Review 100% anônima",
                true, // isAnonymous = true
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(
                        place.getId(),
                        BigDecimal.valueOf(5.0),
                        "Lugar incrível"
                ))
        ));

        // Comentário de terceiro
        DiscussionView comment = discussionService.createDiscussion(new CreateDiscussionCommand(
                review.id(),
                commenter.getId(),
                null,
                "Comentário na review anônima"
        ));

        // Autor da review anônima responde
        DiscussionView ownerReply = discussionService.createDiscussion(new CreateDiscussionCommand(
                review.id(),
                anonOwner.getId(),
                comment.id(),
                "Obrigado pelo comentário!"
        ));
        assertNotNull(ownerReply);

        PageResult<NotificationView> commenterNotifs = notificationService.findMyNotifications(commenter.getId(), 0, 10);
        assertEquals(1, commenterNotifs.totalElements());
        NotificationView notif = commenterNotifs.content().get(0);
        assertEquals(NotificationType.DISCUSSION_REPLY.name(), notif.type());
        assertNull(notif.actorId(), "Autor anônimo NÃO deve ter actorId revelado na notificação");
    }

    @Test
    @DisplayName("Cenário Report: A denuncia Review de B -> NENHUMA notificação é gerada para B ou A")
    void shouldNeverTriggerNotificationOnReport() {
        User reporterA = createAndPersistUser("rep_a@test.com");
        User authorB = createAndPersistUser("rep_b@test.com");
        Place place = createPlace();

        ReviewDetailView review = reviewService.createReview(new CreateReviewCommand(
                authorB.getId(),
                place.getId(),
                "Comida ruim",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(
                        place.getId(),
                        BigDecimal.valueOf(1.0),
                        "Péssimo"
                ))
        ));

        ReportService.CreateReportResult res = reportService.createReport(new CreateReportCommand(
                reporterA.getId(),
                review.id(),
                ReportReason.SPAM,
                "Denúncia de spam"
        ));
        assertTrue(res.newlyCreated());

        PageResult<NotificationView> notifsB = notificationService.findMyNotifications(authorB.getId(), 0, 10);
        assertEquals(0, notifsB.totalElements(), "Autor da Review NÃO deve receber notificação de denúncia");

        PageResult<NotificationView> notifsA = notificationService.findMyNotifications(reporterA.getId(), 0, 10);
        assertEquals(0, notifsA.totalElements(), "Denunciante NÃO deve receber notificação de denúncia");
    }

    private User createAndPersistUser(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String cleanPrefix = prefix.replace("@", "_").replace(".", "_");
        String email = cleanPrefix + "_" + suffix + "@test.com";
        User user = new User(
                null,
                email,
                "$2a$10$abcdefghijklmnopqrstuvwxyzABCDEF1234567890",
                AuthProvider.LOCAL,
                null
        );
        user = userRepository.save(user);

        Profile profile = new Profile(
                UUID.randomUUID(),
                user.getId(),
                "u_" + suffix,
                "User " + suffix,
                "Bio",
                null
        );
        profileRepository.save(profile);
        return user;
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
                null,
                "Restaurante Event " + suffix,
                "restaurante-event-" + suffix,
                "RESTAURANTE",
                "Descrição",
                "Rua Event, 100",
                "100",
                "Bairro",
                "Cidade",
                "UF",
                "BR",
                -23.5505,
                -46.6333,
                50,
                "USER",
                false,
                null,
                "ACTIVE"
        );
        return placeRepository.save(place);
    }
}
