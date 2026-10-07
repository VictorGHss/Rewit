package com.rewit.application.usecase;

import com.rewit.application.dto.discussion.DiscussionReportDtos.ReportDiscussionCommand;
import com.rewit.application.dto.discussion.DiscussionReportDtos.ReportDiscussionResult;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewDiscussion;
import com.rewit.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Denúncia de discussão e auto-quarentena (C3 D1) com beans reais e PostgreSQL real.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Denúncias de discussões e auto-quarentena: PostgreSQL real")
class ReportDiscussionIntegrationTest {

    @Autowired private ReportDiscussionUseCase reportDiscussionUseCase;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private DiscussionRepository discussionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User reviewAuthor;
    private User commenter;
    private Place place;
    private Review review;
    private ReviewDiscussion comment;

    @BeforeEach
    void setUp() {
        reviewAuthor = createUser("autor");
        commenter = createUser("comentarista");
        place = createPlace();
        review = createReview(reviewAuthor.getId(), "PUBLIC");
        comment = createDiscussion(review, commenter.getId(), null);
    }

    @Test
    @DisplayName("Denúncia válida é persistida como PENDING e o comentário segue ACTIVE")
    void validReportIsPersisted() {
        User reporter = createUser("denunciante");

        ReportDiscussionResult result = report(reporter, comment);

        assertTrue(result.newlyCreated());
        assertEquals("PENDING", jdbcTemplate.queryForObject(
                "SELECT status FROM discussion_reports WHERE id = ?", String.class, result.reportId()));
        assertEquals(DiscussionStatus.ACTIVE, statusOf(comment));
    }

    @Test
    @DisplayName("Autor não denuncia o próprio comentário (400 SELF_REPORT_FORBIDDEN)")
    void selfReportIsForbidden() {
        BusinessException ex = assertThrows(BusinessException.class, () -> report(commenter, comment));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("SELF_REPORT_FORBIDDEN", ex.getErrorCode());
        assertEquals(0, reportCount(comment));
    }

    @Test
    @DisplayName("Denúncia repetida não cria nova linha nem conta duas vezes")
    void duplicateReportCountsOnce() {
        User reporter = createUser("repetido");
        ReportDiscussionResult first = report(reporter, comment);
        ReportDiscussionResult second = report(reporter, comment);
        ReportDiscussionResult third = report(reporter, comment);

        assertTrue(first.newlyCreated());
        assertFalse(second.newlyCreated());
        assertFalse(third.newlyCreated());
        assertEquals(first.reportId(), second.reportId());
        assertEquals(1, reportCount(comment));
        assertEquals(DiscussionStatus.ACTIVE, statusOf(comment));
    }

    @Test
    @DisplayName("Terceira denúncia PENDING de usuários distintos coloca o comentário em UNDER_REVIEW, sem outros efeitos")
    void thirdDistinctReportQuarantines() {
        long notificationsBefore = count("SELECT count(*) FROM notifications");
        long outboxBefore = count("SELECT count(*) FROM outbox_messages");

        report(createUser("d1"), comment);
        report(createUser("d2"), comment);
        assertEquals(DiscussionStatus.ACTIVE, statusOf(comment));

        report(createUser("d3"), comment);

        assertEquals(DiscussionStatus.UNDER_REVIEW, statusOf(comment));
        assertEquals(3, count("SELECT count(*) FROM discussion_reports WHERE discussion_id = ? AND status = 'PENDING'", comment.getId()));
        // Sem notificação, push, reputação ou estatística: só o status do comentário muda
        assertEquals(notificationsBefore, count("SELECT count(*) FROM notifications"));
        assertEquals(outboxBefore, count("SELECT count(*) FROM outbox_messages"));
        assertEquals(0, count("SELECT count(*) FROM user_reputation WHERE user_id = ?", commenter.getId()));
        assertEquals(ReviewStatus.ACTIVE.name(), jdbcTemplate.queryForObject(
                "SELECT status FROM reviews WHERE id = ?", String.class, review.getId()));
    }

    @Test
    @DisplayName("Depois da quarentena: novo denunciante recebe 404; quem já denunciou recebe a mesma confirmação")
    void afterQuarantineNewReportersSeeNotFoundAndOldOnesTheSameReceipt() {
        User first = createUser("q1");
        report(first, comment);
        report(createUser("q2"), comment);
        report(createUser("q3"), comment);

        assertDiscussionNotFound(() -> report(createUser("q4"), comment));
        ReportDiscussionResult repeated = report(first, comment);
        assertFalse(repeated.newlyCreated());
        assertEquals(3, reportCount(comment));
        assertEquals(DiscussionStatus.UNDER_REVIEW, statusOf(comment));
    }

    @Test
    @DisplayName("Quarentena não reverte REMOVED nem repete UNDER_REVIEW: comentário removido não aceita denúncia")
    void removedCommentIsNotReportable() {
        jdbcTemplate.update("UPDATE review_discussions SET status = 'REMOVED' WHERE id = ?", comment.getId());

        assertDiscussionNotFound(() -> report(createUser("r1"), comment));
        assertEquals(DiscussionStatus.REMOVED, statusOf(comment));
        assertEquals(0, reportCount(comment));
    }

    @Test
    @DisplayName("Concorrência: 6 denúncias simultâneas de usuários distintos quarentenam exatamente na terceira")
    void concurrentReportsQuarantineExactlyOnce() throws Exception {
        int reporters = 6;
        List<User> users = new ArrayList<>();
        for (int i = 0; i < reporters; i++) {
            users.add(createUser("c" + i));
        }
        ExecutorService executor = Executors.newFixedThreadPool(reporters);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (User user : users) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        report(user, comment);
                        return true;
                    } catch (BusinessException e) {
                        assertEquals("DISCUSSION_NOT_FOUND", e.getErrorCode());
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertEquals(ReportDiscussionUseCase.QUARANTINE_THRESHOLD, accepted);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(DiscussionStatus.UNDER_REVIEW, statusOf(comment));
        assertEquals(ReportDiscussionUseCase.QUARANTINE_THRESHOLD, reportCount(comment));
    }

    @Test
    @DisplayName("Resposta numa conversa em quarentena fica inacessível; resposta a comentário removido segue denunciável")
    void replyAccessFollowsThreadVisibility() {
        ReviewDiscussion quarantinedRoot = createDiscussion(review, commenter.getId(), null);
        ReviewDiscussion hiddenReply = createDiscussion(review, createUser("resp1").getId(), quarantinedRoot.getId());
        jdbcTemplate.update("UPDATE review_discussions SET status = 'UNDER_REVIEW' WHERE id = ?", quarantinedRoot.getId());
        assertDiscussionNotFound(() -> report(createUser("x1"), hiddenReply));

        ReviewDiscussion removedRoot = createDiscussion(review, commenter.getId(), null);
        ReviewDiscussion visibleReply = createDiscussion(review, createUser("resp2").getId(), removedRoot.getId());
        jdbcTemplate.update("UPDATE review_discussions SET status = 'REMOVED' WHERE id = ?", removedRoot.getId());
        assertTrue(report(createUser("x2"), visibleReply).newlyCreated());
    }

    @Test
    @DisplayName("Avaliação inacessível: privada (403), removida (404) e discussão inexistente (404)")
    void inaccessibleReviewOrDiscussion() {
        Review privateReview = createReview(reviewAuthor.getId(), "PRIVATE");
        ReviewDiscussion privateComment = createDiscussion(privateReview, reviewAuthor.getId(), null);
        BusinessException forbidden = assertThrows(BusinessException.class, () -> report(createUser("p1"), privateComment));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatus());

        jdbcTemplate.update("UPDATE reviews SET status = 'REMOVED' WHERE id = ?", review.getId());
        BusinessException removed = assertThrows(BusinessException.class, () -> report(createUser("p2"), comment));
        assertEquals("REVIEW_NOT_FOUND", removed.getErrorCode());

        assertDiscussionNotFound(() -> reportDiscussionUseCase.execute(new ReportDiscussionCommand(
                createUser("p3").getId(), UUID.randomUUID(), ReportReason.SPAM, null)));
    }

    @Test
    @DisplayName("Conta inativa não denuncia (401 ACCOUNT_DISABLED)")
    void inactiveReporterIsRejected() {
        User inactive = createUser("inativo");
        jdbcTemplate.update("UPDATE users SET is_active = FALSE WHERE id = ?", inactive.getId());

        BusinessException ex = assertThrows(BusinessException.class, () -> report(inactive, comment));
        assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
        assertEquals(0, reportCount(comment));
    }

    @Test
    @DisplayName("Schema (V19): unicidade por denunciante, motivo e status válidos")
    void schemaConstraints() {
        User reporter = createUser("schema");
        report(reporter, comment);

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO discussion_reports (discussion_id, reporter_user_id, reason) VALUES (?, ?, 'SPAM')",
                comment.getId(), reporter.getId()));
        UUID other = createUser("schema2").getId();
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO discussion_reports (discussion_id, reporter_user_id, reason) VALUES (?, ?, 'INVALIDO')",
                comment.getId(), other));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO discussion_reports (discussion_id, reporter_user_id, reason, status) VALUES (?, ?, 'SPAM', 'OUTRO')",
                comment.getId(), other));
        assertEquals(1, count("SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_discussion_reports_pending_queue'"));
    }

    private ReportDiscussionResult report(User reporter, ReviewDiscussion discussion) {
        return reportDiscussionUseCase.execute(new ReportDiscussionCommand(
                reporter.getId(), discussion.getId(), ReportReason.HARASSMENT, "Detalhe da denúncia"));
    }

    private static void assertDiscussionNotFound(Executable executable) {
        BusinessException ex = assertThrows(BusinessException.class, executable);
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("DISCUSSION_NOT_FOUND", ex.getErrorCode());
    }

    private DiscussionStatus statusOf(ReviewDiscussion discussion) {
        return DiscussionStatus.valueOf(jdbcTemplate.queryForObject(
                "SELECT status FROM review_discussions WHERE id = ?", String.class, discussion.getId()));
    }

    private long reportCount(ReviewDiscussion discussion) {
        return count("SELECT count(*) FROM discussion_reports WHERE discussion_id = ?", discussion.getId());
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private User createUser(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.save(new User(null, prefix + "." + suffix + "@rewit.test", "hash", AuthProvider.LOCAL, null));
        profileRepository.save(new Profile(null, user.getId(), "dr_" + suffix, "Conta " + prefix, null, null));
        return user;
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return placeRepository.save(new Place(null, "Lugar Denúncia " + suffix, "lugar-denuncia-" + suffix, "RESTAURANTE",
                "Descrição", "Rua Teste, 100", "100", "Centro", "São Paulo", "SP", "BR",
                -23.5505, -46.6333, 50, "USER", false, null, "ACTIVE"));
    }

    private Review createReview(UUID authorId, String visibility) {
        return reviewRepository.save(new Review(null, authorId, place.getId(), "Avaliação com discussões denunciadas",
                false, false, ReviewStatus.ACTIVE, visibility, null, null, null, Instant.now(), Instant.now()));
    }

    private ReviewDiscussion createDiscussion(Review target, UUID authorId, UUID parentId) {
        return discussionRepository.save(new ReviewDiscussion(null, target.getId(), authorId, parentId,
                "Comentário de teste", authorId.equals(target.getUserId())));
    }
}
