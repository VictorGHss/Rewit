package com.rewit.infrastructure.persistence;

import com.rewit.application.dto.user.UserDtos.PublicUserProfileView;
import com.rewit.application.port.*;
import com.rewit.application.service.UserService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração PostgreSQL Real - Perfil Público e Estatísticas Factuais (Step 18.0)")
class UserPublicProfilePersistenceIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ReviewTargetRepository reviewTargetRepository;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @Autowired
    private ReviewReactionRepository reviewReactionRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User createUser(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User(null, prefix + "-" + suffix + "@rewit.com", "hash123", AuthProvider.LOCAL, null);
        User savedUser = userRepository.save(user);

        Profile profile = new Profile(UUID.randomUUID(), savedUser.getId(), prefix + "_" + suffix, "Nome " + suffix, "Bio " + suffix, null);
        profileRepository.save(profile);

        return savedUser;
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
                null,
                "Restaurante Profile " + suffix,
                "restaurante-profile-" + suffix,
                "RESTAURANTE",
                "Descrição",
                "Rua das Flores, 100",
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

    private RateableTarget createTarget() {
        RateableTarget target = new RateableTarget(UUID.randomUUID(), TargetType.PLACE);
        return rateableTargetRepository.save(target);
    }

    private Review createReview(User author, Place place, RateableTarget target,
                                 ReviewStatus status, boolean isVerifiedOnSite, String visibility) {
        UUID reviewId = UUID.randomUUID();
        // A Review é persistida com isVerifiedOnSite = false para cumprir o trigger fn_prevent_unverified_review_flag
        Review review = new Review(
                reviewId,
                author.getId(),
                place.getId(),
                "Experiência da avaliação",
                false,
                false,
                status,
                visibility,
                -23.5505,
                -46.6333,
                10.0,
                Instant.now(),
                Instant.now()
        );
        ReviewTarget reviewTarget = new ReviewTarget(UUID.randomUUID(), reviewId, target.getId(), BigDecimal.valueOf(4.5), "Nota");
        review.addTarget(reviewTarget);
        Review saved = reviewRepository.save(review);
        reviewTargetRepository.save(reviewTarget);

        if (isVerifiedOnSite) {
            UUID checkInId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO check_ins (id, review_id, user_id, place_id, coordinates, distance_to_centroid_meters, status, verified_at) " +
                            "VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(-46.6333, -23.5505), 4326)::geography, 10.0, 'VERIFIED', NOW())",
                    checkInId, reviewId, author.getId(), place.getId()
            );
        }

        return saved;
    }

    @Test
    @DisplayName("Cenário Seção 18: User A com 4 ACTIVE (2 verificadas), 1 REMOVED, 1 UNDER_REVIEW, followers, following, reações HELPFUL e LIKE")
    void shouldCalculateAccurateFactualStatisticsForUserA() {
        User userA = createUser("usera");
        User userB = createUser("userb");
        User userC = createUser("userc");
        User userD = createUser("userd");
        User userE = createUser("usere");

        Place place = createPlace();
        RateableTarget target = createTarget();

        // 4 Reviews ACTIVE: 2 com is_verified_on_site = true, 2 com is_verified_on_site = false
        Review r1ActiveVerif = createReview(userA, place, target, ReviewStatus.ACTIVE, true, "PUBLIC");
        Review r2ActiveVerif = createReview(userA, place, target, ReviewStatus.ACTIVE, true, "FOLLOWERS");
        Review r3ActiveNotVerif = createReview(userA, place, target, ReviewStatus.ACTIVE, false, "PUBLIC");
        Review r4ActivePrivate = createReview(userA, place, target, ReviewStatus.ACTIVE, false, "PRIVATE");

        // 1 Review REMOVED
        Review r5Removed = createReview(userA, place, target, ReviewStatus.REMOVED, true, "PUBLIC");

        // 1 Review UNDER_REVIEW
        Review r6UnderReview = createReview(userA, place, target, ReviewStatus.UNDER_REVIEW, false, "PUBLIC");

        // Múltiplos Followers (User B, C e D seguem User A) -> followersCount = 3
        userFollowRepository.follow(userB.getId(), userA.getId());
        userFollowRepository.follow(userC.getId(), userA.getId());
        userFollowRepository.follow(userD.getId(), userA.getId());

        // Múltiplos Following (User A segue User D e User E) -> followingCount = 2
        userFollowRepository.follow(userA.getId(), userD.getId());
        userFollowRepository.follow(userA.getId(), userE.getId());

        // Helpful em Reviews ACTIVE:
        // User B vota HELPFUL em r1ActiveVerif
        reviewReactionRepository.addHelpful(r1ActiveVerif.getId(), userB.getId());
        // User C vota HELPFUL em r1ActiveVerif
        reviewReactionRepository.addHelpful(r1ActiveVerif.getId(), userC.getId());
        // User D vota HELPFUL em r3ActiveNotVerif
        reviewReactionRepository.addHelpful(r3ActiveNotVerif.getId(), userD.getId());

        // Helpful em Review REMOVED (não deve entrar no contador)
        reviewReactionRepository.addHelpful(r5Removed.getId(), userB.getId());

        // Helpful em Review UNDER_REVIEW (não deve entrar no contador)
        reviewReactionRepository.addHelpful(r6UnderReview.getId(), userC.getId());

        // Reação de outro tipo (ex: 'LIKE') em Review ACTIVE (não deve entrar no helpfulVotesReceived)
        jdbcTemplate.update(
                "INSERT INTO review_reactions (review_id, user_id, reaction_type) VALUES (?, ?, ?)",
                r1ActiveVerif.getId(), userE.getId(), "LIKE"
        );

        // Reação dada por User A em review de outro usuário (User B) (não deve contar nas recebidas por User A)
        Review rUserB = createReview(userB, place, target, ReviewStatus.ACTIVE, false, "PUBLIC");
        reviewReactionRepository.addHelpful(rUserB.getId(), userA.getId());

        // Requester: User B (que segue User A)
        PublicUserProfileView profileView = userService.getPublicProfile(userA.getId(), userB.getId());

        assertNotNull(profileView);
        assertEquals(userA.getId(), profileView.id());
        assertNotNull(profileView.handle());
        assertNotNull(profileView.displayName());
        assertTrue(profileView.isFollowing());

        // Verificação explícita dos fixtures r2ActiveVerif e r4ActivePrivate:
        assertNotNull(r2ActiveVerif.getId());
        assertTrue(reviewRepository.findById(r2ActiveVerif.getId()).orElseThrow().isVerifiedOnSite(),
                "r2ActiveVerif deve estar confirmada como verificada no local pelo CheckIn");
        assertNotNull(r4ActivePrivate.getId());
        assertEquals("PRIVATE", r4ActivePrivate.getVisibility());
        assertEquals(userA.getId(), r4ActivePrivate.getUserId());

        // Verificação das Estatísticas Factuais:
        assertEquals(4L, profileView.stats().totalReviews(), "totalReviews deve conter apenas as 4 ACTIVE (incluindo PRIVATE)");
        assertEquals(2L, profileView.stats().verifiedReviewsCount(), "verifiedReviewsCount deve conter apenas as 2 ACTIVE verificadas");
        assertEquals(3L, profileView.stats().followersCount(), "followersCount deve conter as 3 relações reais");
        assertEquals(2L, profileView.stats().followingCount(), "followingCount deve conter as 2 relações reais");
        assertEquals(3L, profileView.stats().helpfulVotesReceived(), "helpfulVotesReceived deve conter apenas os 3 votos HELPFUL em Reviews ACTIVE de User A");

        // Requester: User E (que NÃO segue User A)
        PublicUserProfileView profileViewRequesterE = userService.getPublicProfile(userA.getId(), userE.getId());
        assertFalse(profileViewRequesterE.isFollowing());

        // Requester: Próprio User A (self-view)
        PublicUserProfileView profileViewSelf = userService.getPublicProfile(userA.getId(), userA.getId());
        assertFalse(profileViewSelf.isFollowing(), "isFollowing deve ser false quando o requester for o próprio usuário");
    }

    @Test
    @DisplayName("Cenário Seção 19 e Seção 4: Consistência incremental de contadores à medida que dados são criados")
    void shouldReflectIncrementalChangesDeterministically() {
        User user = createUser("inc");
        User otherUser = createUser("other");
        Place place = createPlace();
        RateableTarget target = createTarget();

        // 1. Inicial: tudo zerado
        PublicUserProfileView v0 = userService.getPublicProfile(user.getId(), otherUser.getId());
        assertEquals(0L, v0.stats().totalReviews());
        assertEquals(0L, v0.stats().verifiedReviewsCount());
        assertEquals(0L, v0.stats().followersCount());
        assertEquals(0L, v0.stats().followingCount());
        assertEquals(0L, v0.stats().helpfulVotesReceived());

        // 2. Cria 1 review ativa não verificada
        Review r1 = createReview(user, place, target, ReviewStatus.ACTIVE, false, "PUBLIC");
        PublicUserProfileView v1 = userService.getPublicProfile(user.getId(), otherUser.getId());
        assertEquals(1L, v1.stats().totalReviews());
        assertEquals(0L, v1.stats().verifiedReviewsCount());

        // 3. Cria 1 review ativa verificada no local
        Review r2 = createReview(user, place, target, ReviewStatus.ACTIVE, true, "PUBLIC");
        assertNotNull(r2.getId());
        assertTrue(reviewRepository.findById(r2.getId()).orElseThrow().isVerifiedOnSite(),
                "r2 deve ser persistida e sincronizada como verificada via CheckIn");
        PublicUserProfileView v2 = userService.getPublicProfile(user.getId(), otherUser.getId());
        assertEquals(2L, v2.stats().totalReviews());
        assertEquals(1L, v2.stats().verifiedReviewsCount());

        // 4. Cria 1 review ativa PRIVATE -> totalReviews incrementa, verifiedReviewsCount inalterado
        Review r3Private = createReview(user, place, target, ReviewStatus.ACTIVE, false, "PRIVATE");
        assertNotNull(r3Private.getId());
        PublicUserProfileView v2Private = userService.getPublicProfile(user.getId(), otherUser.getId());
        assertEquals(3L, v2Private.stats().totalReviews());
        assertEquals(1L, v2Private.stats().verifiedReviewsCount());

        // 5. Cria 1 review REMOVED e 1 UNDER_REVIEW -> contadores NÃO alteram
        createReview(user, place, target, ReviewStatus.REMOVED, false, "PUBLIC");
        createReview(user, place, target, ReviewStatus.UNDER_REVIEW, false, "PUBLIC");
        PublicUserProfileView v2Inactive = userService.getPublicProfile(user.getId(), otherUser.getId());
        assertEquals(3L, v2Inactive.stats().totalReviews(), "REMOVED e UNDER_REVIEW não devem entrar no totalReviews");
        assertEquals(1L, v2Inactive.stats().verifiedReviewsCount());

        // 6. Recebe follow de outro usuário
        userFollowRepository.follow(otherUser.getId(), user.getId());
        PublicUserProfileView v3 = userService.getPublicProfile(user.getId(), otherUser.getId());
        assertEquals(1L, v3.stats().followersCount());
        assertEquals(0L, v3.stats().followingCount());

        // 7. Segue outro usuário
        userFollowRepository.follow(user.getId(), otherUser.getId());
        PublicUserProfileView v4 = userService.getPublicProfile(user.getId(), otherUser.getId());
        assertEquals(1L, v4.stats().followersCount());
        assertEquals(1L, v4.stats().followingCount());

        // 8. Recebe Helpful em r1
        reviewReactionRepository.addHelpful(r1.getId(), otherUser.getId());
        PublicUserProfileView v5 = userService.getPublicProfile(user.getId(), otherUser.getId());
        assertEquals(1L, v5.stats().helpfulVotesReceived());

        // 9. Reação de outro tipo (ex: 'LIKE') não deve alterar helpfulVotesReceived
        jdbcTemplate.update(
                "INSERT INTO review_reactions (review_id, user_id, reaction_type) VALUES (?, ?, ?)",
                r1.getId(), otherUser.getId(), "LIKE"
        );
        PublicUserProfileView v5Like = userService.getPublicProfile(user.getId(), otherUser.getId());
        assertEquals(1L, v5Like.stats().helpfulVotesReceived(), "Reação diferente de HELPFUL não deve alterar o contador");

        // 10. Remove Helpful (toggle/unvote)
        reviewReactionRepository.removeHelpful(r1.getId(), otherUser.getId());
        PublicUserProfileView v6 = userService.getPublicProfile(user.getId(), otherUser.getId());
        assertEquals(0L, v6.stats().helpfulVotesReceived());
    }

    @Test
    @DisplayName("Seção 5: Review PRIVATE incrementa totalReviews sem expor nenhum conteúdo no perfil público")
    void shouldIncludePrivateReviewInTotalReviewsWithoutExposingPrivateDetails() {
        User targetUser = createUser("targetpriv");
        User requester = createUser("reqpriv");
        Place place = createPlace();
        RateableTarget target = createTarget();

        Review privateReview = createReview(targetUser, place, target, ReviewStatus.ACTIVE, false, "PRIVATE");
        assertNotNull(privateReview.getId());

        PublicUserProfileView profileView = userService.getPublicProfile(targetUser.getId(), requester.getId());

        assertEquals(1L, profileView.stats().totalReviews());
        assertEquals(0L, profileView.stats().verifiedReviewsCount());
        // O DTO público não contém referências ao conteúdo ou ID da review privada
        assertNotNull(profileView.handle());
        assertNotNull(profileView.displayName());
    }

    @Test
    @DisplayName("Seção 6: Helpful recebido conta apenas em Reviews ACTIVE e é desconsiderado se Review for REMOVED")
    void shouldCountOnlyHelpfulReactionsOnActiveReviewsAndIgnoreRemovedReviews() {
        User targetUser = createUser("targethlp");
        User userA = createUser("usra");
        User userB = createUser("usrb");
        User userC = createUser("usrc");
        Place place = createPlace();
        RateableTarget target = createTarget();

        Review activeReview = createReview(targetUser, place, target, ReviewStatus.ACTIVE, false, "PUBLIC");

        // User A e User B votam HELPFUL
        reviewReactionRepository.addHelpful(activeReview.getId(), userA.getId());
        reviewReactionRepository.addHelpful(activeReview.getId(), userB.getId());

        // User C vota com outro reaction_type ('LIKE')
        jdbcTemplate.update(
                "INSERT INTO review_reactions (review_id, user_id, reaction_type) VALUES (?, ?, ?)",
                activeReview.getId(), userC.getId(), "LIKE"
        );

        PublicUserProfileView viewBeforeRemoval = userService.getPublicProfile(targetUser.getId(), userA.getId());
        assertEquals(2L, viewBeforeRemoval.stats().helpfulVotesReceived(), "Apenas os 2 votos HELPFUL devem ser contabilizados");

        // Marca a review como REMOVED
        jdbcTemplate.update("UPDATE reviews SET status = 'REMOVED' WHERE id = ?", activeReview.getId());

        PublicUserProfileView viewAfterRemoval = userService.getPublicProfile(targetUser.getId(), userA.getId());
        assertEquals(0L, viewAfterRemoval.stats().helpfulVotesReceived(), "Helpful em Review REMOVED não deve ser contabilizado");
        assertEquals(0L, viewAfterRemoval.stats().totalReviews(), "Review REMOVED não deve ser contabilizada no totalReviews");
    }

    @Test
    @DisplayName("Seção 7: Followers e Following contam estritamente as direções corretas sem inversão de colunas")
    void shouldCountFollowersAndFollowingWithStrictColumnDirection() {
        User targetUser = createUser("targetdir");
        User a = createUser("usra");
        User b = createUser("usrb");
        User c = createUser("usrc");
        User d = createUser("usrd");

        // A -> segue Target; B -> segue Target (followersCount = 2)
        userFollowRepository.follow(a.getId(), targetUser.getId());
        userFollowRepository.follow(b.getId(), targetUser.getId());

        // Target -> segue C; Target -> segue D (followingCount = 2)
        userFollowRepository.follow(targetUser.getId(), c.getId());
        userFollowRepository.follow(targetUser.getId(), d.getId());

        PublicUserProfileView profileView = userService.getPublicProfile(targetUser.getId(), a.getId());

        assertEquals(2L, profileView.stats().followersCount(), "followersCount deve ser exatamente 2");
        assertEquals(2L, profileView.stats().followingCount(), "followingCount deve ser exatamente 2");
        assertTrue(profileView.isFollowing(), "Requester A segue targetUser, logo isFollowing deve ser true");
    }

    @Test
    @DisplayName("Usuário inativo ou soft-deleted retorna 404 USER_NOT_FOUND")
    void shouldReturn404WhenTargetUserIsInactiveOrSoftDeleted() {
        User user = createUser("deleted");
        User requester = createUser("req");

        user.softDelete();
        userRepository.save(user);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                userService.getPublicProfile(user.getId(), requester.getId()));

        assertEquals("USER_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }
}
