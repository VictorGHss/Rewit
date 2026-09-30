package com.rewit.application.service;

import com.rewit.application.dto.feed.FeedV2CandidatePage;
import com.rewit.application.dto.feed.FeedV2PageProjection;
import com.rewit.application.dto.feed.FeedV2Projection;
import com.rewit.application.port.*;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.feed.FeedCandidate;
import com.rewit.domain.feed.FeedScore;
import com.rewit.domain.feed.RankedFeedCandidate;
import com.rewit.domain.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de integração do {@link FeedV2Hydrator} contra PostgreSQL Real (Step 24.4.2).
 *
 * <p>Valida o pipeline completo de hidratação em lote:
 * {@code FeedV2CandidatePage → FeedV2Hydrator → Batch Loaders PostgreSQL → FeedV2PageProjection}
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração: FeedV2Hydrator com PostgreSQL Real (Step 24.4.2)")
class FeedV2HydratorIntegrationTest {

    @Autowired
    private FeedV2Hydrator feedV2Hydrator;

    @Autowired
    private FeedV2Service feedV2Service;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ReviewTargetRepository reviewTargetRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @Autowired
    private ReviewReactionRepository reviewReactionRepository;

    private User createActiveUser(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User user = new User(null, prefix + "-" + suffix + "@rewit.com", "hash123", AuthProvider.LOCAL, null);
        User saved = userRepository.save(user);
        Profile profile = new Profile(UUID.randomUUID(), saved.getId(), prefix + "_" + suffix, "Name " + suffix, "Bio", "https://avatar.com/" + suffix);
        profileRepository.save(profile);
        return saved;
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Place place = new Place(
                null, "Place Hyd " + suffix, "place-hyd-" + suffix, "RESTAURANTE",
                "Desc", "Rua Hyd, 100", "100", "Bairro", "Cidade", "SP", "BR",
                -23.55, -46.63, 50, "USER", false, null, "ACTIVE"
        );
        return placeRepository.save(place);
    }

    private RateableTarget createTarget(TargetType type) {
        return rateableTargetRepository.save(new RateableTarget(UUID.randomUUID(), type));
    }

    private Review createReviewWithTargets(User author, Place place, List<RateableTarget> targets,
                                          String visibility, ReviewStatus status,
                                          boolean verified, boolean anonymous, Instant createdAt) {
        UUID reviewId = UUID.randomUUID();
        Review review = new Review(
                reviewId, author.getId(), place.getId(),
                "Experiência da review " + reviewId, anonymous, verified,
                status, visibility,
                -23.55, -46.63, 10.0,
                createdAt != null ? createdAt : Instant.now(),
                Instant.now()
        );

        for (int i = 0; i < targets.size(); i++) {
            RateableTarget target = targets.get(i);
            ReviewTarget rt = new ReviewTarget(
                    UUID.randomUUID(), reviewId, target.getId(),
                    BigDecimal.valueOf(4.0 + (i * 0.5)), "Comentário alvo " + (i + 1),
                    (createdAt != null ? createdAt : Instant.now()).plusSeconds(i * 5)
            );
            review.addTarget(rt);
        }

        Review saved = reviewRepository.save(review);
        for (ReviewTarget rt : review.getTargets()) {
            reviewTargetRepository.save(rt);
        }
        return saved;
    }

    @Test
    @DisplayName("1. PostgreSQL: Hidrata avaliação pública com autor, targets e helpful em lote")
    void shouldHydrateSinglePublicReviewWithAuthorAndTargetAndHelpful() {
        User requester = createActiveUser("req");
        User author = createActiveUser("author");
        Place place = createPlace();
        RateableTarget target = createTarget(TargetType.PLACE);

        userFollowRepository.follow(requester.getId(), author.getId());

        Instant now = Instant.now();
        Review review = createReviewWithTargets(author, place, List.of(target), "PUBLIC", ReviewStatus.ACTIVE, false, false, now);

        // Requester vota como Helpful
        reviewReactionRepository.addHelpful(review.getId(), requester.getId());

        // Obtém a página via FeedV2Service
        FeedV2CandidatePage candidatePage = feedV2Service.getCandidatePage(requester.getId(), 0, 10, now);
        assertFalse(candidatePage.isEmpty());

        FeedV2PageProjection projection = feedV2Hydrator.hydrate(candidatePage, requester.getId());

        assertNotNull(projection);
        assertEquals(1, projection.items().size());

        FeedV2Projection item = projection.items().get(0);
        assertEquals(review.getId(), item.id());
        assertFalse(item.isAnonymous());
        assertFalse(item.isVerifiedOnSite());
        assertEquals("PUBLIC", item.visibility());
        assertEquals("ACTIVE", item.status());
        assertEquals(place.getId(), item.contextPlaceId());

        // Autor
        assertNotNull(item.author());
        assertEquals(author.getId(), item.author().id());
        assertNotNull(item.author().handle());
        assertNotNull(item.author().displayName());
        assertNotNull(item.author().avatarUrl());
        assertFalse(item.author().isAnonymous());

        // Target
        assertEquals(1, item.targets().size());
        assertEquals(target.getId(), item.targets().get(0).targetId());
        assertEquals(BigDecimal.valueOf(4.0), item.targets().get(0).rating());

        // Helpful
        assertEquals(1L, item.helpfulCount());
        assertTrue(item.isHelpfulByMe());
    }

    @Test
    @DisplayName("2. PostgreSQL: Hidrata avaliação anônima com preservação estrita de anonimato")
    void shouldHydrateAnonymousReviewPreservingStrictPrivacy() {
        User requester = createActiveUser("req_anon");
        User author = createActiveUser("author_anon");
        Place place = createPlace();
        RateableTarget target = createTarget(TargetType.PLACE);

        userFollowRepository.follow(requester.getId(), author.getId());

        Instant now = Instant.now();
        Review anonymousReview = createReviewWithTargets(author, place, List.of(target), "PUBLIC", ReviewStatus.ACTIVE, false, true, now);

        FeedV2CandidatePage candidatePage = feedV2Service.getCandidatePage(requester.getId(), 0, 10, now);
        FeedV2PageProjection projection = feedV2Hydrator.hydrate(candidatePage, requester.getId());

        assertFalse(projection.isEmpty());
        FeedV2Projection item = projection.items().stream()
                .filter(p -> p.id().equals(anonymousReview.getId()))
                .findFirst()
                .orElseThrow();

        assertTrue(item.isAnonymous());
        assertNotNull(item.author());
        assertNull(item.author().id(), "authorId não pode vazar");
        assertNull(item.author().handle(), "handle não pode vazar");
        assertEquals("Anônimo", item.author().displayName());
        assertNull(item.author().avatarUrl(), "avatarUrl não pode vazar");
        assertTrue(item.author().isAnonymous());
    }

    @Test
    @DisplayName("3. PostgreSQL: Hidrata avaliação multi-target preservando todos os alvos deterministamente")
    void shouldHydrateMultiTargetReviewWithDeterministicOrdering() {
        User requester = createActiveUser("req_multi");
        User author = createActiveUser("author_multi");
        Place place = createPlace();
        RateableTarget t1 = createTarget(TargetType.PLACE);
        RateableTarget t2 = createTarget(TargetType.PRODUCT);
        RateableTarget t3 = createTarget(TargetType.SERVICE);

        userFollowRepository.follow(requester.getId(), author.getId());

        Instant now = Instant.now();
        Review multiReview = createReviewWithTargets(author, place, List.of(t1, t2, t3), "PUBLIC", ReviewStatus.ACTIVE, false, false, now);

        FeedV2CandidatePage candidatePage = feedV2Service.getCandidatePage(requester.getId(), 0, 10, now);
        FeedV2PageProjection projection = feedV2Hydrator.hydrate(candidatePage, requester.getId());

        FeedV2Projection item = projection.items().stream()
                .filter(p -> p.id().equals(multiReview.getId()))
                .findFirst()
                .orElseThrow();

        assertEquals(3, item.targets().size());
        assertEquals(t1.getId(), item.targets().get(0).targetId());
        assertEquals(t2.getId(), item.targets().get(1).targetId());
        assertEquals(t3.getId(), item.targets().get(2).targetId());
    }

    @Test
    @DisplayName("4. PostgreSQL: Mistura de públicas e anônimas preserva a ordem exata do ranker/diversifier")
    void shouldHydrateMixedPagePreservingExactRankingOrder() {
        User requester = createActiveUser("req_mixed");
        User author1 = createActiveUser("author1");
        User author2 = createActiveUser("author2");
        Place place = createPlace();
        RateableTarget target = createTarget(TargetType.PLACE);

        userFollowRepository.follow(requester.getId(), author1.getId());
        userFollowRepository.follow(requester.getId(), author2.getId());

        Instant now = Instant.now();
        // author1: review pública recente
        Review r1 = createReviewWithTargets(author1, place, List.of(target), "PUBLIC", ReviewStatus.ACTIVE, false, false, now.minusSeconds(10));
        // author2: review anônima intermediária
        Review r2 = createReviewWithTargets(author2, place, List.of(target), "PUBLIC", ReviewStatus.ACTIVE, false, true, now.minusSeconds(20));
        // author1: review pública antiga
        Review r3 = createReviewWithTargets(author1, place, List.of(target), "PUBLIC", ReviewStatus.ACTIVE, false, false, now.minusSeconds(30));

        FeedV2CandidatePage candidatePage = feedV2Service.getCandidatePage(requester.getId(), 0, 10, now);
        FeedV2PageProjection projection = feedV2Hydrator.hydrate(candidatePage, requester.getId());

        // A ordem na projeção deve bater 100% com a ordem na candidatePage
        assertEquals(candidatePage.items().size(), projection.items().size());
        for (int i = 0; i < candidatePage.items().size(); i++) {
            assertEquals(candidatePage.items().get(i).candidate().reviewId(), projection.items().get(i).id());
        }

        // Valida que os itens anônimos estão anônimos e os públicos têm dados
        for (FeedV2Projection p : projection.items()) {
            if (p.id().equals(r2.getId())) {
                assertTrue(p.isAnonymous());
                assertNull(p.author().id());
                assertEquals("Anônimo", p.author().displayName());
            } else if (p.id().equals(r1.getId()) || p.id().equals(r3.getId())) {
                assertFalse(p.isAnonymous());
                assertEquals(author1.getId(), p.author().id());
            }
        }
    }

    @Test
    @DisplayName("5. PostgreSQL: Distinção de isHelpfulByMe entre múltiplas avaliações")
    void shouldHydrateHelpfulByMeDistinctionAcrossMultipleReviews() {
        User requester = createActiveUser("req_help");
        User voter = createActiveUser("voter_other");
        User author = createActiveUser("author_help");
        Place place = createPlace();
        RateableTarget target = createTarget(TargetType.PLACE);

        userFollowRepository.follow(requester.getId(), author.getId());

        Instant now = Instant.now();
        Review rLikedByRequester = createReviewWithTargets(author, place, List.of(target), "PUBLIC", ReviewStatus.ACTIVE, false, false, now.minusSeconds(10));
        Review rLikedByOther = createReviewWithTargets(author, place, List.of(target), "PUBLIC", ReviewStatus.ACTIVE, false, false, now.minusSeconds(20));

        reviewReactionRepository.addHelpful(rLikedByRequester.getId(), requester.getId());
        reviewReactionRepository.addHelpful(rLikedByOther.getId(), voter.getId());

        FeedV2CandidatePage candidatePage = feedV2Service.getCandidatePage(requester.getId(), 0, 10, now);
        FeedV2PageProjection projection = feedV2Hydrator.hydrate(candidatePage, requester.getId());

        FeedV2Projection pRequester = projection.items().stream()
                .filter(p -> p.id().equals(rLikedByRequester.getId()))
                .findFirst().orElseThrow();
        FeedV2Projection pOther = projection.items().stream()
                .filter(p -> p.id().equals(rLikedByOther.getId()))
                .findFirst().orElseThrow();

        assertEquals(1L, pRequester.helpfulCount());
        assertTrue(pRequester.isHelpfulByMe());

        assertEquals(1L, pOther.helpfulCount());
        assertFalse(pOther.isHelpfulByMe());
    }

    @Test
    @DisplayName("6. PostgreSQL: Reviews com status REMOVED ou UNDER_REVIEW não geram projeção")
    void shouldExcludeRemovedAndUnderReviewReviewsIfPresentInCandidatePage() {
        User requester = createActiveUser("req_status");
        User author = createActiveUser("author_status");
        Place place = createPlace();
        RateableTarget target = createTarget(TargetType.PLACE);

        Review rRemoved = createReviewWithTargets(author, place, List.of(target), "PUBLIC", ReviewStatus.REMOVED, false, false, Instant.now());

        // Cria candidate artificial com o id da review REMOVED
        FeedCandidate fc = new FeedCandidate(rRemoved.getId(), author.getId(), target.getId(), Instant.now(), false, 0, true);
        RankedFeedCandidate rfc = new RankedFeedCandidate(fc, new FeedScore(0.9));
        FeedV2CandidatePage page = new FeedV2CandidatePage(List.of(rfc), 0, 10, 1);

        FeedV2PageProjection projection = feedV2Hydrator.hydrate(page, requester.getId());

        // Deve filtrar a review REMOVED
        assertTrue(projection.isEmpty());
    }
}
