package com.rewit.infrastructure.persistence;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.ReviewDetailView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.report.ReportDtos.CreateReportCommand;
import com.rewit.application.port.*;
import com.rewit.application.service.ReportService;
import com.rewit.application.service.ReportService.CreateReportResult;
import com.rewit.application.service.ReviewHelpfulService;
import com.rewit.application.service.ReviewService;
import com.rewit.application.service.UserFollowService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.*;
import com.rewit.infrastructure.persistence.entity.ReportJpaEntity;
import com.rewit.infrastructure.persistence.repository.ReportJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração PostgreSQL Real: Persistência de Reports e Moderação Preventiva (Step 19.0)")
class ReportPersistenceIntegrationTest {

    @Autowired
    private ReportService reportService;

    @Autowired
    private ReportJpaRepository reportJpaRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private ReviewHelpfulService helpfulService;

    @Autowired
    private UserFollowService userFollowService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateableTargetRepository targetRepository;

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
                "Restaurante " + suffix,
                "restaurante-" + suffix,
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

    private ReviewDetailView createTestReview(UUID authorId, UUID placeId, UUID targetId, String visibility) {
        CreateReviewCommand cmd = new CreateReviewCommand(
                authorId,
                placeId,
                "Review para teste de moderação e denúncias comunitárias",
                false,
                visibility,
                null,
                null,
                null,
                List.of(new CreateReviewTargetCommand(targetId, new BigDecimal("4.5"), "Muito bom"))
        );
        return reviewService.createReview(cmd);
    }

    @Test
    @DisplayName("1. Persistência: salva Report no PostgreSQL com FKs válidas e status PENDING")
    void persistReport_valid_shouldPersistInDatabase() {
        User author = createTestUser("rep_auth1");
        User reporter = createTestUser("rep_user1");
        Place place = createTestPlace();
        RateableTarget target = createTestTarget(place.getId());
        ReviewDetailView review = createTestReview(author.getId(), place.getId(), target.getId(), "PUBLIC");

        CreateReportCommand cmd = new CreateReportCommand(
                reporter.getId(),
                review.id(),
                ReportReason.SPAM,
                "Mensagem de spam repetida"
        );

        CreateReportResult result = reportService.createReport(cmd);

        assertNotNull(result);
        assertTrue(result.newlyCreated());
        assertNotNull(result.report().getId());

        // Verifica diretamente no JPA
        Optional<ReportJpaEntity> fromDb = reportJpaRepository.findById(result.report().getId());
        assertTrue(fromDb.isPresent());
        assertEquals(review.id(), fromDb.get().getReviewId());
        assertEquals(reporter.getId(), fromDb.get().getReporterUserId());
        assertEquals("SPAM", fromDb.get().getReason());
        assertEquals("Mensagem de spam repetida", fromDb.get().getDetail());
        assertEquals("PENDING", fromDb.get().getStatus());
        assertNotNull(fromDb.get().getCreatedAt());
    }

    @Test
    @DisplayName("2. Idempotência no PostgreSQL: denúncia repetida retorna existente sem duplicar linha")
    void persistReport_duplicate_shouldBeIdempotent() {
        User author = createTestUser("rep_auth2");
        User reporter = createTestUser("rep_user2");
        Place place = createTestPlace();
        RateableTarget target = createTestTarget(place.getId());
        ReviewDetailView review = createTestReview(author.getId(), place.getId(), target.getId(), "PUBLIC");

        CreateReportCommand cmd1 = new CreateReportCommand(reporter.getId(), review.id(), ReportReason.FRAUD, "Fraude 1");
        CreateReportResult res1 = reportService.createReport(cmd1);
        assertTrue(res1.newlyCreated());

        // Segunda tentativa com motivo e detalhe diferentes para o mesmo usuário e review
        CreateReportCommand cmd2 = new CreateReportCommand(reporter.getId(), review.id(), ReportReason.SPAM, "Spam diferente");
        CreateReportResult res2 = reportService.createReport(cmd2);
        assertFalse(res2.newlyCreated());
        assertEquals(res1.report().getId(), res2.report().getId());
        assertEquals(ReportReason.FRAUD, res2.report().getReason(), "Deve preservar o motivo original da denúncia");
        assertEquals("Fraude 1", res2.report().getDetail(), "Deve preservar o detalhe original da denúncia");

        // Contagem no banco deve ser estritamente 1
        long count = reportJpaRepository.countPendingByReviewId(review.id());
        assertEquals(1L, count);

        // Confirma no banco que os dados do registro permanecem os originais
        ReportJpaEntity fromDb = reportJpaRepository.findById(res1.report().getId()).orElseThrow();
        assertEquals("FRAUD", fromDb.getReason());
        assertEquals("Fraude 1", fromDb.getDetail());
    }

    @Test
    @DisplayName("3. Threshold de 3 denúncias: transiciona atomicamente de ACTIVE para UNDER_REVIEW")
    void reportThreshold_threeDistinctReporters_shouldTransitionToUnderReview() {
        User author = createTestUser("rep_auth3");
        User reporterA = createTestUser("rep_usrA");
        User reporterB = createTestUser("rep_usrB");
        User reporterC = createTestUser("rep_usrC");
        Place place = createTestPlace();
        RateableTarget target = createTestTarget(place.getId());
        ReviewDetailView review = createTestReview(author.getId(), place.getId(), target.getId(), "PUBLIC");

        // 1º Report (A)
        reportService.createReport(new CreateReportCommand(reporterA.getId(), review.id(), ReportReason.HARASSMENT, null));
        Review afterA = reviewRepository.findById(review.id()).orElseThrow();
        assertEquals(ReviewStatus.ACTIVE, afterA.getStatus(), "Após 1º report, deve continuar ACTIVE");

        // 2º Report (B)
        reportService.createReport(new CreateReportCommand(reporterB.getId(), review.id(), ReportReason.HARASSMENT, null));
        Review afterB = reviewRepository.findById(review.id()).orElseThrow();
        assertEquals(ReviewStatus.ACTIVE, afterB.getStatus(), "Após 2º report, deve continuar ACTIVE");

        // 3º Report (C) -> atinge o limiar
        reportService.createReport(new CreateReportCommand(reporterC.getId(), review.id(), ReportReason.HARASSMENT, null));
        Review afterC = reviewRepository.findById(review.id()).orElseThrow();
        assertEquals(ReviewStatus.UNDER_REVIEW, afterC.getStatus(), "Após 3º report distinto, deve transicionar para UNDER_REVIEW");
    }

    @Test
    @DisplayName("4. Mesmo denunciante repetido não atinge o threshold")
    void reportThreshold_sameReporterMultipleTimes_shouldNotTriggerUnderReview() {
        User author = createTestUser("rep_auth4");
        User reporter = createTestUser("rep_usr4");
        Place place = createTestPlace();
        RateableTarget target = createTestTarget(place.getId());
        ReviewDetailView review = createTestReview(author.getId(), place.getId(), target.getId(), "PUBLIC");

        for (int i = 0; i < 5; i++) {
            reportService.createReport(new CreateReportCommand(reporter.getId(), review.id(), ReportReason.SPAM, "Tentativa " + i));
        }

        Review current = reviewRepository.findById(review.id()).orElseThrow();
        assertEquals(ReviewStatus.ACTIVE, current.getStatus(), "Mesmo reporter repetido não pode contar mais de uma vez");
    }

    @Test
    @DisplayName("5. Concorrência real: 3 denúncias simultâneas em threads concorrentes transicionam para UNDER_REVIEW sem erro 500")
    void concurrentReports_threeThreads_shouldAtomicallyTransitionToUnderReview() throws Exception {
        User author = createTestUser("rep_auth5");
        User rep1 = createTestUser("rep_c1");
        User rep2 = createTestUser("rep_c2");
        User rep3 = createTestUser("rep_c3");
        Place place = createTestPlace();
        RateableTarget target = createTestTarget(place.getId());
        ReviewDetailView review = createTestReview(author.getId(), place.getId(), target.getId(), "PUBLIC");

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CyclicBarrier barrier = new CyclicBarrier(3);
        CountDownLatch endLatch = new CountDownLatch(3);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        Runnable task1 = () -> {
            try {
                barrier.await();
                reportService.createReport(new CreateReportCommand(rep1.getId(), review.id(), ReportReason.SPAM, "Conc 1"));
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                endLatch.countDown();
            }
        };

        Runnable task2 = () -> {
            try {
                barrier.await();
                reportService.createReport(new CreateReportCommand(rep2.getId(), review.id(), ReportReason.HARASSMENT, "Conc 2"));
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                endLatch.countDown();
            }
        };

        Runnable task3 = () -> {
            try {
                barrier.await();
                reportService.createReport(new CreateReportCommand(rep3.getId(), review.id(), ReportReason.FRAUD, "Conc 3"));
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                endLatch.countDown();
            }
        };

        executor.submit(task1);
        executor.submit(task2);
        executor.submit(task3);

        assertTrue(endLatch.await(10, TimeUnit.SECONDS), "Todas as threads devem concluir em até 10 segundos");
        executor.shutdown();

        assertTrue(errors.isEmpty(), "Nenhum erro concorrente deve ocorrer: " + errors);

        // Verifica estado final no banco de dados
        Review finalReview = reviewRepository.findById(review.id()).orElseThrow();
        assertEquals(ReviewStatus.UNDER_REVIEW, finalReview.getStatus(), "Review deve terminar em UNDER_REVIEW");

        long reportCount = reportJpaRepository.countPendingByReviewId(review.id());
        assertEquals(3L, reportCount, "Devem existir exatamente 3 reports no banco de dados");
    }

    @Test
    @DisplayName("6. Impacto nos Steps Anteriores: Review UNDER_REVIEW desaparece do Target Listing, Feed, rejeita Helpful e novas denúncias")
    void underReview_impactOnPreviousSteps_shouldHideFromListingAndFeedAndRejectHelpful() {
        User author = createTestUser("rep_auth6");
        User follower = createTestUser("rep_fol6");
        User repA = createTestUser("rep_6a");
        User repB = createTestUser("rep_6b");
        User repC = createTestUser("rep_6c");

        // follower segue author
        userFollowService.followUser(follower.getId(), author.getId());

        Place place = createTestPlace();
        RateableTarget target = createTestTarget(place.getId());
        ReviewDetailView review = createTestReview(author.getId(), place.getId(), target.getId(), "PUBLIC");

        // 1. Antes de atingir o threshold: aparece na listagem do target e no Feed
        PageResult<ReviewRepository.ReviewWithTarget> initialListing = reviewRepository.findByTarget(
                target.getId(), follower.getId(), false, "recent", 0, 10
        );
        assertTrue(initialListing.content().stream().anyMatch(r -> r.review().getId().equals(review.id())));

        PageResult<Review> initialFeed = reviewRepository.findFeedByFollowing(follower.getId(), 0, 10);
        assertTrue(initialFeed.content().stream().anyMatch(r -> r.getId().equals(review.id())));

        // 2. Aplica 3 denúncias distintas -> Review vira UNDER_REVIEW
        reportService.createReport(new CreateReportCommand(repA.getId(), review.id(), ReportReason.SPAM, null));
        reportService.createReport(new CreateReportCommand(repB.getId(), review.id(), ReportReason.SPAM, null));
        reportService.createReport(new CreateReportCommand(repC.getId(), review.id(), ReportReason.SPAM, null));

        Review moderatedReview = reviewRepository.findById(review.id()).orElseThrow();
        assertEquals(ReviewStatus.UNDER_REVIEW, moderatedReview.getStatus());

        // 3. Verifica Target Listing: não aparece mais
        PageResult<ReviewRepository.ReviewWithTarget> postListing = reviewRepository.findByTarget(
                target.getId(), follower.getId(), false, "recent", 0, 10
        );
        assertFalse(postListing.content().stream().anyMatch(r -> r.review().getId().equals(review.id())),
                "Review UNDER_REVIEW não deve aparecer na listagem do target");

        // 4. Verifica Feed: não aparece mais
        PageResult<Review> postFeed = reviewRepository.findFeedByFollowing(follower.getId(), 0, 10);
        assertFalse(postFeed.content().stream().anyMatch(r -> r.getId().equals(review.id())),
                "Review UNDER_REVIEW não deve aparecer no Feed");

        // 5. Verifica Helpful: rejeita tentativa de registrar Helpful
        BusinessException helpfulEx = assertThrows(BusinessException.class, () ->
                helpfulService.addHelpful(review.id(), follower.getId())
        );
        assertEquals(HttpStatus.NOT_FOUND, helpfulEx.getStatus());
        assertEquals("REVIEW_NOT_FOUND", helpfulEx.getErrorCode());

        // 6. Verifica novas denúncias: rejeita tentativa de denunciar Review UNDER_REVIEW
        User repD = createTestUser("rep_6d");
        BusinessException reportEx = assertThrows(BusinessException.class, () ->
                reportService.createReport(new CreateReportCommand(repD.getId(), review.id(), ReportReason.SPAM, null))
        );
        assertEquals(HttpStatus.NOT_FOUND, reportEx.getStatus());
        assertEquals("REVIEW_NOT_FOUND", reportEx.getErrorCode());
    }
}
