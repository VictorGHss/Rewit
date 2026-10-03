package com.rewit.infrastructure.persistence;

import com.rewit.application.dto.media.MediaDtos.UploadMediaCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.ReviewDetailView;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.ReviewMediaRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.ReviewMediaService;
import com.rewit.application.service.ReviewService;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.security.MediaRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes Concorrentes com PostgreSQL Real: Limite Estrito de Mídias (Step 21.0 - Requisito 26)")
class MediaConcurrencyIntegrationTest {

    @Autowired
    private ReviewMediaService reviewMediaService;

    @Autowired
    private ReviewMediaRepository reviewMediaRepository;

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
    private MediaRateLimiter mediaRateLimiter;

    private byte[] validJpegBytes;

    @BeforeEach
    void setUp() throws IOException {
        mediaRateLimiter.reset();

        BufferedImage img = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        validJpegBytes = baos.toByteArray();
    }

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
                "Restaurante Concurrency " + suffix,
                "restaurante-conc-" + suffix,
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

    private RateableTarget createTestTarget() {
        RateableTarget target = new RateableTarget(UUID.randomUUID(), TargetType.PLACE);
        return targetRepository.save(target);
    }

    private ReviewDetailView createTestReview(UUID authorId, UUID placeId, UUID targetId) {
        CreateReviewCommand cmd = new CreateReviewCommand(
                authorId,
                placeId,
                "Review para teste concorrente de mídia",
                false,
                "PUBLIC",
                null,
                null,
                null,
                List.of(new CreateReviewTargetCommand(targetId, new BigDecimal("5.0"), "Excelente"))
        );
        return reviewService.createReview(cmd);
    }

    @Test
    @DisplayName("Duas requisições simultâneas disputando o limite de 5 mídias: exatamente 5 devem ser persistidas")
    void testConcurrentMediaUploadRespectsMaxLimitOfFive() throws Exception {
        User author = createTestUser("conc_auth");
        Place place = createTestPlace();
        RateableTarget target = createTestTarget();
        ReviewDetailView review = createTestReview(author.getId(), place.getId(), target.getId());

        // 1. Pré-popula com 4 mídias com sucesso
        for (int i = 0; i < 4; i++) {
            UploadMediaCommand cmd = new UploadMediaCommand(review.id(), author.getId(), validJpegBytes, "pre" + i + ".jpg");
            reviewMediaService.uploadMedia(cmd);
        }

        assertEquals(4, reviewMediaRepository.countActiveByReviewId(review.id()));

        // 2. Dispara 2 threads simultâneas para tentar a 5ª vaga
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        CountDownLatch endLatch = new CountDownLatch(2);

        List<Throwable> errors = new CopyOnWriteArrayList<>();
        List<Object> successes = new CopyOnWriteArrayList<>();

        Runnable task = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                UploadMediaCommand cmd = new UploadMediaCommand(review.id(), author.getId(), validJpegBytes, "photo_concurrent.jpg");
                var view = reviewMediaService.uploadMedia(cmd);
                successes.add(view);
            } catch (InterruptedException | BrokenBarrierException | TimeoutException | RuntimeException t) {
                errors.add(t);
            } finally {
                endLatch.countDown();
            }
        };

        executor.submit(task);
        executor.submit(task);

        assertTrue(endLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // 3. Validações estritas de concorrência
        long finalActiveCount = reviewMediaRepository.countActiveByReviewId(review.id());
        assertEquals(5, finalActiveCount, "O número final de mídias ativas deve ser exatamente 5");
        assertEquals(1, successes.size(), "Exatamente uma das requisições simultâneas deve ter sucesso na última vaga");
        assertEquals(1, errors.size(), "A requisição concorrente excedente deve ter sido rejeitada");
    }
}
