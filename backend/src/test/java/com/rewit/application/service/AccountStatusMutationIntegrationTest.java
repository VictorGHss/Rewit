package com.rewit.application.service;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.UpdateReviewCommand;
import com.rewit.application.dto.discussion.DiscussionDtos.CreateDiscussionCommand;
import com.rewit.application.dto.media.MediaDtos.UploadMediaCommand;
import com.rewit.application.dto.report.ReportDtos.CreateReportCommand;
import com.rewit.application.dto.report.ReportDtos.ModerateReviewCommand;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.usecase.DeleteReviewUseCase;
import com.rewit.application.usecase.ModerateReviewUseCase;
import com.rewit.application.usecase.UpdateReviewUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.ModerationAction;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.Review;
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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Estado da conta nas mutações, com beans reais e PostgreSQL real: uma conta inativa ou excluída não inicia
 * nenhuma mutação de autoria, e a rejeição acontece antes de qualquer efeito.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Estado da conta nas mutações: PostgreSQL real")
class AccountStatusMutationIntegrationTest {

    private enum AccountState { INACTIVE, SOFT_DELETED }

    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    @Autowired private ReviewService reviewService;
    @Autowired private UpdateReviewUseCase updateReviewUseCase;
    @Autowired private DeleteReviewUseCase deleteReviewUseCase;
    @Autowired private ModerateReviewUseCase moderateReviewUseCase;
    @Autowired private ReviewHelpfulService reviewHelpfulService;
    @Autowired private DiscussionService discussionService;
    @Autowired private ReportService reportService;
    @Autowired private ReviewMediaService reviewMediaService;
    @Autowired private UserFollowService userFollowService;

    private User author;
    private Place place;
    private Review authorReview;

    @BeforeEach
    void setUp() {
        author = createUser("autor");
        place = createPlace();
        authorReview = createReview(author.getId());
    }

    @Test
    @DisplayName("Conta ativa: as mesmas mutações seguem permitidas")
    void activeAccountKeepsMutating() {
        User actor = createUser("ativa");
        Review own = createReview(actor.getId());

        reviewHelpfulService.addHelpful(authorReview.getId(), actor.getId());
        reviewHelpfulService.removeHelpful(authorReview.getId(), actor.getId());
        assertNotNull(discussionService.createDiscussion(
                new CreateDiscussionCommand(authorReview.getId(), actor.getId(), null, "Comentário de conta ativa")));
        assertTrue(reportService.createReport(
                new CreateReportCommand(actor.getId(), authorReview.getId(), ReportReason.SPAM, null)).newlyCreated());
        assertTrue(userFollowService.followUser(actor.getId(), author.getId()));
        assertTrue(userFollowService.unfollowUser(actor.getId(), author.getId()));
        updateReviewUseCase.execute(own.getId(), actor.getId(),
                new UpdateReviewCommand("Texto editado por conta ativa", Map.of(), null, null, Instant.now()));
        deleteReviewUseCase.execute(own.getId(), actor.getId(), Instant.now());

        assertEquals("REMOVED", jdbcTemplate.queryForObject("SELECT status FROM reviews WHERE id = ?", String.class, own.getId()));
    }

    @Test
    @DisplayName("Conta inativa: toda mutação de autoria é rejeitada com 401 ACCOUNT_DISABLED e sem efeito")
    void inactiveAccountCannotMutate() {
        assertEveryMutationRejected(AccountState.INACTIVE);
    }

    @Test
    @DisplayName("Conta soft-deleted: toda mutação de autoria é rejeitada com 401 ACCOUNT_DISABLED e sem efeito")
    void softDeletedAccountCannotMutate() {
        assertEveryMutationRejected(AccountState.SOFT_DELETED);
    }

    @Test
    @DisplayName("Criação de review preserva o contrato existente: 403 USER_INACTIVE (inativa) e 404 USER_NOT_FOUND (excluída)")
    void createReviewKeepsItsExistingContract() {
        User inactive = createUser("criar_inativa");
        User deleted = createUser("criar_excluida");
        setState(inactive, AccountState.INACTIVE);
        setState(deleted, AccountState.SOFT_DELETED);

        BusinessException inactiveEx = assertThrows(BusinessException.class,
                () -> reviewService.createReview(createReviewCommand(inactive.getId())));
        assertEquals(HttpStatus.FORBIDDEN, inactiveEx.getStatus());
        assertEquals("USER_INACTIVE", inactiveEx.getErrorCode());

        BusinessException deletedEx = assertThrows(BusinessException.class,
                () -> reviewService.createReview(createReviewCommand(deleted.getId())));
        assertEquals(HttpStatus.NOT_FOUND, deletedEx.getStatus());
        assertEquals("USER_NOT_FOUND", deletedEx.getErrorCode());
        assertEquals(0, count("SELECT count(*) FROM reviews WHERE user_id IN (?, ?)", inactive.getId(), deleted.getId()));
    }

    @Test
    @DisplayName("Concorrência: desativação não confirmada não bloqueia a mutação; depois de confirmada, a próxima é rejeitada")
    void concurrentDeactivationIsOrderedWithoutBlocking() throws Exception {
        User actor = createUser("concorrente");
        Review second = createReview(author.getId());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection deactivation = dataSource.getConnection()) {
            deactivation.setAutoCommit(false);
            try (PreparedStatement ps = deactivation.prepareStatement("UPDATE users SET account_status = 'SUSPENDED', is_active = FALSE WHERE id = ?")) {
                ps.setObject(1, actor.getId());
                assertEquals(1, ps.executeUpdate());
            }

            // A linha do usuário está travada pela desativação em curso. O guard lê sem lock e o insert da reação
            // pega só FOR KEY SHARE na linha (compatível com o UPDATE), então a mutação termina sem esperar:
            // ela é ordenada antes da desativação.
            Future<?> mutation = executor.submit(() -> reviewHelpfulService.addHelpful(authorReview.getId(), actor.getId()));
            mutation.get(10, TimeUnit.SECONDS);

            deactivation.commit();
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count("SELECT count(*) FROM review_reactions WHERE review_id = ? AND user_id = ?",
                authorReview.getId(), actor.getId()));

        assertAccountDisabled(() -> reviewHelpfulService.addHelpful(second.getId(), actor.getId()));
        assertEquals(0, count("SELECT count(*) FROM review_reactions WHERE review_id = ? AND user_id = ?",
                second.getId(), actor.getId()));
    }

    @Test
    @DisplayName("Schema (V17): is_active = TRUE com deleted_at preenchido não pode ser persistido")
    void inconsistentAccountStateIsRejectedBySchema() {
        UUID id = UUID.randomUUID();
        DataIntegrityViolationException insert = assertThrows(DataIntegrityViolationException.class, () ->
                jdbcTemplate.update("INSERT INTO users (id, email, auth_provider, is_active, deleted_at) "
                        + "VALUES (?, ?, 'LOCAL', TRUE, now())", id, "inconsistente." + id + "@rewit.test"));
        assertTrue(insert.getMessage().contains("chk_users_active_not_deleted"));

        User deleted = createUser("schema");
        setState(deleted, AccountState.SOFT_DELETED);
        DataIntegrityViolationException update = assertThrows(DataIntegrityViolationException.class, () ->
                jdbcTemplate.update("UPDATE users SET is_active = TRUE WHERE id = ?", deleted.getId()));
        assertTrue(update.getMessage().contains("chk_users_active_not_deleted"));

        // Todo estado do ciclo de vida (V21) com is_active/deleted_at coerentes é aceito
        User valid = createUser("schema_validos");
        jdbcTemplate.update("UPDATE users SET account_status = 'DEACTIVATED', is_active = FALSE WHERE id = ?", valid.getId());
        jdbcTemplate.update("UPDATE users SET account_status = 'SUSPENDED' WHERE id = ?", valid.getId());
        jdbcTemplate.update("UPDATE users SET account_status = 'DELETED', deleted_at = now() WHERE id = ?", valid.getId());
        jdbcTemplate.update("UPDATE users SET account_status = 'ACTIVE', deleted_at = NULL, is_active = TRUE WHERE id = ?",
                valid.getId());
        assertEquals(1, count("SELECT count(*) FROM pg_constraint WHERE conname = 'chk_users_active_not_deleted' "
                + "AND conrelid = 'users'::regclass AND convalidated"));
    }

    @Test
    @DisplayName("Schema (V21): is_active e deleted_at não divergem de account_status, que só aceita os quatro estados")
    void legacyColumnsCannotDivergeFromAccountStatus() {
        User user = createUser("schema_v21");
        UUID id = user.getId();

        // Escrita só na representação anterior: sem account_status correspondente, rejeitada
        assertConstraintViolated("chk_users_status_consistency", "UPDATE users SET is_active = FALSE WHERE id = ?", id);
        assertConstraintViolated("chk_users_status_consistency",
                "UPDATE users SET account_status = 'SUSPENDED' WHERE id = ?", id);
        assertConstraintViolated("chk_users_status_consistency",
                "UPDATE users SET account_status = 'DELETED', is_active = FALSE WHERE id = ?", id);
        assertConstraintViolated("chk_users_status_consistency",
                "UPDATE users SET account_status = 'DEACTIVATED', is_active = FALSE, deleted_at = now() WHERE id = ?", id);
        assertConstraintViolated("chk_users_account_status",
                "UPDATE users SET account_status = 'BANNED', is_active = FALSE WHERE id = ?", id);

        assertEquals("ACTIVE", jdbcTemplate.queryForObject("SELECT account_status FROM users WHERE id = ?", String.class, id));
        assertEquals(2, count("SELECT count(*) FROM pg_constraint WHERE conname IN "
                + "('chk_users_account_status', 'chk_users_status_consistency') AND conrelid = 'users'::regclass AND convalidated"));
    }

    private void assertConstraintViolated(String constraint, String sql, Object... args) {
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(sql, args));
        assertTrue(ex.getMessage().contains(constraint), ex.getMessage());
    }

    private void assertEveryMutationRejected(AccountState state) {
        User actor = createUser(state.name().toLowerCase());
        Review own = createReview(actor.getId());
        // Efeitos criados enquanto a conta ainda estava ativa, para testar as remoções
        reviewHelpfulService.addHelpful(authorReview.getId(), actor.getId());
        assertTrue(userFollowService.followUser(actor.getId(), author.getId()));
        UUID discussionId = discussionService.createDiscussion(
                new CreateDiscussionCommand(authorReview.getId(), actor.getId(), null, "Comentário ainda ativo")).id();

        setState(actor, state);
        UUID actorId = actor.getId();

        assertAccountDisabled(() -> reviewHelpfulService.addHelpful(createReview(author.getId()).getId(), actorId));
        assertAccountDisabled(() -> reviewHelpfulService.removeHelpful(authorReview.getId(), actorId));
        assertAccountDisabled(() -> discussionService.createDiscussion(
                new CreateDiscussionCommand(authorReview.getId(), actorId, null, "Comentário após desativação")));
        assertAccountDisabled(() -> discussionService.deleteDiscussion(discussionId, actorId));
        assertAccountDisabled(() -> reportService.createReport(
                new CreateReportCommand(actorId, authorReview.getId(), ReportReason.SPAM, null)));
        assertAccountDisabled(() -> reviewMediaService.uploadMedia(
                new UploadMediaCommand(own.getId(), actorId, new byte[]{1, 2, 3}, "foto.jpg")));
        // Identificador de mídia inexistente: o 401 vem antes de qualquer busca (seria 404)
        assertAccountDisabled(() -> reviewMediaService.deleteMedia(own.getId(), UUID.randomUUID(), actorId));
        assertAccountDisabled(() -> userFollowService.followUser(actorId, createUser("alvo").getId()));
        assertAccountDisabled(() -> userFollowService.unfollowUser(actorId, author.getId()));
        assertAccountDisabled(() -> updateReviewUseCase.execute(own.getId(), actorId,
                new UpdateReviewCommand("Edição após desativação", Map.of(), null, null, Instant.now())));
        assertAccountDisabled(() -> deleteReviewUseCase.execute(own.getId(), actorId, Instant.now()));
        assertAccountDisabled(() -> moderateReviewUseCase.execute(new ModerateReviewCommand(authorReview.getId(), actorId,
                ModerationAction.REMOVE_REVIEW, "SPAM", "Justificativa com mais de quinze caracteres", Instant.now())));

        // Nada mudou: reação e follow anteriores continuam, nada novo foi criado, reviews intactas
        assertEquals(1, count("SELECT count(*) FROM review_reactions WHERE user_id = ?", actorId));
        assertEquals(1, count("SELECT count(*) FROM user_follows WHERE follower_user_id = ?", actorId));
        assertEquals(1, count("SELECT count(*) FROM review_discussions WHERE user_id = ? AND status = 'ACTIVE'", actorId));
        assertEquals(0, count("SELECT count(*) FROM review_reports WHERE reporter_user_id = ?", actorId));
        assertEquals(0, count("SELECT count(*) FROM review_media WHERE user_id = ?", actorId));
        assertEquals(0, count("SELECT count(*) FROM moderation_audit_logs WHERE moderator_user_id = ?", actorId));
        assertEquals(List.of("ACTIVE", "ACTIVE"), jdbcTemplate.queryForList(
                "SELECT status FROM reviews WHERE id IN (?, ?)", String.class, own.getId(), authorReview.getId()));
        assertEquals("Avaliação de teste do estado da conta", jdbcTemplate.queryForObject(
                "SELECT experience_text FROM reviews WHERE id = ?", String.class, own.getId()));
    }

    private static void assertAccountDisabled(Executable mutation) {
        BusinessException ex = assertThrows(BusinessException.class, mutation);
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
    }

    private void setState(User user, AccountState state) {
        String sql = state == AccountState.INACTIVE
                ? "UPDATE users SET account_status = 'SUSPENDED', is_active = FALSE WHERE id = ?"
                : "UPDATE users SET account_status = 'DELETED', is_active = FALSE, deleted_at = now() WHERE id = ?";
        assertEquals(1, jdbcTemplate.update(sql, user.getId()));
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private User createUser(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.save(new User(null, prefix + "." + suffix + "@rewit.test", "hash", AuthProvider.LOCAL, null));
        profileRepository.save(new Profile(null, user.getId(), "acc_" + suffix, "Conta " + prefix, null, null));
        return user;
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return placeRepository.save(new Place(null, "Lugar Conta " + suffix, "lugar-conta-" + suffix, "RESTAURANTE",
                "Descrição", "Rua Teste, 100", "100", "Centro", "São Paulo", "SP", "BR",
                -23.5505, -46.6333, 50, "USER", false, null, "ACTIVE"));
    }

    private Review createReview(UUID authorId) {
        return reviewRepository.save(new Review(null, authorId, place.getId(), "Avaliação de teste do estado da conta",
                false, false, ReviewStatus.ACTIVE, "PUBLIC", null, null, null, Instant.now(), Instant.now()));
    }

    private CreateReviewCommand createReviewCommand(UUID authorId) {
        return new CreateReviewCommand(authorId, place.getId(), "Nova avaliação", false, "PUBLIC", null, null, null,
                List.of());
    }
}
