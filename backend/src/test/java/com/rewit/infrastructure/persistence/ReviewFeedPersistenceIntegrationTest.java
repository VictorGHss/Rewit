package com.rewit.infrastructure.persistence;

import com.rewit.application.dto.ReviewDto.ReviewPublicView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.*;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.TargetType;
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

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração de Persistência no PostgreSQL Real - Feed Social V1 (Step 17.0)")
class ReviewFeedPersistenceIntegrationTest {

    @Autowired
    private com.rewit.application.service.ReviewService reviewService;

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
                "Restaurante Feed " + suffix,
                "restaurante-feed-" + suffix,
                "RESTAURANTE",
                "Descrição",
                "Rua Feed, 100",
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

    private RateableTarget createTarget() {
        RateableTarget target = new RateableTarget(UUID.randomUUID(), TargetType.PLACE);
        return rateableTargetRepository.save(target);
    }

    private Review createReview(User author, Place place, RateableTarget target, String visibility, ReviewStatus status, boolean isAnonymous, Instant createdAt) {
        UUID reviewId = UUID.randomUUID();
        Review review = new Review(
                reviewId,
                author.getId(),
                place.getId(),
                "Texto de avaliação do feed",
                isAnonymous,
                false,
                status,
                visibility,
                -23.5505,
                -46.6333,
                10.0,
                createdAt != null ? createdAt : Instant.now(),
                Instant.now()
        );
        ReviewTarget reviewTarget = new ReviewTarget(UUID.randomUUID(), reviewId, target.getId(), BigDecimal.valueOf(4.5), "Nota do prato");
        review.addTarget(reviewTarget);
        Review saved = reviewRepository.save(review);
        reviewTargetRepository.save(reviewTarget);
        return saved;
    }

    @Test
    @DisplayName("1. Cenário social obrigatório: apenas Reviews PUBLIC e FOLLOWERS de usuários seguidos entram no Feed")
    void shouldReturnOnlyPublicAndFollowersReviewsFromFollowedUsers() {
        User requester = createActiveUser();
        User followedUserB = createActiveUser();
        User nonFollowedUserC = createActiveUser();

        Place place = createPlace();
        RateableTarget target = createTarget();

        // Requester segue B
        userFollowRepository.follow(requester.getId(), followedUserB.getId());

        Instant now = Instant.now();
        // B cria reviews com status e visibilidades variadas
        Review revPublicB = createReview(followedUserB, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(10));
        Review revFollowersB = createReview(followedUserB, place, target, "FOLLOWERS", ReviewStatus.ACTIVE, false, now.minusSeconds(20));
        Review revPrivateB = createReview(followedUserB, place, target, "PRIVATE", ReviewStatus.ACTIVE, false, now.minusSeconds(30));
        Review revRemovedB = createReview(followedUserB, place, target, "PUBLIC", ReviewStatus.REMOVED, false, now.minusSeconds(40));
        Review revUnderReviewB = createReview(followedUserB, place, target, "PUBLIC", ReviewStatus.UNDER_REVIEW, false, now.minusSeconds(50));

        // C (não seguido) cria review PUBLIC
        Review revPublicC = createReview(nonFollowedUserC, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(5));

        // Requester cria review para si mesmo
        Review revOwnRequester = createReview(requester, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(2));

        PageResult<ReviewPublicView> feed = reviewService.findFeed(requester.getId(), 0, 10, "newest");

        assertNotNull(feed);
        List<UUID> returnedReviewIds = feed.content().stream().map(r -> r.id()).toList();

        // Deve conter exatamente revPublicB e revFollowersB
        assertTrue(returnedReviewIds.contains(revPublicB.getId()), "Deve conter a Review PUBLIC do usuário seguido");
        assertTrue(returnedReviewIds.contains(revFollowersB.getId()), "Deve conter a Review FOLLOWERS do usuário seguido");

        // NÃO deve conter revPrivateB, revRemovedB, revUnderReviewB, revPublicC nem revOwnRequester
        assertFalse(returnedReviewIds.contains(revPrivateB.getId()), "NÃO deve conter a Review PRIVATE");
        assertFalse(returnedReviewIds.contains(revRemovedB.getId()), "NÃO deve conter a Review REMOVED");
        assertFalse(returnedReviewIds.contains(revUnderReviewB.getId()), "NÃO deve conter a Review UNDER_REVIEW");
        assertFalse(returnedReviewIds.contains(revPublicC.getId()), "NÃO deve conter Review de usuário não seguido");
        assertFalse(returnedReviewIds.contains(revOwnRequester.getId()), "NÃO deve conter Review criada pelo próprio requester");
    }

    @Test
    @DisplayName("2. Paginação determinística e ordenação cronológica decrescente com desempate por id")
    void shouldPaginateAndOrderChronologically() {
        User requester = createActiveUser();
        User author = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();

        userFollowRepository.follow(requester.getId(), author.getId());

        // Criar 12 reviews com timestamps ordenados
        Instant baseTime = Instant.now().minusSeconds(1000);
        for (int i = 0; i < 12; i++) {
            createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, baseTime.plusSeconds(i * 10));
        }

        // Página 0 com size 5
        PageResult<ReviewPublicView> p0 = reviewService.findFeed(requester.getId(), 0, 5, "newest");
        assertEquals(5, p0.content().size());
        assertEquals(0, p0.pageNumber());
        assertEquals(12, p0.totalElements());
        assertEquals(3, p0.totalPages());
        assertFalse(p0.isLast());

        // Página 1 com size 5
        PageResult<ReviewPublicView> p1 = reviewService.findFeed(requester.getId(), 1, 5, "newest");
        assertEquals(5, p1.content().size());
        assertEquals(1, p1.pageNumber());
        assertFalse(p1.isLast());

        // Página 2 com size 5 (restante 2)
        PageResult<ReviewPublicView> p2 = reviewService.findFeed(requester.getId(), 2, 5, "newest");
        assertEquals(2, p2.content().size());
        assertEquals(2, p2.pageNumber());
        assertTrue(p2.isLast());

        // Nenhum elemento de p0 pode estar em p1 ou p2
        List<UUID> idsP0 = p0.content().stream().map(r -> r.id()).toList();
        List<UUID> idsP1 = p1.content().stream().map(r -> r.id()).toList();
        List<UUID> idsP2 = p2.content().stream().map(r -> r.id()).toList();

        for (UUID id : idsP0) {
            assertFalse(idsP1.contains(id), "Página 1 não deve duplicar elementos da Página 0");
            assertFalse(idsP2.contains(id), "Página 2 não deve duplicar elementos da Página 0");
        }

        // Ordenação cronológica estrita mais recente primeiro
        for (int i = 0; i < p0.content().size() - 1; i++) {
            Instant tCurrent = p0.content().get(i).createdAt();
            Instant tNext = p0.content().get(i + 1).createdAt();
            assertTrue(!tCurrent.isBefore(tNext), "Ordenação deve ser decrescente por createdAt");
        }
    }

    @Test
    @DisplayName("3. Multi-target: preserva múltiplos alvos avaliados intactos no feed")
    void shouldPreserveMultipleTargetsInFeed() {
        User requester = createActiveUser();
        User author = createActiveUser();
        Place place = createPlace();
        RateableTarget t1 = createTarget();
        RateableTarget t2 = createTarget();

        userFollowRepository.follow(requester.getId(), author.getId());

        UUID revId = UUID.randomUUID();
        Review review = new Review(
                revId,
                author.getId(),
                place.getId(),
                "Experiência com dois alvos",
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
        ReviewTarget rt1 = new ReviewTarget(UUID.randomUUID(), revId, t1.getId(), BigDecimal.valueOf(4.0), "Local agradável");
        ReviewTarget rt2 = new ReviewTarget(UUID.randomUUID(), revId, t2.getId(), BigDecimal.valueOf(5.0), "Excelente prato");
        review.addTarget(rt1);
        review.addTarget(rt2);

        reviewRepository.save(review);
        reviewTargetRepository.save(rt1);
        reviewTargetRepository.save(rt2);

        PageResult<ReviewPublicView> feed = reviewService.findFeed(requester.getId(), 0, 10, "newest");
        ReviewPublicView view = feed.content().stream()
                .filter(r -> r.id().equals(revId))
                .findFirst()
                .orElseThrow();

        assertEquals(2, view.targets().size());
        assertTrue(view.targets().stream().anyMatch(t -> t.targetId().equals(t1.getId()) && t.rating().compareTo(BigDecimal.valueOf(4.0)) == 0));
        assertTrue(view.targets().stream().anyMatch(t -> t.targetId().equals(t2.getId()) && t.rating().compareTo(BigDecimal.valueOf(5.0)) == 0));
    }

    @Test
    @DisplayName("4. Helpful no Feed: retorna helpfulCount e isHelpfulByMe corretos (A=3/true, B=7/false, C=0/false) sem quebrar ordem cronológica")
    void shouldReturnHelpfulMetadataInFeed() {
        User requester = createActiveUser();
        User author = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();

        userFollowRepository.follow(requester.getId(), author.getId());

        Instant now = Instant.now();
        // A é mais recente que B, e B é mais recente que C
        Review rA = createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(10));
        Review rB = createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(20));
        Review rC = createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(30));

        // R_A recebe 3 Helpful (requester + 2 outros) -> requester votou
        User voterA1 = createActiveUser();
        User voterA2 = createActiveUser();
        reviewReactionRepository.addHelpful(rA.getId(), requester.getId());
        reviewReactionRepository.addHelpful(rA.getId(), voterA1.getId());
        reviewReactionRepository.addHelpful(rA.getId(), voterA2.getId());

        // R_B recebe 7 Helpful (7 outros usuários) -> requester NÃO votou
        for (int i = 0; i < 7; i++) {
            User voterB = createActiveUser();
            reviewReactionRepository.addHelpful(rB.getId(), voterB.getId());
        }

        // R_C recebe 0 Helpful

        PageResult<ReviewPublicView> feed = reviewService.findFeed(requester.getId(), 0, 10, "newest");
        assertEquals(3, feed.content().size());

        // A ordenação deve ser estritamente cronológica decrescente: rA (10s atrás), rB (20s atrás), rC (30s atrás)
        // Mesmo com 7 votos de helpful, rB não ultrapassa rA
        assertEquals(rA.getId(), feed.content().get(0).id(), "rA deve ser a primeira por ser a mais recente");
        assertEquals(rB.getId(), feed.content().get(1).id(), "rB deve ser a segunda");
        assertEquals(rC.getId(), feed.content().get(2).id(), "rC deve ser a terceira");

        ReviewPublicView viewRA = feed.content().get(0);
        ReviewPublicView viewRB = feed.content().get(1);
        ReviewPublicView viewRC = feed.content().get(2);

        assertEquals(3L, viewRA.helpfulCount());
        assertTrue(viewRA.isHelpfulByMe());

        assertEquals(7L, viewRB.helpfulCount());
        assertFalse(viewRB.isHelpfulByMe());

        assertEquals(0L, viewRC.helpfulCount());
        assertFalse(viewRC.isHelpfulByMe());
    }

    @Test
    @DisplayName("5. Anonimização no Feed: mascara autor anônimo de usuário seguido")
    void shouldPreserveAnonymizationInFeed() {
        User requester = createActiveUser();
        User author = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();

        userFollowRepository.follow(requester.getId(), author.getId());

        Review review = createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, true, Instant.now());

        PageResult<ReviewPublicView> feed = reviewService.findFeed(requester.getId(), 0, 10, "newest");
        ReviewPublicView view = feed.content().stream().filter(r -> r.id().equals(review.getId())).findFirst().orElseThrow();

        assertTrue(view.isAnonymous());
        assertTrue(view.author().isAnonymous());
        assertEquals("Anônimo", view.author().displayName());
        assertNull(view.author().handle());
        assertNull(view.author().id());
    }

    @Test
    @DisplayName("6. Empate determinístico: reviews com mesmo createdAt desempatam por id ASC no PostgreSQL")
    void shouldPreserveDeterministicTieBreakByIdAscWhenCreatedAtIsIdentical() {
        User requester = createActiveUser();
        User author = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();

        userFollowRepository.follow(requester.getId(), author.getId());

        Instant sameTime = Instant.now().minusSeconds(50);
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        UUID idLow = (u1.toString().compareTo(u2.toString()) < 0) ? u1 : u2;
        UUID idHigh = (u1.toString().compareTo(u2.toString()) < 0) ? u2 : u1;

        // Cria reviews com idLow e idHigh, salvando primeiro idHigh
        Review rHigh = new Review(idHigh, author.getId(), place.getId(), "High", false, false, ReviewStatus.ACTIVE, "PUBLIC", -23.5505, -46.6333, 10.0, sameTime, sameTime);
        ReviewTarget rtHigh = new ReviewTarget(null, idHigh, target.getId(), BigDecimal.valueOf(4.0), "High");
        rHigh.addTarget(rtHigh);
        reviewRepository.save(rHigh);
        reviewTargetRepository.save(rtHigh);

        Review rLow = new Review(idLow, author.getId(), place.getId(), "Low", false, false, ReviewStatus.ACTIVE, "PUBLIC", -23.5505, -46.6333, 10.0, sameTime, sameTime);
        ReviewTarget rtLow = new ReviewTarget(null, idLow, target.getId(), BigDecimal.valueOf(5.0), "Low");
        rLow.addTarget(rtLow);
        reviewRepository.save(rLow);
        reviewTargetRepository.save(rtLow);

        PageResult<ReviewPublicView> feed = reviewService.findFeed(requester.getId(), 0, 10, "newest");
        List<UUID> returnedIds = feed.content().stream().map(r -> r.id()).toList();

        int idxLow = returnedIds.indexOf(idLow);
        int idxHigh = returnedIds.indexOf(idHigh);

        assertTrue(idxLow >= 0 && idxHigh >= 0, "Ambas as reviews empatadas devem estar no feed");
        assertTrue(idxLow < idxHigh, "idLow deve preceder idHigh no desempate determinístico (id ASC)");
    }
}
