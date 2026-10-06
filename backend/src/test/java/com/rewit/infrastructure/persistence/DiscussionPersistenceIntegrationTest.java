package com.rewit.infrastructure.persistence;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.ReviewDetailView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.discussion.DiscussionDtos.CreateDiscussionCommand;
import com.rewit.application.dto.discussion.DiscussionDtos.DiscussionView;
import com.rewit.application.port.*;
import com.rewit.application.service.DiscussionService;
import com.rewit.application.service.ReviewService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.*;
import com.rewit.infrastructure.persistence.entity.DiscussionJpaEntity;
import com.rewit.infrastructure.persistence.repository.DiscussionJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração PostgreSQL Real: Persistência de Discussões de Avaliações (Step 20.0)")
class DiscussionPersistenceIntegrationTest {

    @Autowired
    private DiscussionService discussionService;

    @Autowired
    private DiscussionRepository discussionRepository;

    @Autowired
    private DiscussionJpaRepository discussionJpaRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateableTargetRepository targetRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private User createTestUser(String prefix) {
        String unique = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        User user = new User(
                null,
                unique + "@rewit.test",
                "Password123!",
                AuthProvider.LOCAL,
                null
        );
        user = userRepository.save(user);

        Profile profile = new Profile(
                UUID.randomUUID(),
                user.getId(),
                "u_" + unique.replace("-", "_").toLowerCase(),
                "User " + unique,
                "Bio",
                null
        );
        profileRepository.save(profile);
        return user;
    }

    private Place createTestPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
                null,
                "Lugar " + suffix,
                "lugar-" + suffix,
                "RESTAURANTE",
                "Descrição",
                "Rua Teste, 100",
                "100",
                "Centro",
                "São Paulo",
                "SP",
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

    private RateableTarget createTestTarget(UUID placeId) {
        RateableTarget target = new RateableTarget(UUID.randomUUID(), TargetType.PLACE);
        return targetRepository.save(target);
    }

    private Review createTestReview(User author, Place place, String visibility) {
        RateableTarget target = createTestTarget(place.getId());

        CreateReviewCommand cmd = new CreateReviewCommand(
                author.getId(),
                place.getId(),
                "Avaliação para discussões em banco real",
                false,
                visibility,
                null,
                null,
                null,
                List.of(new CreateReviewTargetCommand(target.getId(), BigDecimal.valueOf(4.5), "Nota do local"))
        );
        ReviewDetailView view = reviewService.createReview(cmd);
        return reviewRepository.findById(view.id()).orElseThrow();
    }

    @Test
    @DisplayName("1. Persistência real no PostgreSQL: salva e recupera discussão raiz")
    void shouldPersistRootDiscussionInPostgres() {
        User author = createTestUser("disc_author");
        User commenter = createTestUser("disc_commenter");
        Place place = createTestPlace();
        Review review = createTestReview(author, place, "PUBLIC");

        CreateDiscussionCommand cmd = new CreateDiscussionCommand(
                review.getId(),
                commenter.getId(),
                null,
                "Comentário inicial persistido no banco real"
        );

        DiscussionView view = discussionService.createDiscussion(cmd);

        assertNotNull(view.id());
        assertEquals(review.getId(), view.reviewId());
        assertEquals(commenter.getId(), view.authorId());
        assertNull(view.parentId());
        assertEquals("Comentário inicial persistido no banco real", view.content());
        assertFalse(view.isFromOwner());
        assertEquals("ACTIVE", view.status());
        assertNotNull(view.createdAt());

        // Validação direta via JPA Entity e Adapter
        Optional<ReviewDiscussion> retrieved = discussionRepository.findById(view.id());
        assertTrue(retrieved.isPresent());
        assertEquals(DiscussionStatus.ACTIVE, retrieved.get().getStatus());
        assertEquals(commenter.getId(), retrieved.get().getUserId());
    }

    @Test
    @DisplayName("2. Resposta com parentId: persiste encadeamento e referencia o pai no PostgreSQL")
    void shouldPersistReplyWithParentIdInPostgres() {
        User author = createTestUser("disc_owner");
        User commenter = createTestUser("disc_replier");
        Place place = createTestPlace();
        Review review = createTestReview(author, place, "PUBLIC");

        // Cria raiz pelo comentarista
        DiscussionView root = discussionService.createDiscussion(new CreateDiscussionCommand(
                review.getId(),
                commenter.getId(),
                null,
                "Pergunta ao dono da review"
        ));

        // Cria resposta pelo autor da avaliação (isFromOwner = true)
        DiscussionView reply = discussionService.createDiscussion(new CreateDiscussionCommand(
                review.getId(),
                author.getId(),
                root.id(),
                "Resposta do proprietário da avaliação"
        ));

        assertNotNull(reply.id());
        assertEquals(root.id(), reply.parentId());
        assertTrue(reply.isFromOwner());

        // Checagem no JPA Entity para garantir parent_id no schema PostgreSQL
        Optional<DiscussionJpaEntity> jpaOpt = discussionJpaRepository.findById(reply.id());
        assertTrue(jpaOpt.isPresent());
        assertEquals(root.id(), jpaOpt.get().getParentId());
        assertTrue(jpaOpt.get().isFromOwner());
    }

    @Test
    @DisplayName("3. Integridade referencial PostgreSQL: FK para reviews e users é estritamente garantida")
    void shouldEnforceForeignKeysInPostgres() {
        User validUser = createTestUser("valid_fk_user");
        UUID fakeReviewId = UUID.randomUUID();

        ReviewDiscussion orphanReview = new ReviewDiscussion(
                null,
                fakeReviewId,
                validUser.getId(),
                null,
                "Comentário em review inexistente",
                false
        );

        assertThrows(DataIntegrityViolationException.class, () ->
                discussionRepository.save(orphanReview));

        UUID fakeUserId = UUID.randomUUID();
        Place place = createTestPlace();
        Review review = createTestReview(validUser, place, "PUBLIC");

        ReviewDiscussion orphanUser = new ReviewDiscussion(
                null,
                review.getId(),
                fakeUserId,
                null,
                "Comentário de usuário inexistente",
                false
        );

        assertThrows(DataIntegrityViolationException.class, () ->
                discussionRepository.save(orphanUser));
    }

    @Test
    @DisplayName("4. Leitura paginada com ordenação fixa created_at ASC, id ASC")
    void shouldPaginateWithStrictChronologicalOrdering() throws InterruptedException {
        User author = createTestUser("pag_author");
        User commenter = createTestUser("pag_user");
        Place place = createTestPlace();
        Review review = createTestReview(author, place, "PUBLIC");

        DiscussionView d1 = discussionService.createDiscussion(new CreateDiscussionCommand(
                review.getId(), commenter.getId(), null, "Comentário 1"));
        Thread.sleep(20);
        DiscussionView d2 = discussionService.createDiscussion(new CreateDiscussionCommand(
                review.getId(), author.getId(), d1.id(), "Resposta 2"));
        Thread.sleep(20);
        DiscussionView d3 = discussionService.createDiscussion(new CreateDiscussionCommand(
                review.getId(), commenter.getId(), null, "Comentário 3"));

        PageResult<DiscussionView> page0 = discussionService.findDiscussionsByReviewId(review.getId(), commenter.getId(), 0, 2);
        assertEquals(2, page0.content().size());
        assertEquals(d1.id(), page0.content().get(0).id());
        assertEquals(d2.id(), page0.content().get(1).id());
        assertEquals(3L, page0.totalElements());
        assertEquals(2, page0.totalPages());
        assertFalse(page0.isLast());

        PageResult<DiscussionView> page1 = discussionService.findDiscussionsByReviewId(review.getId(), commenter.getId(), 1, 2);
        assertEquals(1, page1.content().size());
        assertEquals(d3.id(), page1.content().get(0).id());
        assertTrue(page1.isLast());
    }

    @Test
    @DisplayName("5. Soft delete: marca como REMOVED, exclui da listagem ativa mas preserva linha no PostgreSQL")
    void shouldPerformSoftDeleteWithoutPhysicalDeletion() {
        User author = createTestUser("soft_author");
        User commenter = createTestUser("soft_commenter");
        Place place = createTestPlace();
        Review review = createTestReview(author, place, "PUBLIC");

        DiscussionView created = discussionService.createDiscussion(new CreateDiscussionCommand(
                review.getId(), commenter.getId(), null, "Comentário que será removido"));

        // Remoção pelo autor do comentário
        discussionService.deleteDiscussion(created.id(), commenter.getId());

        // Na listagem ativa, não deve aparecer
        PageResult<DiscussionView> activeList = discussionService.findDiscussionsByReviewId(review.getId(), commenter.getId(), 0, 10);
        assertTrue(activeList.content().stream().noneMatch(d -> d.id().equals(created.id())));

        // No banco de dados, o registro persiste fisicamente com status REMOVED
        Optional<DiscussionJpaEntity> jpaOpt = discussionJpaRepository.findById(created.id());
        assertTrue(jpaOpt.isPresent());
        assertEquals(DiscussionStatus.REMOVED, jpaOpt.get().getStatus());
    }

    @Test
    @DisplayName("6. Impossibilidade de construir relação de resposta entre comentários de Reviews diferentes")
    void shouldRejectCrossReviewReply() {
        User userA = createTestUser("cross_a");
        User userB = createTestUser("cross_b");
        Place place1 = createTestPlace();
        Place place2 = createTestPlace();

        Review review1 = createTestReview(userA, place1, "PUBLIC");
        Review review2 = createTestReview(userB, place2, "PUBLIC");

        // Comentário pertence à review1
        DiscussionView commentRev1 = discussionService.createDiscussion(new CreateDiscussionCommand(
                review1.getId(), userA.getId(), null, "Comentário na Review 1"));

        // Tentativa de responder a commentRev1 dentro da review2
        CreateDiscussionCommand invalidCmd = new CreateDiscussionCommand(
                review2.getId(), userB.getId(), commentRev1.id(), "Tentando responder na Review 2");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                discussionService.createDiscussion(invalidCmd));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_PARENT_DISCUSSION", ex.getErrorCode());
    }

    @Test
    @DisplayName("7. Status UNDER_REVIEW: persiste e recupera com sucesso mas é excluído da listagem ativa")
    void shouldPersistAndFilterUnderReviewDiscussion() {
        User author = createTestUser("under_author");
        User commenter = createTestUser("under_commenter");
        Place place = createTestPlace();
        Review review = createTestReview(author, place, "PUBLIC");

        ReviewDiscussion underReview = new ReviewDiscussion(
                null,
                review.getId(),
                commenter.getId(),
                null,
                "Comentário em moderação preventiva",
                false,
                DiscussionStatus.UNDER_REVIEW,
                java.time.Instant.now(),
                java.time.Instant.now()
        );
        ReviewDiscussion saved = discussionRepository.save(underReview);

        Optional<ReviewDiscussion> retrieved = discussionRepository.findById(saved.getId());
        assertTrue(retrieved.isPresent());
        assertEquals(DiscussionStatus.UNDER_REVIEW, retrieved.get().getStatus());
        assertTrue(retrieved.get().isUnderReview());

        // Deve ser excluído da listagem pública ativa
        PageResult<DiscussionView> activeList = discussionService.findDiscussionsByReviewId(review.getId(), commenter.getId(), 0, 10);
        assertTrue(activeList.content().stream().noneMatch(d -> d.id().equals(saved.getId())));
    }

    @Test
    @DisplayName("8. findByIdForUpdate: recupera registro com lock pessimista em transação ativa")
    void shouldRetrieveDiscussionWithLockPessimistic() {
        User author = createTestUser("lock_author");
        User commenter = createTestUser("lock_commenter");
        Place place = createTestPlace();
        Review review = createTestReview(author, place, "PUBLIC");

        DiscussionView created = discussionService.createDiscussion(new CreateDiscussionCommand(
                review.getId(), commenter.getId(), null, "Comentário para lock pessimista"));

        transactionTemplate.execute(status -> {
            Optional<ReviewDiscussion> locked = discussionRepository.findByIdForUpdate(created.id());
            assertTrue(locked.isPresent());
            assertEquals(DiscussionStatus.ACTIVE, locked.get().getStatus());
            return null;
        });

        // Caso ID inexistente
        transactionTemplate.execute(status -> {
            Optional<ReviewDiscussion> notFound = discussionRepository.findByIdForUpdate(UUID.randomUUID());
            assertTrue(notFound.isEmpty());
            return null;
        });
    }

    @Test
    @DisplayName("9. Constraint de integridade chk_review_discussions_status: PostgreSQL rejeita status inválido")
    void shouldRejectInvalidStatusByCheckConstraint() {
        User author = createTestUser("chk_author");
        Place place = createTestPlace();
        Review review = createTestReview(author, place, "PUBLIC");

        assertThrows(DataIntegrityViolationException.class, () -> {
            jdbcTemplate.update(
                    "INSERT INTO review_discussions (id, review_id, user_id, content, is_from_owner, status, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())",
                    UUID.randomUUID(),
                    review.getId(),
                    author.getId(),
                    "Comentário com status inválido",
                    false,
                    "INVALID_STATUS"
            );
        });
    }
}
