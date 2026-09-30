package com.rewit.infrastructure.persistence;

import com.rewit.application.port.FeedCandidateRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.ReviewReactionRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.ReviewTargetRepository;
import com.rewit.application.port.UserFollowRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.feed.FeedCandidate;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewTarget;
import com.rewit.domain.model.User;
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
 * Testes de integração de persistência para o Candidate Retrieval do Feed V2 (Step 24.3.2).
 *
 * <p>Todos os cenários usam PostgreSQL real via perfil "local".
 * Cada teste cria seu próprio conjunto de dados isolado para evitar interferência entre execuções.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Persistência: Feed V2 Candidate Retrieval (Step 24.3.2)")
class FeedCandidateRetrievalPersistenceIntegrationTest {

    @Autowired
    private FeedCandidateRepository feedCandidateRepository;

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

    // ------------------------------------------------------------------
    // Helpers de criação de dados
    // ------------------------------------------------------------------

    private User createActiveUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User user = new User(null, "feed-v2-" + suffix + "@rewit.com", "hash123", AuthProvider.LOCAL, null);
        User saved = userRepository.save(user);
        Profile profile = new Profile(UUID.randomUUID(), saved.getId(), "fv2_" + suffix, "Feed V2 " + suffix, "Bio", null);
        profileRepository.save(profile);
        return saved;
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Place place = new Place(
                null, "Place V2 " + suffix, "place-v2-" + suffix, "RESTAURANTE",
                "Desc", "Rua V2, 1", "1", "Bairro", "Cidade", "SP", "BR",
                -23.55, -46.63, 50, "USER", false, null, "ACTIVE"
        );
        return placeRepository.save(place);
    }

    private RateableTarget createTarget() {
        return rateableTargetRepository.save(new RateableTarget(UUID.randomUUID(), TargetType.PLACE));
    }

    private Review createReview(User author, Place place, RateableTarget target,
                                 String visibility, ReviewStatus status,
                                 boolean anonymous, Instant createdAt) {
        UUID reviewId = UUID.randomUUID();
        Review review = new Review(
                reviewId, author.getId(), place.getId(),
                "Texto feed v2 " + reviewId, anonymous, false,
                status, visibility,
                -23.55, -46.63, 10.0,
                createdAt != null ? createdAt : Instant.now(),
                Instant.now()
        );
        ReviewTarget rt = new ReviewTarget(UUID.randomUUID(), reviewId, target.getId(),
                BigDecimal.valueOf(4.0), "ok");
        review.addTarget(rt);
        Review saved = reviewRepository.save(review);
        reviewTargetRepository.save(rt);
        return saved;
    }

    // ------------------------------------------------------------------
    // 1. Visibilidade: PUBLIC aparece no feed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("1.1. Review PUBLIC de autor seguido aparece nos candidatos")
    void publicReviewFromFollowedAuthorIsReturned() {
        User requester = createActiveUser();
        User author = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();
        userFollowRepository.follow(requester.getId(), author.getId());

        Review review = createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now());

        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        List<UUID> ids = candidates.stream().map(c -> c.reviewId()).toList();
        assertTrue(ids.contains(review.getId()), "Review PUBLIC de autor seguido deve aparecer");
    }

    // ------------------------------------------------------------------
    // 2. Visibilidade: FOLLOWERS aparece para quem segue
    // ------------------------------------------------------------------

    @Test
    @DisplayName("1.2. Review FOLLOWERS de autor seguido aparece nos candidatos")
    void followersReviewFromFollowedAuthorIsReturned() {
        User requester = createActiveUser();
        User author = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();
        userFollowRepository.follow(requester.getId(), author.getId());

        Review review = createReview(author, place, target, "FOLLOWERS", ReviewStatus.ACTIVE, false, Instant.now());

        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        List<UUID> ids = candidates.stream().map(c -> c.reviewId()).toList();
        assertTrue(ids.contains(review.getId()), "Review FOLLOWERS de autor seguido deve aparecer");
    }

    // ------------------------------------------------------------------
    // 3. Visibilidade: PRIVATE nunca aparece no feed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("1.3. Review PRIVATE de autor seguido NÃO aparece nos candidatos")
    void privateReviewIsNeverReturned() {
        User requester = createActiveUser();
        User author = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();
        userFollowRepository.follow(requester.getId(), author.getId());

        Review review = createReview(author, place, target, "PRIVATE", ReviewStatus.ACTIVE, false, Instant.now());

        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        List<UUID> ids = candidates.stream().map(c -> c.reviewId()).toList();
        assertFalse(ids.contains(review.getId()), "Review PRIVATE nunca deve aparecer no feed");
    }

    // ------------------------------------------------------------------
    // 4. Status: ACTIVE aparece; UNDER_REVIEW e REMOVED não aparecem
    // ------------------------------------------------------------------

    @Test
    @DisplayName("2.1. Apenas reviews com status ACTIVE entram nos candidatos")
    void onlyActiveReviewsAreReturned() {
        User requester = createActiveUser();
        User author = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();
        userFollowRepository.follow(requester.getId(), author.getId());

        Instant now = Instant.now();
        Review active      = createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(30));
        Review underReview = createReview(author, place, target, "PUBLIC", ReviewStatus.UNDER_REVIEW, false, now.minusSeconds(20));
        Review removed     = createReview(author, place, target, "PUBLIC", ReviewStatus.REMOVED, false, now.minusSeconds(10));

        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        List<UUID> ids = candidates.stream().map(c -> c.reviewId()).toList();
        assertTrue(ids.contains(active.getId()),       "Review ACTIVE deve aparecer");
        assertFalse(ids.contains(underReview.getId()), "Review UNDER_REVIEW não deve aparecer");
        assertFalse(ids.contains(removed.getId()),     "Review REMOVED não deve aparecer");
    }

    // ------------------------------------------------------------------
    // 5. Rede social: review de seguido aparece; de não-seguido não aparece
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3.1. Review de autor não seguido não aparece, mesmo sendo PUBLIC e ACTIVE")
    void reviewFromNonFollowedAuthorIsNotReturned() {
        User requester    = createActiveUser();
        User followed     = createActiveUser();
        User notFollowed  = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();

        // Requester segue apenas "followed"
        userFollowRepository.follow(requester.getId(), followed.getId());

        Review revFollowed    = createReview(followed, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now().minusSeconds(10));
        Review revNotFollowed = createReview(notFollowed, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now());

        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        List<UUID> ids = candidates.stream().map(c -> c.reviewId()).toList();
        assertTrue(ids.contains(revFollowed.getId()),     "Review de autor seguido deve aparecer");
        assertFalse(ids.contains(revNotFollowed.getId()), "Review de autor não seguido não deve aparecer");
    }

    // ------------------------------------------------------------------
    // 6. Rede social: sem follows → janela vazia
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3.2. Sem follows o retrieval retorna lista vazia")
    void noFollowsReturnsEmptyWindow() {
        User requester = createActiveUser();

        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        assertTrue(candidates.isEmpty(), "Sem follows a janela de candidatos deve ser vazia");
    }

    // ------------------------------------------------------------------
    // 7. Segurança: requester não recebe próprias reviews
    // ------------------------------------------------------------------

    @Test
    @DisplayName("4.1. Reviews do próprio requester não aparecem nos candidatos")
    void requesterOwnReviewsAreNeverReturned() {
        User requester = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();

        // Requester cria review para si mesmo (self-follow não existe, mas a review existe)
        Review own = createReview(requester, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now());

        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        List<UUID> ids = candidates.stream().map(c -> c.reviewId()).toList();
        assertFalse(ids.contains(own.getId()),
                "Reviews do próprio requester não devem aparecer nos candidatos");
    }

    // ------------------------------------------------------------------
    // 8. Segurança: PUBLIC de não-seguido não entra por ser PUBLIC nesta etapa
    // ------------------------------------------------------------------

    @Test
    @DisplayName("4.2. Review PUBLIC de autor não seguido não entra nos candidatos V2")
    void publicReviewFromNonFollowedAuthorIsExcludedInV2() {
        User requester   = createActiveUser();
        User notFollowed = createActiveUser();
        User followed    = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();

        userFollowRepository.follow(requester.getId(), followed.getId());

        Review publicFromNonFollowed = createReview(notFollowed, place, target,
                "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now());

        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        List<UUID> ids = candidates.stream().map(c -> c.reviewId()).toList();
        assertFalse(ids.contains(publicFromNonFollowed.getId()),
                "Review PUBLIC de não-seguido não deve entrar no feed V2 (somente grafo social direto)");
    }

    // ------------------------------------------------------------------
    // 9. Determinismo: mesmo dataset → mesma ordem
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5.1. Mesmo dataset produz a mesma ordem em duas chamadas consecutivas")
    void sameDatasetProducesDeterministicOrder() {
        User requester = createActiveUser();
        User author    = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();
        userFollowRepository.follow(requester.getId(), author.getId());

        Instant base = Instant.now().minusSeconds(500);
        for (int i = 0; i < 5; i++) {
            createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false,
                    base.plusSeconds(i * 60L));
        }

        List<UUID> first  = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW)
                .stream().map(c -> c.reviewId()).toList();
        List<UUID> second = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW)
                .stream().map(c -> c.reviewId()).toList();

        assertEquals(first, second, "Duas chamadas consecutivas devem produzir a mesma ordem");
    }

    // ------------------------------------------------------------------
    // 10. Ordenação: createdAt DESC, id ASC
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5.2. Candidatos retornados em ordem createdAt DESC com desempate id ASC")
    void candidatesAreOrderedByCreatedAtDescThenIdAsc() {
        User requester = createActiveUser();
        User author    = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();
        userFollowRepository.follow(requester.getId(), author.getId());

        Instant t1 = Instant.now().minusSeconds(300);
        Instant t2 = Instant.now().minusSeconds(200);
        Instant t3 = Instant.now().minusSeconds(100);

        Review older  = createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, t1);
        Review middle = createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, t2);
        Review newest = createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, t3);

        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        // Filtrar só as reviews deste teste
        List<UUID> filtered = candidates.stream()
                .map(c -> c.reviewId())
                .filter(id -> id.equals(older.getId()) || id.equals(middle.getId()) || id.equals(newest.getId()))
                .toList();

        assertEquals(3, filtered.size());
        assertEquals(newest.getId(), filtered.get(0), "A mais recente deve ser a primeira");
        assertEquals(middle.getId(), filtered.get(1), "A do meio deve ser a segunda");
        assertEquals(older.getId(),  filtered.get(2), "A mais antiga deve ser a última");
    }

    // ------------------------------------------------------------------
    // 11. Limite: mais de 100 candidatos → retorna somente a janela configurada
    // ------------------------------------------------------------------

    @Test
    @DisplayName("6.1. Com mais de CANDIDATE_WINDOW reviews elegíveis, retorna no máximo CANDIDATE_WINDOW")
    void moreEligibleThanWindowReturnsOnlyWindow() {
        User requester = createActiveUser();
        User author    = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();
        userFollowRepository.follow(requester.getId(), author.getId());

        // Criar CANDIDATE_WINDOW + 10 reviews
        Instant base = Instant.now().minusSeconds(20000);
        for (int i = 0; i < FeedCandidateRepository.CANDIDATE_WINDOW + 10; i++) {
            createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false,
                    base.plusSeconds(i * 10L));
        }

        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        assertTrue(candidates.size() <= FeedCandidateRepository.CANDIDATE_WINDOW,
                "O retrieval deve respeitar o limite de CANDIDATE_WINDOW candidatos");
        assertEquals(FeedCandidateRepository.CANDIDATE_WINDOW, candidates.size(),
                "Com mais de CANDIDATE_WINDOW reviews elegíveis, deve retornar exatamente CANDIDATE_WINDOW");
    }

    // ------------------------------------------------------------------
    // 12. Anonimato: review anônima não gera profile lookup nem expõe identidade
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7.1. Review anônima é retornada nos candidatos com authorId interno preservado")
    void anonymousReviewReturnsCandidateWithInternalAuthorId() {
        User requester = createActiveUser();
        User author    = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();
        userFollowRepository.follow(requester.getId(), author.getId());

        Review anonymousReview = createReview(author, place, target,
                "PUBLIC", ReviewStatus.ACTIVE, true /* anonymous */, Instant.now());

        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        FeedCandidate candidate = candidates.stream()
                .filter(c -> c.reviewId().equals(anonymousReview.getId()))
                .findFirst()
                .orElse(null);

        assertNotNull(candidate, "Review anônima deve aparecer nos candidatos");
        // O authorId interno é preservado para diversidade — nunca é nulo
        assertNotNull(candidate.authorId(), "authorId interno deve estar presente mesmo em review anônima");
        // O authorId correto é o do autor real (necessário para diversidade)
        assertEquals(author.getId(), candidate.authorId(),
                "O authorId interno deve ser o do autor real mesmo em review anônima");
    }

    // ------------------------------------------------------------------
    // 13. Helpful count via lote
    // ------------------------------------------------------------------

    @Test
    @DisplayName("8.1. helpfulCount reflete o número de votos Helpful da review")
    void helpfulCountIsCorrectlyLoaded() {
        User requester = createActiveUser();
        User author    = createActiveUser();
        User voter1    = createActiveUser();
        User voter2    = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();
        userFollowRepository.follow(requester.getId(), author.getId());

        Review review = createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now());

        // 2 votos helpful
        reviewReactionRepository.addHelpful(review.getId(), voter1.getId());
        reviewReactionRepository.addHelpful(review.getId(), voter2.getId());

        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        FeedCandidate candidate = candidates.stream()
                .filter(c -> c.reviewId().equals(review.getId()))
                .findFirst()
                .orElse(null);

        assertNotNull(candidate, "Review deve aparecer nos candidatos");
        assertEquals(2, candidate.helpfulCount(), "helpfulCount deve refletir os 2 votos registrados");
    }

    // ------------------------------------------------------------------
    // 14. isDirectFollow sempre true nesta etapa
    // ------------------------------------------------------------------

    @Test
    @DisplayName("9.1. isDirectFollow é true para todos os candidatos do grafo social direto")
    void isDirectFollowIsTrueForAllCandidates() {
        User requester = createActiveUser();
        User author    = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();
        userFollowRepository.follow(requester.getId(), author.getId());

        createReview(author, place, target, "PUBLIC",    ReviewStatus.ACTIVE, false, Instant.now().minusSeconds(20));
        createReview(author, place, target, "FOLLOWERS", ReviewStatus.ACTIVE, false, Instant.now().minusSeconds(10));

        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        List<FeedCandidate> fromAuthor = candidates.stream()
                .filter(c -> c.authorId().equals(author.getId()))
                .toList();

        assertFalse(fromAuthor.isEmpty(), "Deve haver candidatos do autor seguido");
        assertTrue(fromAuthor.stream().allMatch(c -> c.isDirectFollow()),
                "Todos os candidatos do grafo social direto devem ter isDirectFollow=true");
    }

    // ------------------------------------------------------------------
    // 15. targetId resolvido a partir de contextPlaceId
    // ------------------------------------------------------------------

    @Test
    @DisplayName("10.1. targetId usa contextPlaceId quando disponível")
    void targetIdUsesContextPlaceIdWhenAvailable() {
        User requester = createActiveUser();
        User author    = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();
        userFollowRepository.follow(requester.getId(), author.getId());

        Review review = createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now());

        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        FeedCandidate candidate = candidates.stream()
                .filter(c -> c.reviewId().equals(review.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Candidato não encontrado"));

        assertEquals(place.getId(), candidate.targetId(),
                "targetId deve ser o contextPlaceId quando a review possui contexto de place");
    }

    // ------------------------------------------------------------------
    // 16. requesterId null → lista vazia (nenhuma exceção)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("11.1. requesterId nulo retorna lista vazia sem lançar exceção")
    void nullRequesterIdReturnsEmpty() {
        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(null, 10);
        assertNotNull(candidates, "O retorno não deve ser null");
        assertTrue(candidates.isEmpty(), "Com requesterId null o retorno deve ser vazio");
    }
}
