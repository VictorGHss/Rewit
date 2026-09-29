package com.rewit.infrastructure.persistence;

import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.ReviewReactionRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.persistence.repository.ReviewReactionJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração de Persistência no PostgreSQL Real - Validação de Utilidade (Helpful) (Step 16.0)")
class ReviewHelpfulPersistenceIntegrationTest {

    @Autowired
    private ReviewReactionRepository reviewReactionRepository;

    @Autowired
    private ReviewReactionJpaRepository reviewReactionJpaRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User createUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User(null, "user-" + suffix + "@rewit.com", "hash123", AuthProvider.LOCAL, null);
        User savedUser = userRepository.save(user);

        Profile profile = new Profile(UUID.randomUUID(), savedUser.getId(), "handle_" + suffix, "Nome " + suffix, "Bio", null);
        profileRepository.save(profile);

        return savedUser;
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
                null,
                "Restaurante " + suffix,
                "restaurante-" + suffix,
                "RESTAURANTE",
                "Descrição",
                "Rua Helpful, 100",
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

    private Review createReview(User author, Place place) {
        Review review = new Review(
                null,
                author.getId(),
                place.getId(),
                "Experiência detalhada com foco em utilidade.",
                false,
                false,
                ReviewStatus.ACTIVE,
                "PUBLIC",
                -23.5505,
                -46.6333,
                10.0,
                Instant.now(),
                Instant.now()
        );
        return reviewRepository.save(review);
    }

    @Test
    @DisplayName("1. Persiste Helpful com sucesso e verifica contagem e voto")
    void shouldPersistAndCountHelpfulSuccessfully() {
        User author = createUser();
        User voter = createUser();
        Place place = createPlace();
        Review review = createReview(author, place);

        assertFalse(reviewReactionRepository.isHelpful(review.getId(), voter.getId()));
        assertEquals(0L, reviewReactionRepository.countHelpful(review.getId()));

        boolean added = reviewReactionRepository.addHelpful(review.getId(), voter.getId());
        assertTrue(added);

        assertTrue(reviewReactionRepository.isHelpful(review.getId(), voter.getId()));
        assertEquals(1L, reviewReactionRepository.countHelpful(review.getId()));
    }

    @Test
    @DisplayName("2. Adição repetida é idempotente no banco")
    void shouldBeIdempotentOnRepeatedAdd() {
        User author = createUser();
        User voter = createUser();
        Place place = createPlace();
        Review review = createReview(author, place);

        boolean first = reviewReactionRepository.addHelpful(review.getId(), voter.getId());
        boolean second = reviewReactionRepository.addHelpful(review.getId(), voter.getId());
        boolean third = reviewReactionRepository.addHelpful(review.getId(), voter.getId());

        assertTrue(first);
        assertFalse(second);
        assertFalse(third);

        assertEquals(1L, reviewReactionRepository.countHelpful(review.getId()));
        assertEquals(1L, reviewReactionJpaRepository.countByReviewIdAndReactionType(review.getId(), "HELPFUL"));
    }

    @Test
    @DisplayName("3. Remoção de Helpful é realizada com sucesso e de forma idempotente")
    void shouldRemoveHelpfulSuccessfullyAndIdempotently() {
        User author = createUser();
        User voter = createUser();
        Place place = createPlace();
        Review review = createReview(author, place);

        reviewReactionRepository.addHelpful(review.getId(), voter.getId());
        assertEquals(1L, reviewReactionRepository.countHelpful(review.getId()));

        boolean removedFirst = reviewReactionRepository.removeHelpful(review.getId(), voter.getId());
        assertTrue(removedFirst);
        assertEquals(0L, reviewReactionRepository.countHelpful(review.getId()));
        assertFalse(reviewReactionRepository.isHelpful(review.getId(), voter.getId()));

        boolean removedSecond = reviewReactionRepository.removeHelpful(review.getId(), voter.getId());
        assertFalse(removedSecond);
        assertEquals(0L, reviewReactionRepository.countHelpful(review.getId()));
    }

    @Test
    @DisplayName("4. Concorrência: duas threads executando addHelpful simultâneo resultam em exatamente 1 registro")
    void shouldHandleConcurrentHelpfulRequestsSafely() throws InterruptedException, ExecutionException {
        User author = createUser();
        User voter = createUser();
        Place place = createPlace();
        Review review = createReview(author, place);

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<Boolean> task = () -> {
            readyLatch.countDown();
            startLatch.await();
            return reviewReactionRepository.addHelpful(review.getId(), voter.getId());
        };

        Future<Boolean> f1 = executor.submit(task);
        Future<Boolean> f2 = executor.submit(task);

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        boolean r1 = f1.get();
        boolean r2 = f2.get();

        executor.shutdown();

        // Exatamente um deve ser true e o outro false (idempotência concorrente)
        assertTrue(r1 ^ r2, "Exatamente uma chamada concorrente deve inserir o registro");
        assertEquals(1L, reviewReactionJpaRepository.countByReviewIdAndReactionType(review.getId(), "HELPFUL"));
        assertEquals(1L, reviewReactionRepository.countHelpful(review.getId()));
    }

    @Test
    @DisplayName("5. Contagem independente: Review A=3, Review B=1, Review C=0")
    void shouldCountHelpfulIndependentlyPerReview() {
        User author = createUser();
        User v1 = createUser();
        User v2 = createUser();
        User v3 = createUser();
        Place place = createPlace();

        Review reviewA = createReview(author, place);
        Review reviewB = createReview(author, place);
        Review reviewC = createReview(author, place);

        // A recebe 3
        reviewReactionRepository.addHelpful(reviewA.getId(), v1.getId());
        reviewReactionRepository.addHelpful(reviewA.getId(), v2.getId());
        reviewReactionRepository.addHelpful(reviewA.getId(), v3.getId());

        // B recebe 1
        reviewReactionRepository.addHelpful(reviewB.getId(), v1.getId());

        // C recebe 0

        assertEquals(3L, reviewReactionRepository.countHelpful(reviewA.getId()));
        assertEquals(1L, reviewReactionRepository.countHelpful(reviewB.getId()));
        assertEquals(0L, reviewReactionRepository.countHelpful(reviewC.getId()));
    }

    @Test
    @DisplayName("6. Apenas reaction_type='HELPFUL' é contabilizado")
    void shouldOnlyCountHelpfulReactionType() {
        User author = createUser();
        User voter1 = createUser();
        User voter2 = createUser();
        Place place = createPlace();
        Review review = createReview(author, place);

        // Inserir manualmente via JDBC uma reação 'LIKE' não-HELPFUL
        jdbcTemplate.update(
                "INSERT INTO review_reactions (id, review_id, user_id, reaction_type) VALUES (?, ?, ?, 'LIKE')",
                UUID.randomUUID(), review.getId(), voter1.getId()
        );

        // Inserir via repositório um HELPFUL
        reviewReactionRepository.addHelpful(review.getId(), voter2.getId());

        // O total de reações na tabela é 2, mas countHelpful deve ser estritamente 1
        Long totalReactionsInDb = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_reactions WHERE review_id = ?",
                Long.class, review.getId()
        );
        assertEquals(2L, totalReactionsInDb);

        assertEquals(1L, reviewReactionRepository.countHelpful(review.getId()));
        assertFalse(reviewReactionRepository.isHelpful(review.getId(), voter1.getId()));
        assertTrue(reviewReactionRepository.isHelpful(review.getId(), voter2.getId()));
    }

    @Test
    @DisplayName("7. Batch de contagens e votos por usuário em lote")
    void shouldBatchCountAndFindHelpfulByUserWithoutNPlusOne() {
        User author = createUser();
        User requester = createUser();
        User otherVoter = createUser();
        Place place = createPlace();

        Review r1 = createReview(author, place);
        Review r2 = createReview(author, place);
        Review r3 = createReview(author, place);

        // Requester vota em r1 e r2
        reviewReactionRepository.addHelpful(r1.getId(), requester.getId());
        reviewReactionRepository.addHelpful(r2.getId(), requester.getId());

        // Outro votante vota em r2 e r3
        reviewReactionRepository.addHelpful(r2.getId(), otherVoter.getId());
        reviewReactionRepository.addHelpful(r3.getId(), otherVoter.getId());

        List<UUID> reviewIds = List.of(r1.getId(), r2.getId(), r3.getId());

        Map<UUID, Long> counts = reviewReactionRepository.countHelpfulByReviewIds(reviewIds);
        assertEquals(1L, counts.getOrDefault(r1.getId(), 0L));
        assertEquals(2L, counts.getOrDefault(r2.getId(), 0L));
        assertEquals(1L, counts.getOrDefault(r3.getId(), 0L));

        Set<UUID> votedByRequester = reviewReactionRepository.findHelpfulReviewIdsByUser(reviewIds, requester.getId());
        assertTrue(votedByRequester.contains(r1.getId()));
        assertTrue(votedByRequester.contains(r2.getId()));
        assertFalse(votedByRequester.contains(r3.getId()));
    }

    @Test
    @DisplayName("8. Cascade delete: ao excluir fisicamente uma review, suas reações são removidas")
    void shouldCascadeDeleteReactionsWhenReviewIsPhysicallyDeleted() {
        User author = createUser();
        User voter = createUser();
        Place place = createPlace();
        Review review = createReview(author, place);

        reviewReactionRepository.addHelpful(review.getId(), voter.getId());
        assertEquals(1L, reviewReactionRepository.countHelpful(review.getId()));

        jdbcTemplate.update("DELETE FROM reviews WHERE id = ?", review.getId());

        Long reactionsRemaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_reactions WHERE review_id = ?",
                Long.class, review.getId()
        );
        assertEquals(0L, reactionsRemaining);
    }
}
