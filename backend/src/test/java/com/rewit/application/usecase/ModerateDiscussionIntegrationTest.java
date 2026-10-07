package com.rewit.application.usecase;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.AdminDiscussionContextView;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.AdminDiscussionReportView;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.ModerateDiscussionCommand;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.ModerateDiscussionResult;
import com.rewit.application.dto.discussion.DiscussionReportDtos.ReportDiscussionCommand;
import com.rewit.application.port.DiscussionRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.DiscussionService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.DiscussionModerationAction;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.enums.ModerationDecision;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
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
import org.springframework.dao.DataAccessException;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Moderação administrativa de discussões com beans reais e PostgreSQL real. A quarentena é preparada pelo
 * caminho real: três denúncias pelo {@link ReportDiscussionUseCase}.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Moderação de discussões: PostgreSQL real")
class ModerateDiscussionIntegrationTest {

    private static final String JUSTIFICATION = "Conteúdo analisado conforme as diretrizes da comunidade";

    @Autowired private ModerateDiscussionUseCase moderateDiscussionUseCase;
    @Autowired private ReportDiscussionUseCase reportDiscussionUseCase;
    @Autowired private QueryAdminDiscussionReportsUseCase queryAdminDiscussionReportsUseCase;
    @Autowired private GetAdminDiscussionContextUseCase getAdminDiscussionContextUseCase;
    @Autowired private DiscussionService discussionService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private DiscussionRepository discussionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User commenter;
    private User moderator;
    private Review review;
    private List<User> reporters;

    @BeforeEach
    void setUp() {
        commenter = createUser("comentarista");
        moderator = createUser("moderador");
        review = createReview(createUser("autor").getId());
        reporters = List.of(createUser("d1"), createUser("d2"), createUser("d3"));
    }

    @Test
    @DisplayName("Remover: UNDER_REVIEW -> REMOVED, denúncias ACCEPTED e auditoria append-only completa")
    void removeResolvesReportsAsAccepted() {
        ReviewDiscussion comment = quarantinedComment();

        ModerateDiscussionResult result = moderate(comment, moderator, DiscussionModerationAction.REMOVE_DISCUSSION);

        assertEquals(DiscussionStatus.REMOVED, result.discussion().getStatus());
        assertEquals(DiscussionStatus.REMOVED, statusOf(comment));
        assertEquals(3, count("SELECT count(*) FROM discussion_reports WHERE discussion_id = ? AND status = 'ACCEPTED'", comment.getId()));
        assertEquals(0, count("SELECT count(*) FROM discussion_reports WHERE discussion_id = ? AND status = 'PENDING'", comment.getId()));

        assertEquals(DiscussionModerationAction.REMOVE_DISCUSSION, result.auditLog().getAction());
        assertEquals(ModerationDecision.ACCEPTED, result.auditLog().getDecision());
        assertEquals(DiscussionStatus.UNDER_REVIEW, result.auditLog().getPreviousStatus());
        assertEquals(DiscussionStatus.REMOVED, result.auditLog().getNewStatus());
        assertEquals(3, result.auditLog().getReportsAffectedCount());
        assertEquals(1, count("SELECT count(*) FROM discussion_moderation_audit_logs WHERE discussion_id = ? "
                + "AND moderator_user_id = ? AND action = 'REMOVE_DISCUSSION' AND new_status = 'REMOVED'", comment.getId(), moderator.getId()));
    }

    @Test
    @DisplayName("Restaurar: UNDER_REVIEW -> ACTIVE e denúncias REJECTED; nova quarentena exige três novas denúncias")
    void restoreResolvesReportsAsRejected() {
        ReviewDiscussion comment = quarantinedComment();

        ModerateDiscussionResult result = moderate(comment, moderator, DiscussionModerationAction.RESTORE_DISCUSSION);

        assertEquals(DiscussionStatus.ACTIVE, statusOf(comment));
        assertEquals(ModerationDecision.REJECTED, result.auditLog().getDecision());
        assertEquals(3, count("SELECT count(*) FROM discussion_reports WHERE discussion_id = ? AND status = 'REJECTED'", comment.getId()));

        // Denúncias resolvidas não contam: uma nova denúncia não quarentena de novo
        report(createUser("nova"), comment);
        assertEquals(DiscussionStatus.ACTIVE, statusOf(comment));
    }

    @Test
    @DisplayName("Somente UNDER_REVIEW é moderado: ACTIVE e REMOVED respondem 409")
    void onlyUnderReviewIsModerated() {
        ReviewDiscussion active = createComment();
        assertConflict(() -> moderate(active, moderator, DiscussionModerationAction.REMOVE_DISCUSSION));

        ReviewDiscussion removed = quarantinedComment();
        moderate(removed, moderator, DiscussionModerationAction.REMOVE_DISCUSSION);
        assertConflict(() -> moderate(removed, createUser("outro_mod"), DiscussionModerationAction.RESTORE_DISCUSSION));
        assertEquals(1, count("SELECT count(*) FROM discussion_moderation_audit_logs WHERE discussion_id = ?", removed.getId()));
    }

    @Test
    @DisplayName("Conflito de interesse: autor do comentário e quem o denunciou não moderam (403)")
    void conflictsOfInterestAreForbidden() {
        ReviewDiscussion comment = quarantinedComment();

        BusinessException self = assertThrows(BusinessException.class,
                () -> moderate(comment, commenter, DiscussionModerationAction.RESTORE_DISCUSSION));
        assertEquals(HttpStatus.FORBIDDEN, self.getStatus());
        assertEquals("SELF_MODERATION_FORBIDDEN", self.getErrorCode());

        BusinessException reporter = assertThrows(BusinessException.class,
                () -> moderate(comment, reporters.get(0), DiscussionModerationAction.REMOVE_DISCUSSION));
        assertEquals("REPORTER_CANNOT_MODERATE", reporter.getErrorCode());
        assertEquals(DiscussionStatus.UNDER_REVIEW, statusOf(comment));
    }

    @Test
    @DisplayName("Moderador com conta inativa não modera (401 ACCOUNT_DISABLED)")
    void inactiveModeratorIsRejected() {
        ReviewDiscussion comment = quarantinedComment();
        jdbcTemplate.update("UPDATE users SET account_status = 'SUSPENDED', is_active = FALSE WHERE id = ?", moderator.getId());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> moderate(comment, moderator, DiscussionModerationAction.REMOVE_DISCUSSION));
        assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
        assertEquals(DiscussionStatus.UNDER_REVIEW, statusOf(comment));
    }

    @Test
    @DisplayName("Concorrência: duas moderações simultâneas do mesmo comentário, exatamente uma vence; a outra recebe 409")
    void concurrentModerationsAreSerialized() throws Exception {
        ReviewDiscussion comment = quarantinedComment();
        User secondModerator = createUser("mod2");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> results = new ArrayList<>();
            results.add(executor.submit(() -> attempt(start, comment, moderator, DiscussionModerationAction.REMOVE_DISCUSSION)));
            results.add(executor.submit(() -> attempt(start, comment, secondModerator, DiscussionModerationAction.RESTORE_DISCUSSION)));
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, outcomes.stream().filter("OK"::equals).count(), outcomes.toString());
            assertEquals(1, outcomes.stream().filter("DISCUSSION_NOT_UNDER_REVIEW"::equals).count(), outcomes.toString());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count("SELECT count(*) FROM discussion_moderation_audit_logs WHERE discussion_id = ?", comment.getId()));
        assertEquals(0, count("SELECT count(*) FROM discussion_reports WHERE discussion_id = ? AND status = 'PENDING'", comment.getId()));
    }

    @Test
    @DisplayName("Fila administrativa e contexto: filtros, contexto da discussão, denúncias e histórico de auditoria")
    void adminQueueAndContext() {
        ReviewDiscussion comment = quarantinedComment();

        PageResult<AdminDiscussionReportView> pending = queryAdminDiscussionReportsUseCase.execute(
                0, 50, ReportStatus.PENDING, null, comment.getId(), "asc");
        assertEquals(3, pending.totalElements());
        AdminDiscussionReportView first = pending.content().get(0);
        assertEquals(review.getId(), first.reviewId());
        assertEquals(commenter.getId(), first.discussionAuthorUserId());
        assertEquals(DiscussionStatus.UNDER_REVIEW, first.discussionStatus());

        moderate(comment, moderator, DiscussionModerationAction.REMOVE_DISCUSSION);
        AdminDiscussionContextView context = getAdminDiscussionContextUseCase.execute(comment.getId());
        assertEquals(DiscussionStatus.REMOVED, context.discussion().status());
        assertEquals("Comentário moderável", context.discussion().content(), "moderação vê o conteúdo mesmo removido");
        assertNull(context.parent());
        assertEquals(0, context.pendingReportCount());
        assertEquals(3, context.reports().size());
        assertEquals(1, context.auditHistory().size());
        assertTrue(queryAdminDiscussionReportsUseCase.execute(0, 50, ReportStatus.PENDING, null, comment.getId(), "asc")
                .content().isEmpty());
    }

    @Test
    @DisplayName("Autor não exclui comentário em análise; as denúncias seguem PENDING e a moderação conclui o ciclo")
    void authorCannotDeleteUnderReviewAndModerationStillCompletes() {
        ReviewDiscussion comment = quarantinedComment();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> discussionService.deleteDiscussion(comment.getId(), commenter.getId()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("DISCUSSION_UNDER_REVIEW_MUTATION_DENIED", ex.getErrorCode());
        assertEquals(DiscussionStatus.UNDER_REVIEW, statusOf(comment));
        assertEquals(3, count("SELECT count(*) FROM discussion_reports WHERE discussion_id = ? AND status = 'PENDING'", comment.getId()));
        assertEquals(3, queryAdminDiscussionReportsUseCase.execute(0, 50, ReportStatus.PENDING, null, comment.getId(), "asc")
                .totalElements());

        moderate(comment, moderator, DiscussionModerationAction.RESTORE_DISCUSSION);
        assertEquals(DiscussionStatus.ACTIVE, statusOf(comment));
        assertEquals(3, count("SELECT count(*) FROM discussion_reports WHERE discussion_id = ? AND status = 'REJECTED'", comment.getId()));

        // De volta a ACTIVE, o autor pode excluir normalmente
        discussionService.deleteDiscussion(comment.getId(), commenter.getId());
        assertEquals(DiscussionStatus.REMOVED, statusOf(comment));
    }

    @Test
    @DisplayName("Concorrência: exclusão pelo autor durante a moderação termina consistente em qualquer ordem")
    void concurrentAuthorDeleteAndModerationStayConsistent() throws Exception {
        ReviewDiscussion comment = quarantinedComment();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> moderation = executor.submit(
                    () -> attempt(start, comment, moderator, DiscussionModerationAction.REMOVE_DISCUSSION));
            Future<String> deletion = executor.submit(() -> {
                start.await();
                try {
                    discussionService.deleteDiscussion(comment.getId(), commenter.getId());
                    return "OK";
                } catch (BusinessException e) {
                    return e.getErrorCode();
                }
            });
            start.countDown();

            // A moderação sempre conclui; a exclusão ou chega antes (409, ainda em análise) ou depois (no-op em REMOVED)
            assertEquals("OK", moderation.get(30, TimeUnit.SECONDS));
            String deletionOutcome = deletion.get(30, TimeUnit.SECONDS);
            assertTrue(deletionOutcome.equals("OK") || deletionOutcome.equals("DISCUSSION_UNDER_REVIEW_MUTATION_DENIED"),
                    deletionOutcome);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(DiscussionStatus.REMOVED, statusOf(comment));
        assertEquals(0, count("SELECT count(*) FROM discussion_reports WHERE discussion_id = ? AND status = 'PENDING'", comment.getId()));
        assertEquals(3, count("SELECT count(*) FROM discussion_reports WHERE discussion_id = ? AND status = 'ACCEPTED'", comment.getId()));
        assertEquals(1, count("SELECT count(*) FROM discussion_moderation_audit_logs WHERE discussion_id = ?", comment.getId()));
    }

    @Test
    @DisplayName("Schema (V20): auditoria rejeita UPDATE (append-only) e valores inválidos")
    void auditSchemaIsAppendOnly() {
        ReviewDiscussion comment = quarantinedComment();
        moderate(comment, moderator, DiscussionModerationAction.REMOVE_DISCUSSION);

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE discussion_moderation_audit_logs SET justification = 'adulterada' WHERE discussion_id = ?", comment.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO discussion_moderation_audit_logs (discussion_id, moderator_user_id, action, decision, reason_code, "
                        + "justification, previous_status, new_status) VALUES (?, ?, 'DELETE_ALL', 'ACCEPTED', 'X', 'Justificativa', 'UNDER_REVIEW', 'REMOVED')",
                comment.getId(), moderator.getId()));
        assertEquals(JUSTIFICATION, jdbcTemplate.queryForObject(
                "SELECT justification FROM discussion_moderation_audit_logs WHERE discussion_id = ?", String.class, comment.getId()));
    }

    private String attempt(CountDownLatch start, ReviewDiscussion comment, User mod, DiscussionModerationAction action) throws InterruptedException {
        start.await();
        try {
            moderate(comment, mod, action);
            return "OK";
        } catch (BusinessException e) {
            return e.getErrorCode();
        }
    }

    private ModerateDiscussionResult moderate(ReviewDiscussion comment, User mod, DiscussionModerationAction action) {
        return moderateDiscussionUseCase.execute(new ModerateDiscussionCommand(
                comment.getId(), mod.getId(), action, "COMMUNITY_GUIDELINES", JUSTIFICATION, Instant.now()));
    }

    private static void assertConflict(Executable executable) {
        BusinessException ex = assertThrows(BusinessException.class, executable);
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("DISCUSSION_NOT_UNDER_REVIEW", ex.getErrorCode());
    }

    private ReviewDiscussion quarantinedComment() {
        ReviewDiscussion comment = createComment();
        reporters.forEach(reporter -> report(reporter, comment));
        assertEquals(DiscussionStatus.UNDER_REVIEW, statusOf(comment));
        return comment;
    }

    private void report(User reporter, ReviewDiscussion comment) {
        reportDiscussionUseCase.execute(new ReportDiscussionCommand(reporter.getId(), comment.getId(), ReportReason.SPAM, null));
    }

    private ReviewDiscussion createComment() {
        return discussionRepository.save(new ReviewDiscussion(null, review.getId(), commenter.getId(), null,
                "Comentário moderável", false));
    }

    private DiscussionStatus statusOf(ReviewDiscussion discussion) {
        return DiscussionStatus.valueOf(jdbcTemplate.queryForObject(
                "SELECT status FROM review_discussions WHERE id = ?", String.class, discussion.getId()));
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private User createUser(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.save(new User(null, prefix + "." + suffix + "@rewit.test", "hash", AuthProvider.LOCAL, null));
        profileRepository.save(new Profile(null, user.getId(), "md_" + suffix, "Conta " + prefix, null, null));
        return user;
    }

    private Review createReview(UUID authorId) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = placeRepository.save(new Place(null, "Lugar Moderação " + suffix, "lugar-moderacao-" + suffix, "RESTAURANTE",
                "Descrição", "Rua Teste, 100", "100", "Centro", "São Paulo", "SP", "BR",
                -23.5505, -46.6333, 50, "USER", false, null, "ACTIVE"));
        return reviewRepository.save(new Review(null, authorId, place.getId(), "Avaliação com comentário moderado",
                false, false, ReviewStatus.ACTIVE, "PUBLIC", null, null, null, Instant.now(), Instant.now()));
    }
}
