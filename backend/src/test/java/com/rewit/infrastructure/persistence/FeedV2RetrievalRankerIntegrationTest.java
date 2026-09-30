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
import com.rewit.domain.feed.FeedV2Ranker;
import com.rewit.domain.feed.RankedFeedCandidate;
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
 * Testes de integração do pipeline: PostgreSQL → FeedCandidate → FeedV2Ranker (Step 24.3.2).
 *
 * <p>Valida o primeiro trecho do pipeline Feed V2 end-to-end:
 * dados reais são inseridos no banco, recuperados via FeedCandidateRepository e
 * então submetidos ao FeedV2Ranker puro para confirmar que a ordem final é coerente.
 *
 * <p>Sem endpoint, sem MockMvc, sem camada HTTP.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração: Pipeline PostgreSQL → FeedCandidate → FeedV2Ranker (Step 24.3.2)")
class FeedV2RetrievalRankerIntegrationTest {

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
        User user = new User(null, "ranker-" + suffix + "@rewit.com", "hash123", AuthProvider.LOCAL, null);
        User saved = userRepository.save(user);
        Profile profile = new Profile(UUID.randomUUID(), saved.getId(), "rnk_" + suffix, "Ranker " + suffix, "Bio", null);
        profileRepository.save(profile);
        return saved;
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Place place = new Place(
                null, "Ranker Place " + suffix, "rnk-place-" + suffix, "RESTAURANTE",
                "Desc", "Rua Ranker, 1", "1", "Bairro", "Cidade", "SP", "BR",
                -23.55, -46.63, 50, "USER", false, null, "ACTIVE"
        );
        return placeRepository.save(place);
    }

    private RateableTarget createTarget() {
        return rateableTargetRepository.save(new RateableTarget(UUID.randomUUID(), TargetType.PLACE));
    }

    private Review createReview(User author, Place place, RateableTarget target,
                                 String visibility, ReviewStatus status,
                                 boolean verified, boolean anonymous, Instant createdAt) {
        UUID reviewId = UUID.randomUUID();
        Review review = new Review(
                reviewId, author.getId(), place.getId(),
                "Texto ranker " + reviewId, anonymous, verified,
                status, visibility,
                -23.55, -46.63, 10.0,
                createdAt != null ? createdAt : Instant.now(),
                Instant.now()
        );
        ReviewTarget rt = new ReviewTarget(UUID.randomUUID(), reviewId, target.getId(),
                BigDecimal.valueOf(4.5), "Nota ranker");
        review.addTarget(rt);
        Review saved = reviewRepository.save(review);
        reviewTargetRepository.save(rt);
        return saved;
    }

    // ------------------------------------------------------------------
    // 1. Pipeline básico: retrieval → ranker produz lista ordenada
    // ------------------------------------------------------------------

    @Test
    @DisplayName("1. Pipeline PostgreSQL → FeedCandidate → FeedV2Ranker produz lista ordenada")
    void pipelineRetrievalToRankerProducesRankedList() {
        User requester  = createActiveUser();
        User author     = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();
        userFollowRepository.follow(requester.getId(), author.getId());

        Instant now = Instant.now();
        createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, false, now.minusSeconds(300));
        createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, false, now.minusSeconds(200));
        createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, false, now.minusSeconds(100));

        // Step 1: retrieval
        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        List<FeedCandidate> fromAuthor = candidates.stream()
                .filter(c -> c.authorId().equals(author.getId()))
                .toList();
        assertEquals(3, fromAuthor.size(), "Devem existir 3 candidatos do autor seguido");

        // Step 2: ranking
        FeedV2Ranker ranker = new FeedV2Ranker();
        List<RankedFeedCandidate> ranked = ranker.rank(fromAuthor, now);

        assertEquals(3, ranked.size(), "Todos os candidatos devem ser rankeados");
        // Scores devem ser não-negativos e a lista estar em ordem decrescente de score
        for (int i = 0; i < ranked.size() - 1; i++) {
            assertTrue(ranked.get(i).score().value() >= ranked.get(i + 1).score().value(),
                    "A lista rankeada deve estar em ordem decrescente de score");
        }
    }

    // ------------------------------------------------------------------
    // 2. isVerifiedOnSite=false é carregado corretamente no FeedCandidate
    //
    // Nota: is_verified_on_site=TRUE no banco requer um CheckIn VERIFIED via
    // trigger trg_prevent_unverified_review_flag. Esse setup é responsabilidade
    // de um teste de CheckIn dedicado e está fora do escopo do Candidate Retrieval.
    // Aqui validamos que o campo chega false quando a review não é verificada,
    // e que o ranker produz scores distintos para candidatos com sinais distintos
    // (recency diferente, simulado via helpful votes).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("2. isVerifiedOnSite=false é carregado corretamente nos candidatos não verificados")
    void nonVerifiedReviewHasCorrectVerifiedFlag() {
        User requester = createActiveUser();
        User author    = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();
        userFollowRepository.follow(requester.getId(), author.getId());

        // verified=false é o único estado que pode ser inserido diretamente
        // (is_verified_on_site=TRUE exige CheckIn VERIFIED via trigger do banco)
        Review notVerified = createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE,
                false /* verified */, false, Instant.now().minusSeconds(60));

        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        FeedCandidate candidate = candidates.stream()
                .filter(c -> c.reviewId().equals(notVerified.getId()))
                .findFirst()
                .orElseThrow();

        assertFalse(candidate.isVerifiedOnSite(),
                "Review sem CheckIn verificado deve ter isVerifiedOnSite=false no candidato");

        // Ranker opera corretamente com isVerifiedOnSite=false
        FeedV2Ranker ranker = new FeedV2Ranker();
        List<RankedFeedCandidate> ranked = ranker.rank(List.of(candidate), Instant.now());
        assertEquals(1, ranked.size());
        assertTrue(ranked.get(0).score().value() >= 0.0, "Score deve ser não-negativo");
    }

    // ------------------------------------------------------------------
    // 3. Review com helpful votes tem score maior
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3. Review com votos Helpful tem score maior que sem votos (condições equivalentes)")
    void reviewWithHelpfulVotesScoresHigher() {
        User requester = createActiveUser();
        User author    = createActiveUser();
        User voter     = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();
        userFollowRepository.follow(requester.getId(), author.getId());

        Instant sameTime = Instant.now().minusSeconds(30);
        Review withVotes    = createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE,
                false, false, sameTime);
        Review withoutVotes = createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE,
                false, false, sameTime.minusSeconds(1));

        // Adiciona um voto helpful na primeira review
        reviewReactionRepository.addHelpful(withVotes.getId(), voter.getId());

        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        FeedCandidate cWithVotes    = candidates.stream()
                .filter(c -> c.reviewId().equals(withVotes.getId())).findFirst().orElseThrow();
        FeedCandidate cWithoutVotes = candidates.stream()
                .filter(c -> c.reviewId().equals(withoutVotes.getId())).findFirst().orElseThrow();

        assertEquals(1, cWithVotes.helpfulCount(),    "helpfulCount deve ser 1");
        assertEquals(0, cWithoutVotes.helpfulCount(), "helpfulCount deve ser 0");

        FeedV2Ranker ranker = new FeedV2Ranker();
        Instant reference = Instant.now();
        List<RankedFeedCandidate> ranked = ranker.rank(List.of(cWithVotes, cWithoutVotes), reference);

        assertEquals(withVotes.getId(), ranked.get(0).candidate().reviewId(),
                "Review com votos helpful deve ter score maior");
    }

    // ------------------------------------------------------------------
    // 4. Pipeline com lista vazia de candidatos não quebra o ranker
    // ------------------------------------------------------------------

    @Test
    @DisplayName("4. Pipeline com zero candidatos retorna lista vazia do ranker sem exceção")
    void emptyWindowProducesEmptyRankerResult() {
        User requester = createActiveUser();
        // Sem follows → sem candidatos

        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        assertTrue(candidates.isEmpty(), "Sem follows a janela deve ser vazia");

        FeedV2Ranker ranker = new FeedV2Ranker();
        List<RankedFeedCandidate> ranked = ranker.rank(candidates, Instant.now());

        assertTrue(ranked.isEmpty(), "Ranker com candidatos vazios deve retornar lista vazia");
    }

    // ------------------------------------------------------------------
    // 5. isDirectFollow preservado no FeedCandidate para o ranker usar
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5. FeedCandidate chega ao ranker com isDirectFollow=true e contribui para o score social")
    void directFollowSignalReachesRanker() {
        User requester = createActiveUser();
        User author    = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();
        userFollowRepository.follow(requester.getId(), author.getId());

        Review review = createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE,
                false, false, Instant.now().minusSeconds(60));

        List<FeedCandidate> candidates = feedCandidateRepository.retrieveCandidates(
                requester.getId(), FeedCandidateRepository.CANDIDATE_WINDOW);

        FeedCandidate candidate = candidates.stream()
                .filter(c -> c.reviewId().equals(review.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Candidato não encontrado"));

        assertTrue(candidate.isDirectFollow(), "isDirectFollow deve ser true no candidato");

        FeedV2Ranker ranker = new FeedV2Ranker();
        List<RankedFeedCandidate> ranked = ranker.rank(List.of(candidate), Instant.now());

        assertEquals(1, ranked.size());
        // Score social = 1.0 × 0.40 = mínimo 0.40 do score total
        assertTrue(ranked.get(0).score().value() >= 0.40,
                "Score com isDirectFollow=true deve incluir sinal social de 0.40");
    }
}
