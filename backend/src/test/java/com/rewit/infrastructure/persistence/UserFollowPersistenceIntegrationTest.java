package com.rewit.infrastructure.persistence;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.ReviewDetailView;
import com.rewit.application.dto.ReviewDto.ReviewPublicView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.social.FollowUserSummaryView;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.UserFollowRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.ReviewService;
import com.rewit.application.service.UserFollowService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.persistence.entity.UserFollowJpaEntity;
import com.rewit.infrastructure.persistence.repository.UserFollowJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração de Persistência no PostgreSQL Real - Subsistema Social de Seguidores (Step 15.0)")
class UserFollowPersistenceIntegrationTest {

    @Autowired
    private UserFollowService userFollowService;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @Autowired
    private UserFollowJpaRepository userFollowJpaRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    @Autowired
    private PlaceRepository placeRepository;

    private User createActiveUser() {
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
                "Restaurante Social " + suffix,
                "restaurante-social-" + suffix,
                "RESTAURANTE",
                "Descrição",
                "Rua Social, 100",
                "100",
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

    @Test
    @DisplayName("1. Persistência real de follow e consulta de isFollowing")
    void shouldPersistFollowRelationshipAndCheckIsFollowing() {
        User userA = createActiveUser();
        User userB = createActiveUser();

        assertFalse(userFollowService.isFollowing(userA.getId(), userB.getId()));

        boolean followed = userFollowService.followUser(userA.getId(), userB.getId());
        assertTrue(followed);

        assertTrue(userFollowService.isFollowing(userA.getId(), userB.getId()));
        assertFalse(userFollowService.isFollowing(userB.getId(), userA.getId()), "O relacionamento é unidirecional");
    }

    @Test
    @DisplayName("2. Idempotência sob duplicidade e proteção pela constraint uq_user_follow")
    void shouldBeIdempotentOnDuplicateFollow() {
        User userA = createActiveUser();
        User userB = createActiveUser();

        assertTrue(userFollowService.followUser(userA.getId(), userB.getId()));
        // Segunda chamada idêntica não duplica nem lança erro
        assertFalse(userFollowService.followUser(userA.getId(), userB.getId()));

        long followersCount = userFollowRepository.countFollowers(userB.getId());
        assertEquals(1, followersCount);
    }

    @Test
    @DisplayName("3. Unfollow idempotente remove a relação")
    void shouldUnfollowSuccessfully() {
        User userA = createActiveUser();
        User userB = createActiveUser();

        userFollowService.followUser(userA.getId(), userB.getId());
        assertTrue(userFollowService.isFollowing(userA.getId(), userB.getId()));

        assertTrue(userFollowService.unfollowUser(userA.getId(), userB.getId()));
        assertFalse(userFollowService.isFollowing(userA.getId(), userB.getId()));

        // Segunda remoção é idempotente
        assertFalse(userFollowService.unfollowUser(userA.getId(), userB.getId()));
    }

    @Test
    @DisplayName("4. Check constraint chk_no_self_follow no banco PostgreSQL rejeita auto-seguir")
    void shouldEnforceDatabaseCheckConstraintOnSelfFollow() {
        User userA = createActiveUser();

        assertThrows(BusinessException.class, () ->
                userFollowService.followUser(userA.getId(), userA.getId())
        );

        // Tentativa direta no repositório JPA deve violar a check constraint do banco
        UserFollowJpaEntity selfEntity = new UserFollowJpaEntity(UUID.randomUUID(), userA.getId(), userA.getId(), Instant.now());
        assertThrows(Exception.class, () ->
                userFollowJpaRepository.saveAndFlush(selfEntity)
        );
    }

    @Test
    @DisplayName("5. Concorrência: Múltiplas threads tentando seguir simultaneamente garantem exatamente 1 relação")
    void shouldHandleConcurrentFollowsGracefully() throws Exception {
        User userA = createActiveUser();
        User userB = createActiveUser();

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);

        List<Future<Boolean>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                return userFollowRepository.follow(userA.getId(), userB.getId());
            }));
        }

        latch.countDown();
        int trueCount = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(5, TimeUnit.SECONDS)) {
                trueCount++;
            }
        }
        executor.shutdown();

        assertEquals(1, trueCount, "Exatamente uma thread deve ter obtido true na criação inicial");
        assertTrue(userFollowRepository.isFollowing(userA.getId(), userB.getId()));
        assertEquals(1, userFollowRepository.countFollowers(userB.getId()));
    }

    @Test
    @DisplayName("6. Paginação de following e followers sem duplicatas")
    void shouldPaginateFollowingAndFollowers() {
        User target = createActiveUser();
        User f1 = createActiveUser();
        User f2 = createActiveUser();
        User f3 = createActiveUser();

        userFollowService.followUser(f1.getId(), target.getId());
        userFollowService.followUser(f2.getId(), target.getId());
        userFollowService.followUser(f3.getId(), target.getId());

        PageResult<FollowUserSummaryView> followersPage = userFollowService.getFollowers(target.getId(), 0, 2);
        assertEquals(2, followersPage.content().size());
        assertEquals(3, followersPage.totalElements());
        assertFalse(followersPage.isLast());

        PageResult<FollowUserSummaryView> page2 = userFollowService.getFollowers(target.getId(), 1, 2);
        assertEquals(1, page2.content().size());
        assertTrue(page2.isLast());

        // Quem f1 segue (apenas target)
        PageResult<FollowUserSummaryView> f1Following = userFollowService.getFollowing(f1.getId(), 0, 10);
        assertEquals(1, f1Following.content().size());
        assertEquals(target.getId(), f1Following.content().getFirst().userId());
    }

    @Test
    @DisplayName("7. INTEGRAÇÃO REAL: Reviews FOLLOWERS são acessíveis por seguidores e bloqueadas para não-seguidores")
    void shouldEnforceFollowersReviewVisibilityForFollowersAndNonFollowers() {
        Place place = createPlace();
        RateableTarget target = rateableTargetRepository.findById(place.getId()).orElseThrow();

        User authorB = createActiveUser();
        User followerA = createActiveUser();
        User nonFollowerC = createActiveUser();

        // Follower A segue Author B
        userFollowService.followUser(followerA.getId(), authorB.getId());

        // Author B cria uma Review com visibility = FOLLOWERS
        CreateReviewCommand cmdFollowers = new CreateReviewCommand(
                authorB.getId(),
                place.getId(),
                "Review exclusiva para seguidores do autor",
                false,
                "FOLLOWERS",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("4.8"), "Ótimo"))
        );
        ReviewDetailView reviewDetail = reviewService.createReview(cmdFollowers);
        UUID reviewId = reviewDetail.id();

        // 1. GET INDIVIDUAL:
        // Follower A acessa -> SUCESSO (200)
        ReviewPublicView viewA = reviewService.getReviewPublicView(reviewId, followerA.getId());
        assertNotNull(viewA);
        assertEquals("FOLLOWERS", viewA.visibility());
        assertEquals("Review exclusiva para seguidores do autor", viewA.experienceText());

        // Non-follower C acessa -> 403 FORBIDDEN
        BusinessException exC = assertThrows(BusinessException.class, () ->
                reviewService.getReviewPublicView(reviewId, nonFollowerC.getId())
        );
        assertEquals("FORBIDDEN", exC.getErrorCode());

        // Autor B acessa -> SUCESSO
        ReviewPublicView viewAuthor = reviewService.getReviewPublicView(reviewId, authorB.getId());
        assertNotNull(viewAuthor);

        // 2. LISTAGEM POR TARGET (GET /api/v1/targets/{id}/reviews):
        // Follower A consulta -> A review FOLLOWERS aparece na lista!
        PageResult<ReviewPublicView> targetReviewsForA = reviewService.findReviewsByTarget(
                target.getId(), 0, 10, "newest", false, followerA.getId()
        );
        assertTrue(targetReviewsForA.content().stream().anyMatch(r -> r.id().equals(reviewId)),
                "Seguidor A deve ver a review FOLLOWERS na listagem por target");

        // Non-follower C consulta -> A review FOLLOWERS NÃO aparece na lista!
        PageResult<ReviewPublicView> targetReviewsForC = reviewService.findReviewsByTarget(
                target.getId(), 0, 10, "newest", false, nonFollowerC.getId()
        );
        assertFalse(targetReviewsForC.content().stream().anyMatch(r -> r.id().equals(reviewId)),
                "Não-seguidor C NÃO deve ver a review FOLLOWERS na listagem por target");

        // 3. SEGURANÇA: Review PRIVATE de B continua bloqueada para Follower A
        CreateReviewCommand cmdPrivate = new CreateReviewCommand(
                authorB.getId(),
                place.getId(),
                "Review privada do autor",
                false,
                "PRIVATE",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("3.0"), null))
        );
        ReviewDetailView privateReview = reviewService.createReview(cmdPrivate);

        assertThrows(BusinessException.class, () ->
                reviewService.getReviewPublicView(privateReview.id(), followerA.getId()),
                "Follower A NÃO pode acessar review PRIVATE de B"
        );

        PageResult<ReviewPublicView> targetReviewsAfterPrivate = reviewService.findReviewsByTarget(
                target.getId(), 0, 10, "newest", false, followerA.getId()
        );
        assertFalse(targetReviewsAfterPrivate.content().stream().anyMatch(r -> r.id().equals(privateReview.id())),
                "Follower A NÃO deve ver review PRIVATE de B na listagem");
    }
}
