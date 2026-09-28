package com.rewit.infrastructure.persistence;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.TargetStatsView;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.RateableTargetStatsRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.ReviewService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.persistence.entity.ReviewJpaEntity;
import com.rewit.infrastructure.persistence.repository.ReviewJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração de RateableTargetStats e Concorrência no PostgreSQL (Step 13.0)")
class RateableTargetStatsPersistenceIntegrationTest {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private RateableTargetStatsRepository rateableTargetStatsRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private ReviewJpaRepository reviewJpaRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User createActiveUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User(null, "user-" + suffix + "@rewit.com", "hash123", AuthProvider.LOCAL, null);
        return userRepository.save(user);
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
                null,
                "Café " + suffix,
                "cafe-" + suffix,
                "ALIMENTACAO",
                "Café aconchegante",
                "Rua XV de Novembro",
                "100",
                "Centro",
                "Curitiba",
                "PR",
                "BR",
                -25.4284,
                -49.2733,
                100,
                "USER",
                false,
                null,
                "ACTIVE"
        );
        return placeRepository.save(place);
    }

    private RateableTarget createGenericTarget(TargetType type) {
        RateableTarget target = new RateableTarget(null, type);
        return rateableTargetRepository.save(target);
    }

    @Test
    @DisplayName("Cenário A e B: Primeira e Segunda Review no mesmo alvo acumulam média exata e contagem")
    void shouldAccumulateAverageAndCountCorrectlyAcrossReviews() {
        User user1 = createActiveUser();
        User user2 = createActiveUser();
        Place place = createPlace();

        // 1. Primeira avaliação com nota 4.5
        CreateReviewCommand cmd1 = new CreateReviewCommand(
                user1.getId(),
                place.getId(),
                "Primeira visita",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(place.getId(), new BigDecimal("4.5"), "Muito bom"))
        );
        reviewService.createReview(cmd1);

        TargetStatsView stats1 = reviewService.getTargetStats(place.getId());
        assertEquals(place.getId(), stats1.targetId());
        assertEquals(new BigDecimal("4.50"), stats1.averageRating());
        assertEquals(1, stats1.reviewsCount());
        assertNotNull(stats1.lastCalculatedAt());

        // 2. Segunda avaliação no mesmo alvo com nota 3.0
        CreateReviewCommand cmd2 = new CreateReviewCommand(
                user2.getId(),
                place.getId(),
                "Segunda visita",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(place.getId(), new BigDecimal("3.0"), "Regular"))
        );
        reviewService.createReview(cmd2);

        // Esperado: (4.5 + 3.0) / 2 = 7.5 / 2 = 3.75
        TargetStatsView stats2 = reviewService.getTargetStats(place.getId());
        assertEquals(new BigDecimal("3.75"), stats2.averageRating());
        assertEquals(2, stats2.reviewsCount());
    }

    @Test
    @DisplayName("Cenário C: Multi-target em publicação única atualiza estatísticas independentes sem contaminação")
    void shouldUpdateIndependentStatsForMultipleTargetsInSingleReview() {
        User user = createActiveUser();
        Place place = createPlace();
        RateableTarget productA = createGenericTarget(TargetType.PRODUCT);
        RateableTarget productB = createGenericTarget(TargetType.PRODUCT);

        CreateReviewCommand cmd = new CreateReviewCommand(
                user.getId(),
                place.getId(),
                "Jantar completo",
                false,
                "PUBLIC",
                List.of(
                        new CreateReviewTargetCommand(place.getId(), new BigDecimal("4.0"), "Local agradável"),
                        new CreateReviewTargetCommand(productA.getId(), new BigDecimal("5.0"), "Prato impecável"),
                        new CreateReviewTargetCommand(productB.getId(), new BigDecimal("3.0"), "Sobremesa mediana")
                )
        );
        reviewService.createReview(cmd);

        // Cada alvo deve possuir sua nota individual exata, sem média global combinada
        TargetStatsView placeStats = reviewService.getTargetStats(place.getId());
        assertEquals(new BigDecimal("4.00"), placeStats.averageRating());
        assertEquals(1, placeStats.reviewsCount());

        TargetStatsView prodAStats = reviewService.getTargetStats(productA.getId());
        assertEquals(new BigDecimal("5.00"), prodAStats.averageRating());
        assertEquals(1, prodAStats.reviewsCount());

        TargetStatsView prodBStats = reviewService.getTargetStats(productB.getId());
        assertEquals(new BigDecimal("3.00"), prodBStats.averageRating());
        assertEquals(1, prodBStats.reviewsCount());
    }

    @Test
    @DisplayName("Cenário D: Avaliação anônima contribui normalmente para as estatísticas do alvo")
    void shouldIncludeAnonymousReviewInTargetStats() {
        User user = createActiveUser();
        Place place = createPlace();

        CreateReviewCommand cmd = new CreateReviewCommand(
                user.getId(),
                place.getId(),
                "Avaliação anônima",
                true,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(place.getId(), new BigDecimal("5.0"), "Excelente sigilo"))
        );
        reviewService.createReview(cmd);

        TargetStatsView stats = reviewService.getTargetStats(place.getId());
        assertEquals(new BigDecimal("5.00"), stats.averageRating());
        assertEquals(1, stats.reviewsCount());
    }

    @Test
    @DisplayName("Cenário E e F: Reviews com status UNDER_REVIEW e REMOVED não entram no cálculo de agregação")
    void shouldExcludeNonActiveReviewsFromAggregation() {
        User user1 = createActiveUser();
        User user2 = createActiveUser();
        Place place = createPlace();

        // 1. Criar review com nota 4.0
        reviewService.createReview(new CreateReviewCommand(
                user1.getId(),
                place.getId(),
                "Review legítima",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(place.getId(), new BigDecimal("4.0"), "Muito bom"))
        ));

        // 2. Criar review com nota 2.0
        var reviewView2 = reviewService.createReview(new CreateReviewCommand(
                user2.getId(),
                place.getId(),
                "Review denunciada",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(place.getId(), new BigDecimal("2.0"), "Ruim"))
        ));

        // Ambas ativas inicialmente: (4.0 + 2.0) / 2 = 3.00
        assertEquals(new BigDecimal("3.00"), reviewService.getTargetStats(place.getId()).averageRating());
        assertEquals(2, reviewService.getTargetStats(place.getId()).reviewsCount());

        // 3. Alterar status da segunda review para REMOVED e disparar recálculo
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ReviewJpaEntity entity = reviewJpaRepository.findById(reviewView2.id()).orElseThrow();
            entity.setStatus("REMOVED");
            reviewJpaRepository.save(entity);
            rateableTargetStatsRepository.recalculateAndSave(place.getId());
        });

        // Agora somente a primeira ativa (4.0) entra na agregação:
        TargetStatsView statsAfterRemoved = reviewService.getTargetStats(place.getId());
        assertEquals(new BigDecimal("4.00"), statsAfterRemoved.averageRating());
        assertEquals(1, statsAfterRemoved.reviewsCount());

        // 4. Alterar a primeira review também para UNDER_REVIEW:
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ReviewJpaEntity entity = reviewJpaRepository.findAll().stream()
                    .filter(r -> r.getUserId().equals(user1.getId()))
                    .findFirst().orElseThrow();
            entity.setStatus("UNDER_REVIEW");
            reviewJpaRepository.save(entity);
            rateableTargetStatsRepository.recalculateAndSave(place.getId());
        });

        // Zero reviews ativas: média 0.00 e count 0
        TargetStatsView statsZero = reviewService.getTargetStats(place.getId());
        assertEquals(new BigDecimal("0.00"), statsZero.averageRating());
        assertEquals(0, statsZero.reviewsCount());
    }

    @Test
    @DisplayName("Cenário G e H: Review com CheckIn VERIFIED não inflaciona peso (peso unitário igual a review sem checkin)")
    void shouldKeepUnitWeightForVerifiedAndUnverifiedReviews() {
        User user1 = createActiveUser();
        User user2 = createActiveUser();
        Place place = createPlace();

        // Review sem coordenadas (sem checkin) nota 4.0
        reviewService.createReview(new CreateReviewCommand(
                user1.getId(),
                place.getId(),
                "Remoto",
                false,
                "PUBLIC",
                null,
                null,
                null,
                List.of(new CreateReviewTargetCommand(place.getId(), new BigDecimal("4.0"), "Remoto"))
        ));

        // Review com coordenadas verificadas nota 5.0
        reviewService.createReview(new CreateReviewCommand(
                user2.getId(),
                place.getId(),
                "Presencial",
                false,
                "PUBLIC",
                place.getLatitude(),
                place.getLongitude(),
                5.0,
                List.of(new CreateReviewTargetCommand(place.getId(), new BigDecimal("5.0"), "Presencial"))
        ));

        // Média aritmética simples de (4.0 + 5.0) / 2 = 4.50
        TargetStatsView stats = reviewService.getTargetStats(place.getId());
        assertEquals(new BigDecimal("4.50"), stats.averageRating());
        assertEquals(2, stats.reviewsCount());
    }

    @Test
    @DisplayName("Cenário I e J: Alvo existente sem reviews retorna default 0.00 e alvo inexistente lança 404")
    void shouldHandleUnratedAndMissingTargetsCorrectly() {
        Place unratedPlace = createPlace();
        TargetStatsView defaultStats = reviewService.getTargetStats(unratedPlace.getId());

        assertEquals(unratedPlace.getId(), defaultStats.targetId());
        assertEquals(new BigDecimal("0.00"), defaultStats.averageRating());
        assertEquals(0, defaultStats.reviewsCount());
        assertNull(defaultStats.lastCalculatedAt());

        UUID nonExistentId = UUID.randomUUID();
        BusinessException ex = assertThrows(BusinessException.class, () -> reviewService.getTargetStats(nonExistentId));
        assertEquals("RATEABLE_TARGET_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    @DisplayName("Cenário K: Concorrência com N threads para o mesmo alvo sem Lost Update")
    void shouldPreventLostUpdateUnderConcurrentReviewsForSameTarget() throws Exception {
        Place place = createPlace();
        int concurrency = 6;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(concurrency);

        List<User> users = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            users.add(createActiveUser());
        }

        // Ratings variados: 3.0, 4.0, 5.0, 3.0, 4.0, 5.0 (soma = 24.0 / 6 = 4.00)
        BigDecimal[] ratings = {
                new BigDecimal("3.0"),
                new BigDecimal("4.0"),
                new BigDecimal("5.0"),
                new BigDecimal("3.0"),
                new BigDecimal("4.0"),
                new BigDecimal("5.0")
        };

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            final int index = i;
            tasks.add(() -> {
                startGate.await();
                try {
                    reviewService.createReview(new CreateReviewCommand(
                            users.get(index).getId(),
                            place.getId(),
                            "Concorrente " + index,
                            false,
                            "PUBLIC",
                            List.of(new CreateReviewTargetCommand(place.getId(), ratings[index], "Rating " + index))
                    ));
                } finally {
                    doneGate.countDown();
                }
                return null;
            });
        }

        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(executor.submit(task));
        }

        // Disparar simultaneamente
        startGate.countDown();
        boolean completed = doneGate.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "Todas as requisições concorrentes devem concluir no tempo limite");

        for (Future<Void> future : futures) {
            future.get();
        }
        executor.shutdown();

        // Validar no PostgreSQL: reviewsCount deve ser exatamente N e a média deve ser exatamente 4.00
        TargetStatsView finalStats = reviewService.getTargetStats(place.getId());
        assertEquals(concurrency, finalStats.reviewsCount(), "reviews_count deve ser exatamente " + concurrency + " sem lost update");
        assertEquals(new BigDecimal("4.00"), finalStats.averageRating(), "average_rating final deve ser exatamente 4.00");
    }

    @Test
    @DisplayName("Cenário L: Concorrência multi-alvo em ordem cruzada sem deadlocks")
    void shouldPreventDeadlocksUnderConcurrentCrossMultiTargetReviews() throws Exception {
        Place place1 = createPlace();
        Place place2 = createPlace();

        User userA = createActiveUser();
        User userB = createActiveUser();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);

        // Thread A envia [Place 1, Place 2]
        Callable<Void> taskA = () -> {
            startGate.await();
            try {
                reviewService.createReview(new CreateReviewCommand(
                        userA.getId(),
                        place1.getId(),
                        "Ordem A",
                        false,
                        "PUBLIC",
                        List.of(
                                new CreateReviewTargetCommand(place1.getId(), new BigDecimal("4.0"), "P1"),
                                new CreateReviewTargetCommand(place2.getId(), new BigDecimal("5.0"), "P2")
                        )
                ));
            } finally {
                doneGate.countDown();
            }
            return null;
        };

        // Thread B envia em ordem cruzada [Place 2, Place 1]
        Callable<Void> taskB = () -> {
            startGate.await();
            try {
                reviewService.createReview(new CreateReviewCommand(
                        userB.getId(),
                        place2.getId(),
                        "Ordem B",
                        false,
                        "PUBLIC",
                        List.of(
                                new CreateReviewTargetCommand(place2.getId(), new BigDecimal("3.0"), "P2"),
                                new CreateReviewTargetCommand(place1.getId(), new BigDecimal("5.0"), "P1")
                        )
                ));
            } finally {
                doneGate.countDown();
            }
            return null;
        };

        Future<Void> futureA = executor.submit(taskA);
        Future<Void> futureB = executor.submit(taskB);

        startGate.countDown();
        boolean completed = doneGate.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "Nenhum deadlock deve ocorrer entre avaliações multi-alvo concorrentes");

        futureA.get();
        futureB.get();
        executor.shutdown();

        // Validar que ambos os lugares foram avaliados 2 vezes
        TargetStatsView statsP1 = reviewService.getTargetStats(place1.getId());
        assertEquals(2, statsP1.reviewsCount());
        assertEquals(new BigDecimal("4.50"), statsP1.averageRating()); // (4.0 + 5.0) / 2 = 4.50

        TargetStatsView statsP2 = reviewService.getTargetStats(place2.getId());
        assertEquals(2, statsP2.reviewsCount());
        assertEquals(new BigDecimal("4.00"), statsP2.averageRating()); // (5.0 + 3.0) / 2 = 4.00
    }
}
