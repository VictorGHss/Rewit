package com.rewit.infrastructure.persistence;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.ReviewDetailView;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.ReputationRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.ReputationService;
import com.rewit.application.service.ReviewHelpfulService;
import com.rewit.application.service.ReviewService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.User;
import com.rewit.domain.model.UserReputation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de integração PostgreSQL real cobrindo o ciclo de vida completo do snapshot de reputação (Step 23.1).
 * Valida: Cenários 1 a 8 de atualização factual, concorrência pessimista, rollback real e usuários inativos.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração PostgreSQL: Ciclo de Vida da Reputação V1 (Step 23.1)")
class ReputationLifecycleIntegrationTest {

    @Autowired private ReputationService reputationService;
    @Autowired private ReputationRepository reputationRepository;
    @Autowired private ReviewService reviewService;
    @Autowired private ReviewHelpfulService reviewHelpfulService;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    // =========================================================================
    // Cenário 1: Usuário sem fatos
    // =========================================================================

    @Test
    @DisplayName("Cenário 1: Usuário sem fatos deve ter snapshot inicial com contadores zero e versão 1")
    void scenario1_userWithoutFacts_hasZeroCounters() {
        User user = createUser("lc_zero");

        UserReputation snapshot = reputationService.recalculateAndSave(user.getId());

        assertNotNull(snapshot);
        assertEquals(user.getId(), snapshot.getUserId());
        assertEquals(1, snapshot.getVersion());
        assertEquals(0, snapshot.getActiveReviews());
        assertEquals(0, snapshot.getVerifiedReviews());
        assertEquals(0, snapshot.getHelpfulVotesReceived());
        assertEquals(0, snapshot.getDistinctTargetsReviewed());
        assertNotNull(snapshot.getCalculatedAt());
    }

    // =========================================================================
    // Cenário 2: Nova Review ACTIVE
    // =========================================================================

    @Test
    @DisplayName("Cenário 2: Nova Review ACTIVE deve atualizar o snapshot automaticamente via transação")
    void scenario2_activeReview_updatesSnapshot() {
        User author = createUser("lc_act");
        Place place = createPlace();

        reviewService.createReview(new CreateReviewCommand(
            author.getId(), place.getId(), "Avaliação muito boa", false, "PUBLIC",
            List.of(new CreateReviewTargetCommand(place.getId(), BigDecimal.valueOf(5.0), "Ótimo"))
        ));

        Optional<UserReputation> opt = reputationRepository.findByUserId(author.getId());
        assertTrue(opt.isPresent(), "Snapshot deve ter sido criado/atualizado na criação da Review");
        assertEquals(1, opt.get().getActiveReviews());
        assertEquals(1, opt.get().getDistinctTargetsReviewed());
    }

    // =========================================================================
    // Cenário 3: Nova Review VERIFIED (verifiedReviews <= activeReviews)
    // =========================================================================

    @Test
    @DisplayName("Cenário 3: Review com verificação on-site deve satisfazer verifiedReviews <= activeReviews")
    void scenario3_verifiedReview_maintainsInvariant() {
        User author = createUser("lc_ver");
        Place place = createPlace();

        // Cria review presencialmente dentro do raio de tolerância do local
        reviewService.createReview(new CreateReviewCommand(
            author.getId(), place.getId(), "Visita confirmada", false, "PUBLIC",
            place.getLatitude(), place.getLongitude(), 10.0,
            List.of(new CreateReviewTargetCommand(place.getId(), BigDecimal.valueOf(4.5), "Muito bom"))
        ));

        UserReputation snapshot = reputationService.recalculateAndSave(author.getId());
        assertTrue(snapshot.getVerifiedReviews() <= snapshot.getActiveReviews(),
            "verifiedReviews deve ser menor ou igual a activeReviews");
        assertEquals(1, snapshot.getActiveReviews());
        assertEquals(1, snapshot.getVerifiedReviews());
    }

    // =========================================================================
    // Cenário 4: Review entra em UNDER_REVIEW
    // =========================================================================

    @Test
    @DisplayName("Cenário 4: Review que entra em UNDER_REVIEW deve ser removida dos sinais do snapshot")
    void scenario4_reviewUnderReview_excludedFromSignals() {
        User author = createUser("lc_ur");
        Place place = createPlace();

        ReviewDetailView created = reviewService.createReview(new CreateReviewCommand(
            author.getId(), place.getId(), "Review questionável", false, "PUBLIC",
            List.of(new CreateReviewTargetCommand(place.getId(), BigDecimal.valueOf(3.0), "Texto"))
        ));

        assertEquals(1, reputationRepository.findByUserId(author.getId()).get().getActiveReviews());

        // Coloca a avaliação em moderação preventiva (UNDER_REVIEW)
        Review review = reviewRepository.findById(created.id()).orElseThrow();
        review.markUnderReview();
        reviewRepository.save(review);

        // Recálculo reflete a mudança de status
        UserReputation updated = reputationService.recalculateAndSave(author.getId());
        assertEquals(0, updated.getActiveReviews(), "Review em UNDER_REVIEW não pode contar como ativa");
    }

    // =========================================================================
    // Cenário 5: Review vira REMOVED
    // =========================================================================

    @Test
    @DisplayName("Cenário 5: Review removida (REMOVED) deve ser excluída do cálculo")
    void scenario5_removedReview_excludedFromCalculation() {
        User author = createUser("lc_rem");
        Place place = createPlace();

        ReviewDetailView created = reviewService.createReview(new CreateReviewCommand(
            author.getId(), place.getId(), "Review inadequada", false, "PUBLIC",
            List.of(new CreateReviewTargetCommand(place.getId(), BigDecimal.valueOf(2.0), "Ruim"))
        ));

        Review review = reviewRepository.findById(created.id()).orElseThrow();
        review.markUnderReview();
        // Simula transição para REMOVED no PostgreSQL
        Review removedReview = new Review(
            review.getId(), review.getUserId(), review.getContextPlaceId(), review.getExperienceText(),
            review.isAnonymous(), review.isVerifiedOnSite(), ReviewStatus.REMOVED, review.getVisibility(),
            review.getUserLatitude(), review.getUserLongitude(), review.getLocationAccuracyMeters(),
            review.getCreatedAt(), java.time.Instant.now()
        );
        reviewRepository.save(removedReview);

        UserReputation updated = reputationService.recalculateAndSave(author.getId());
        assertEquals(0, updated.getActiveReviews(), "Review REMOVED não deve pontuar em activeReviews");
    }

    // =========================================================================
    // Cenário 6: Helpful criado e removido
    // =========================================================================

    @Test
    @DisplayName("Cenário 6: Adicionar e remover Helpful altera helpfulVotesReceived de forma reversível")
    void scenario6_helpfulToggled_updatesHelpfulVotes() {
        User author = createUser("lc_h_author");
        User voter  = createUser("lc_h_voter");
        Place place = createPlace();

        ReviewDetailView created = reviewService.createReview(new CreateReviewCommand(
            author.getId(), place.getId(), "Dica excelente", false, "PUBLIC",
            List.of(new CreateReviewTargetCommand(place.getId(), BigDecimal.valueOf(5.0), "Dica"))
        ));

        // 1. Voto adicionado -> helpfulVotesReceived incrementa
        reviewHelpfulService.addHelpful(created.id(), voter.getId());
        UserReputation afterAdd = reputationRepository.findByUserId(author.getId()).orElseThrow();
        assertEquals(1, afterAdd.getHelpfulVotesReceived(), "helpfulVotesReceived deve ser 1 após addHelpful");

        // 2. Voto removido -> helpfulVotesReceived decrementa
        reviewHelpfulService.removeHelpful(created.id(), voter.getId());
        UserReputation afterRemove = reputationRepository.findByUserId(author.getId()).orElseThrow();
        assertEquals(0, afterRemove.getHelpfulVotesReceived(), "helpfulVotesReceived deve voltar a 0 após removeHelpful");
    }

    // =========================================================================
    // Cenário 7: Target distinto
    // =========================================================================

    @Test
    @DisplayName("Cenário 7: Avaliação multi-alvo com 2 targets distintos incrementa distinctTargetsReviewed")
    void scenario7_distinctTargetsReviewed_countedCorrectly() {
        User author = createUser("lc_dt");
        Place place1 = createPlace();
        Place place2 = createPlace();

        reviewService.createReview(new CreateReviewCommand(
            author.getId(), place1.getId(), "Combo local + item", false, "PUBLIC",
            List.of(
                new CreateReviewTargetCommand(place1.getId(), BigDecimal.valueOf(4.0), "Local"),
                new CreateReviewTargetCommand(place2.getId(), BigDecimal.valueOf(5.0), "Segundo item")
            )
        ));

        UserReputation snapshot = reputationRepository.findByUserId(author.getId()).orElseThrow();
        assertEquals(1, snapshot.getActiveReviews());
        assertEquals(2, snapshot.getDistinctTargetsReviewed(), "distinctTargetsReviewed deve contabilizar os 2 alvos distintos");
    }

    // =========================================================================
    // Cenário 8: Recalcular duas vezes sem mudança factual
    // =========================================================================

    @Test
    @DisplayName("Cenário 8: Recalcular duas vezes consecutivas sem mudança factual produz snapshot idêntico")
    void scenario8_recalculateTwiceWithoutChanges_producesIdenticalSignals() {
        User author = createUser("lc_twice");
        Place place = createPlace();

        reviewService.createReview(new CreateReviewCommand(
            author.getId(), place.getId(), "Review para idempotência", false, "PUBLIC",
            List.of(new CreateReviewTargetCommand(place.getId(), BigDecimal.valueOf(4.0), "Texto"))
        ));

        UserReputation firstCalc = reputationService.recalculateAndSave(author.getId());
        UserReputation secondCalc = reputationService.recalculateAndSave(author.getId());

        assertEquals(firstCalc.getVersion(),                secondCalc.getVersion());
        assertEquals(firstCalc.getActiveReviews(),           secondCalc.getActiveReviews());
        assertEquals(firstCalc.getVerifiedReviews(),         secondCalc.getVerifiedReviews());
        assertEquals(firstCalc.getHelpfulVotesReceived(),    secondCalc.getHelpfulVotesReceived());
        assertEquals(firstCalc.getDistinctTargetsReviewed(), secondCalc.getDistinctTargetsReviewed());
    }

    // =========================================================================
    // Concorrência: serialização via Lock Pessimista (Seção 15)
    // =========================================================================

    @Test
    @DisplayName("Concorrência: múltiplas threads recalculando o mesmo usuário executam sob lock pessimista sem corrupção")
    void concurrency_concurrentRecalculations_serializedWithoutDataCorruption() throws Exception {
        User user = createUser("lc_conc");
        Place place = createPlace();

        reviewService.createReview(new CreateReviewCommand(
            user.getId(), place.getId(), "Review base concorrência", false, "PUBLIC",
            List.of(new CreateReviewTargetCommand(place.getId(), BigDecimal.valueOf(5.0), "Base"))
        ));

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threadCount);

        List<Future<UserReputation>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                try {
                    return reputationService.recalculateAndSave(user.getId());
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Todas as threads devem concluir dentro do timeout");
        executor.shutdown();

        for (Future<UserReputation> f : futures) {
            UserReputation r = f.get();
            assertNotNull(r);
            assertEquals(1, r.getActiveReviews());
            assertEquals(1, r.getDistinctTargetsReviewed());
        }

        UserReputation finalSnap = reputationRepository.findByUserId(user.getId()).orElseThrow();
        assertEquals(1, finalSnap.getActiveReviews());
        assertEquals(1, finalSnap.getDistinctTargetsReviewed());
    }

    // =========================================================================
    // Rollback Real (Seção 19)
    // =========================================================================

    @Test
    @DisplayName("Rollback: quando a operação principal falha, o snapshot de reputação não persiste no PostgreSQL")
    void rollback_factualFailure_revertsSnapshot() {
        User user = createUser("lc_rb_user");
        Place place = createPlace();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThrows(RuntimeException.class, () ->
            tx.execute(status -> {
                reviewService.createReview(new CreateReviewCommand(
                    user.getId(), place.getId(), "Texto temporário", false, "PUBLIC",
                    List.of(new CreateReviewTargetCommand(place.getId(), BigDecimal.valueOf(4.0), "Alvo"))
                ));
                throw new RuntimeException("Falha simulada pós-criação da Review para acionar rollback transacional");
            })
        );

        Optional<UserReputation> snapshot = reputationRepository.findByUserId(user.getId());
        assertTrue(snapshot.isEmpty() || snapshot.get().getActiveReviews() == 0,
            "Nenhum snapshot com activeReviews=1 deve existir após o rollback da criação da Review");
    }

    // =========================================================================
    // Usuário Inativo / Inexistente (Seção 7)
    // =========================================================================

    @Test
    @DisplayName("Usuário inativo ou inexistente: não deve gerar snapshot e deve lançar 404")
    void inactiveOrUnknownUser_throwsNotFound_doesNotCreateSnapshot() {
        UUID unknownId = UUID.randomUUID();

        assertThrows(BusinessException.class, () -> reputationService.getReputation(unknownId));
        assertThrows(BusinessException.class, () -> reputationService.recalculateAndSave(unknownId));

        assertTrue(reputationRepository.findByUserId(unknownId).isEmpty(),
            "Nenhum snapshot deve ser persistido para usuário inexistente");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private User createUser(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email  = prefix + "_" + suffix + "@reputation.test";
        User user = new User(null, email, "$2a$10$abcdefghijklmnopqrstuvwxyzABCDEF1234567890",
            AuthProvider.LOCAL, null);
        user = userRepository.save(user);
        Profile profile = new Profile(UUID.randomUUID(), user.getId(), "r_" + suffix,
            "Rep " + suffix, "Bio", null);
        profileRepository.save(profile);
        return user;
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
            null, "Place " + suffix, "place-" + suffix,
            "RESTAURANTE", "Desc", "Rua A, 1", "1", "Bairro", "Cidade", "SP", "BR",
            -23.5505, -46.6333, 50, "USER", false, null, "ACTIVE"
        );
        return placeRepository.save(place);
    }
}
