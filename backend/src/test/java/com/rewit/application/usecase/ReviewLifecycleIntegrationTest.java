package com.rewit.application.usecase;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.ReviewDetailView;
import com.rewit.application.dto.ReviewDto.TargetStatsView;
import com.rewit.application.dto.ReviewDto.UpdateReviewCommand;
import com.rewit.application.dto.reputation.ReputationDtos.ReputationView;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.ReputationService;
import com.rewit.application.service.ReviewHelpfulService;
import com.rewit.application.service.ReviewService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.persistence.entity.ReviewJpaEntity;
import com.rewit.infrastructure.persistence.repository.CheckInJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewTargetJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
@DisplayName("Testes de Integração: Ciclo de Vida da Review no PostgreSQL Real (Step 25.2)")
class ReviewLifecycleIntegrationTest {

    @Autowired
    private UpdateReviewUseCase updateReviewUseCase;

    @Autowired
    private DeleteReviewUseCase deleteReviewUseCase;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    @Autowired
    private ReputationService reputationService;

    @Autowired
    private ReviewHelpfulService reviewHelpfulService;

    @Autowired
    private ReviewJpaRepository reviewJpaRepository;

    @Autowired
    private ReviewTargetJpaRepository reviewTargetJpaRepository;

    @Autowired
    private CheckInJpaRepository checkInJpaRepository;

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
                "Local Teste " + suffix,
                "local-teste-" + suffix,
                "RESTAURANTE",
                "Descrição",
                "Rua Teste, 123",
                "123",
                "Centro",
                "Curitiba",
                "PR",
                "BR",
                -25.4284,
                -49.2733,
                50,
                "USER",
                false,
                null,
                "ACTIVE"
        );
        return placeRepository.save(place);
    }

    private RateableTarget createRateableTarget(TargetType type) {
        RateableTarget target = new RateableTarget(UUID.randomUUID(), type);
        return rateableTargetRepository.save(target);
    }

    @Test
    @DisplayName("Caso 1: Edição ponta a ponta com recálculo atômico de stats apenas do target alterado e reputação por anonimato")
    void shouldExecuteUpdateReviewUseCaseEndToEndWithMultiTargetStatsAndReputationInPostgres() {
        User author = createActiveUser();
        RateableTarget target1 = createRateableTarget(TargetType.PLACE);
        RateableTarget target2 = createRateableTarget(TargetType.PRODUCT);

        // 1. Criação de review multi-alvo: target1 com nota 2.0, target2 com nota 4.0
        ReviewDetailView created = reviewService.createReview(new CreateReviewCommand(
                author.getId(),
                null,
                "Review multi-alvo inicial",
                false, // não anônimo
                "PUBLIC",
                null,
                null,
                null,
                List.of(
                        new CreateReviewTargetCommand(target1.getId(), new BigDecimal("2.0"), "Target 1"),
                        new CreateReviewTargetCommand(target2.getId(), new BigDecimal("4.0"), "Target 2")
                )
        ));

        // Stats iniciais: target1 = 2.00 (1 review), target2 = 4.00 (1 review)
        TargetStatsView stats1Before = reviewService.getTargetStats(target1.getId());
        TargetStatsView stats2Before = reviewService.getTargetStats(target2.getId());
        assertEquals(new BigDecimal("2.00"), stats1Before.averageRating());
        assertEquals(1, stats1Before.reviewsCount());
        assertEquals(new BigDecimal("4.00"), stats2Before.averageRating());
        assertEquals(1, stats2Before.reviewsCount());

        // Reputação inicial do autor: 1 review standard não-anônima = 10 pts
        ReputationView repBefore = reputationService.getReputation(author.getId());
        assertEquals(1, repBefore.signals().activeReviews());

        // Capturar createdAt persistido originalmente no DB
        Instant initialCreatedAt = reviewJpaRepository.findById(created.id()).orElseThrow().getCreatedAt();

        // 2. Executar UpdateReviewUseCase:
        // - Altera nota apenas de target1 para 5.0 (target2 inalterado)
        // - Altera texto
        // - Altera isAnonymous para true
        Instant editTime = Instant.now().plusSeconds(1800).truncatedTo(ChronoUnit.MICROS);
        UpdateReviewCommand updateCmd = new UpdateReviewCommand(
                "Texto revisado com nota 5",
                Map.of(target1.getId(), new BigDecimal("5.0")),
                true, // tornando anônimo
                "PUBLIC",
                editTime
        );

        updateReviewUseCase.execute(created.id(), author.getId(), updateCmd);

        // 3. Verificações no PostgreSQL:
        // Target 1 teve nota atualizada para 5.00:
        TargetStatsView stats1After = reviewService.getTargetStats(target1.getId());
        assertEquals(new BigDecimal("5.00"), stats1After.averageRating());
        assertEquals(1, stats1After.reviewsCount());

        // Target 2 permaneceu intacto:
        TargetStatsView stats2After = reviewService.getTargetStats(target2.getId());
        assertEquals(new BigDecimal("4.00"), stats2After.averageRating());
        assertEquals(1, stats2After.reviewsCount());

        // Reputação do autor: review virou anônima, então não conta mais para a reputação pública factual:
        ReputationView repAfter = reputationService.getReputation(author.getId());
        assertEquals(0, repAfter.signals().activeReviews());

        // Review persistida:
        ReviewJpaEntity dbEntity = reviewJpaRepository.findById(created.id()).orElseThrow();
        assertEquals("Texto revisado com nota 5", dbEntity.getExperienceText());
        assertTrue(dbEntity.isAnonymous());
        assertEquals(editTime, dbEntity.getUpdatedAt());
        assertEquals(initialCreatedAt, dbEntity.getCreatedAt()); // createdAt inalterado
    }

    @Test
    @DisplayName("Caso 2: Alteração de rating é bloqueada quando review possui Helpful votes, mas texto é permitido")
    void shouldBlockRatingUpdateWhenReviewHasHelpfulVotesInPostgres() {
        User author = createActiveUser();
        User voter = createActiveUser();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        ReviewDetailView created = reviewService.createReview(new CreateReviewCommand(
                author.getId(),
                null,
                "Review com potencial útil",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("3.0"), "Ok"))
        ));

        // Voter adiciona voto de Helpful
        reviewHelpfulService.addHelpful(created.id(), voter.getId());

        // Tentativa de alterar nota pelo autor: bloqueada por helpful
        Instant editTime = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        UpdateReviewCommand ratingCmd = new UpdateReviewCommand(
                "Tentando mudar nota",
                Map.of(target.getId(), new BigDecimal("5.0")),
                null,
                null,
                editTime
        );

        BusinessException ex = assertThrows(BusinessException.class, () ->
                updateReviewUseCase.execute(created.id(), author.getId(), ratingCmd));
        assertEquals("REVIEW_EDIT_RATING_BLOCKED_BY_HELPFUL", ex.getErrorCode());

        // Tentativa de alterar apenas texto: permitida com sucesso
        UpdateReviewCommand textCmd = new UpdateReviewCommand(
                "Texto atualizado sem mudar nota",
                null,
                null,
                null,
                editTime
        );

        assertDoesNotThrow(() -> updateReviewUseCase.execute(created.id(), author.getId(), textCmd));

        ReviewJpaEntity dbEntity = reviewJpaRepository.findById(created.id()).orElseThrow();
        assertEquals("Texto atualizado sem mudar nota", dbEntity.getExperienceText());
    }

    @Test
    @DisplayName("Caso 3: Edição após expiração da janela de 24 horas é bloqueada")
    void shouldBlockEditWhen24HoursWindowHasExpiredInPostgres() {
        User author = createActiveUser();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        ReviewDetailView created = reviewService.createReview(new CreateReviewCommand(
                author.getId(),
                null,
                "Review de ontem",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("4.0"), "Bom"))
        ));

        Instant expiredNow = created.createdAt().plus(25, ChronoUnit.HOURS);
        UpdateReviewCommand cmd = new UpdateReviewCommand("Edit tardio", null, null, null, expiredNow);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                updateReviewUseCase.execute(created.id(), author.getId(), cmd));
        assertEquals("REVIEW_EDIT_WINDOW_EXPIRED", ex.getErrorCode());
    }

    @Test
    @DisplayName("Caso 4: Soft delete ponta a ponta expurga agregados de stats e reputação mantendo registros relacionais intactos")
    void shouldExecuteDeleteReviewUseCaseEndToEndPreservingEntitiesAndRecalculatingAggregates() {
        User author = createActiveUser();
        Place place = createPlace();
        RateableTarget placeTarget = rateableTargetRepository.findById(place.getId())
                .orElseGet(() -> rateableTargetRepository.save(new RateableTarget(place.getId(), TargetType.PLACE)));
        RateableTarget productTarget = createRateableTarget(TargetType.PRODUCT);

        // Cria review verificada presencialmente no local
        ReviewDetailView created = reviewService.createReview(new CreateReviewCommand(
                author.getId(),
                place.getId(),
                "Experiência presencial excelente",
                false,
                "PUBLIC",
                place.getLatitude(),
                place.getLongitude(),
                10.0,
                List.of(
                        new CreateReviewTargetCommand(placeTarget.getId(), new BigDecimal("5.0"), "Local"),
                        new CreateReviewTargetCommand(productTarget.getId(), new BigDecimal("4.0"), "Produto")
                )
        ));

        assertTrue(created.isVerifiedOnSite());

        // Stats iniciais: placeTarget = 5.00, productTarget = 4.00
        assertEquals(new BigDecimal("5.00"), reviewService.getTargetStats(placeTarget.getId()).averageRating());
        assertEquals(new BigDecimal("4.00"), reviewService.getTargetStats(productTarget.getId()).averageRating());

        // Reputação inicial do autor: 1 review verificada ativa = 25 pts
        ReputationView repBefore = reputationService.getReputation(author.getId());
        assertEquals(1, repBefore.signals().verifiedReviews());
        assertEquals(1, repBefore.signals().activeReviews());

        // Capturar createdAt persistido originalmente no DB
        Instant initialCreatedAt = reviewJpaRepository.findById(created.id()).orElseThrow().getCreatedAt();

        // Executar Soft Delete
        Instant deleteTime = Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.MICROS);
        deleteReviewUseCase.execute(created.id(), author.getId(), deleteTime);

        // 1. Review status agora é REMOVED no PostgreSQL:
        ReviewJpaEntity dbReview = reviewJpaRepository.findById(created.id()).orElseThrow();
        assertEquals("REMOVED", dbReview.getStatus());
        assertEquals(deleteTime, dbReview.getUpdatedAt());
        assertEquals(initialCreatedAt, dbReview.getCreatedAt());

        // 2. Alvos em review_targets continuam no PostgreSQL:
        assertEquals(2, reviewTargetJpaRepository.findByReviewId(created.id()).size());

        // 3. CheckIn em check_ins continua no PostgreSQL:
        assertTrue(checkInJpaRepository.findByReviewId(created.id()).isPresent());

        // 4. Stats de ambos os alvos foram recalculados expurgando a review removida:
        assertEquals(new BigDecimal("0.00"), reviewService.getTargetStats(placeTarget.getId()).averageRating());
        assertEquals(0, reviewService.getTargetStats(placeTarget.getId()).reviewsCount());
        assertEquals(new BigDecimal("0.00"), reviewService.getTargetStats(productTarget.getId()).averageRating());
        assertEquals(0, reviewService.getTargetStats(productTarget.getId()).reviewsCount());

        // 5. Reputação do autor foi recalculada expurgando a review:
        ReputationView repAfter = reputationService.getReputation(author.getId());
        assertEquals(0, repAfter.signals().verifiedReviews());
        assertEquals(0, repAfter.signals().activeReviews());
    }

    @Test
    @DisplayName("Caso 5: Soft delete de review em UNDER_REVIEW é permitido e expurga os agregados")
    void shouldAllowDeleteFromUnderReviewStatusInPostgres() {
        User author = createActiveUser();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        ReviewDetailView created = reviewService.createReview(new CreateReviewCommand(
                author.getId(),
                null,
                "Review que entrará em moderação",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("4.0"), "Alvo"))
        ));

        // Força transição para UNDER_REVIEW
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ReviewJpaEntity entity = reviewJpaRepository.findById(created.id()).orElseThrow();
            entity.setStatus("UNDER_REVIEW");
            reviewJpaRepository.save(entity);
        });

        // Soft delete a partir de UNDER_REVIEW é permitido
        Instant deleteTime = Instant.now().plusSeconds(180).truncatedTo(ChronoUnit.MICROS);
        assertDoesNotThrow(() -> deleteReviewUseCase.execute(created.id(), author.getId(), deleteTime));

        ReviewJpaEntity dbReview = reviewJpaRepository.findById(created.id()).orElseThrow();
        assertEquals("REMOVED", dbReview.getStatus());
    }

    @Test
    @DisplayName("Caso 6: Concorrência - duas requisições simultâneas de delete na mesma review garantem serialização determinística via lock pessimista")
    void shouldPreventDoubleDeleteAndStateCorruptionUnderConcurrentCallsInPostgres() throws Exception {
        User author = createActiveUser();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        ReviewDetailView created = reviewService.createReview(new CreateReviewCommand(
                author.getId(),
                null,
                "Review para teste concorrente de exclusão",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("5.0"), "Excelente"))
        ));

        // Sanity check inicial de stats
        assertEquals(1, reviewService.getTargetStats(target.getId()).reviewsCount());
        assertEquals(new BigDecimal("5.00"), reviewService.getTargetStats(target.getId()).averageRating());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);

        Callable<Boolean> deleteTask = () -> {
            startGate.await();
            try {
                deleteReviewUseCase.execute(created.id(), author.getId(), Instant.now());
                return true;
            } catch (BusinessException ex) {
                if ("REVIEW_ALREADY_REMOVED".equals(ex.getErrorCode())) {
                    return false;
                }
                throw ex;
            } finally {
                doneGate.countDown();
            }
        };

        Future<Boolean> future1 = executor.submit(deleteTask);
        Future<Boolean> future2 = executor.submit(deleteTask);

        startGate.countDown();
        boolean completed = doneGate.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "As operações concorrentes devem concluir dentro do tempo limite");

        boolean result1 = future1.get();
        boolean result2 = future2.get();

        // Exatamente uma transação deve ter sucesso e a outra deve ser rejeitada com REVIEW_ALREADY_REMOVED
        assertTrue(result1 ^ result2, "Exatamente uma das chamadas concorrentes deve ter sucesso (XOR)");

        // Estado final persistido no PostgreSQL deve ser consistente
        ReviewJpaEntity dbReview = reviewJpaRepository.findById(created.id()).orElseThrow();
        assertEquals("REMOVED", dbReview.getStatus());

        // Stats do target não sofrem double-decremento: reviewsCount = 0, averageRating = 0.00
        assertEquals(0, reviewService.getTargetStats(target.getId()).reviewsCount());
        assertEquals(new BigDecimal("0.00"), reviewService.getTargetStats(target.getId()).averageRating());

        executor.shutdown();
    }
}
