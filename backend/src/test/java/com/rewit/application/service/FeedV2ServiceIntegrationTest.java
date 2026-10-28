package com.rewit.application.service;

import com.rewit.application.dto.feed.FeedV2CandidatePage;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.ReviewReactionRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.ReviewTargetRepository;
import com.rewit.application.port.UserFollowRepository;
import com.rewit.application.port.UserInterestProfileRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.InterestCategory;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewTarget;
import com.rewit.domain.model.User;
import com.rewit.domain.model.UserInterestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste de integração do pipeline de aplicação do Feed V2 (Step 24.4.1 + Personalização por Interesses).
 *
 * <p>Fluxo testado end-to-end sem camada HTTP:
 * {@code PostgreSQL → Candidate Retrieval (com batch categories) → FeedV2Ranker (com afinidade por interesses) → FeedV2Diversifier → FeedV2Service → FeedV2CandidatePage}
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração: FeedV2Service com PostgreSQL Real (Step 24.4.1)")
class FeedV2ServiceIntegrationTest {

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

    @Autowired
    private UserInterestProfileRepository userInterestProfileRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User createActiveUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User user = new User(null, "v2service-" + suffix + "@rewit.com", "hash123", AuthProvider.LOCAL, null);
        User saved = userRepository.save(user);
        Profile profile = new Profile(UUID.randomUUID(), saved.getId(), "srv_" + suffix, "Service " + suffix, "Bio", null);
        profileRepository.save(profile);
        return saved;
    }

    private Place createPlace() {
        return createPlace("RESTAURANTE");
    }

    private Place createPlace(String category) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Place place = new Place(
                null, "Place Srv " + suffix, "place-srv-" + suffix, category != null ? category : "RESTAURANTE",
                "Desc", "Rua Srv, 10", "10", "Bairro", "Cidade", "SP", "BR",
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
                "Texto do feed v2 " + reviewId, anonymous, verified,
                status, visibility,
                -23.55, -46.63, 10.0,
                createdAt != null ? createdAt : Instant.now(),
                Instant.now()
        );
        ReviewTarget rt = new ReviewTarget(UUID.randomUUID(), reviewId, target.getId(),
                BigDecimal.valueOf(4.5), "Nota do prato");
        review.addTarget(rt);
        Review saved = reviewRepository.save(review);
        reviewTargetRepository.save(rt);
        return saved;
    }

    private void setUserInterests(UUID userId, InterestCategory... categories) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.execute(status -> {
            Instant now = Instant.now();
            userInterestProfileRepository.findOrCreateForUpdate(userId, now);
            UserInterestProfile profile = new UserInterestProfile(
                    userId,
                    Set.of(categories),
                    now,
                    now,
                    now
            );
            return userInterestProfileRepository.save(profile);
        });
    }

    // -------------------------------------------------------------------------
    // 1. Pipeline Completo: PostgreSQL → Retrieval → Ranking → Diversidade → Paginação
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. PostgreSQL → FeedV2Service: fatia corretamente page 0, page 1 e além da janela")
    void shouldPaginateRealCandidatesFromPostgreSql() {
        User requester = createActiveUser();
        User author = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();

        userFollowRepository.follow(requester.getId(), author.getId());

        Instant referenceTime = Instant.now();
        // Criar 12 reviews com timestamps decrescentes
        for (int i = 0; i < 12; i++) {
            createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, false, referenceTime.minusSeconds((i + 1) * 60));
        }

        // Page 0, Size 5 -> Retorna 5 itens
        FeedV2CandidatePage p0 = feedV2Service.getCandidatePage(requester.getId(), 0, 5, referenceTime);
        assertNotNull(p0);
        assertEquals(5, p0.items().size());
        assertEquals(0, p0.page());
        assertEquals(5, p0.size());
        assertEquals(12, p0.windowSize());
        assertEquals(3, p0.totalPages());
        assertFalse(p0.isLast());

        // Page 1, Size 5 -> Retorna próximos 5 itens
        FeedV2CandidatePage p1 = feedV2Service.getCandidatePage(requester.getId(), 1, 5, referenceTime);
        assertNotNull(p1);
        assertEquals(5, p1.items().size());
        assertEquals(1, p1.page());
        assertEquals(12, p1.windowSize());
        assertFalse(p1.isLast());

        // Nenhum item de p0 pode estar em p1
        List<UUID> idsP0 = p0.items().stream().map(r -> r.candidate().reviewId()).toList();
        List<UUID> idsP1 = p1.items().stream().map(r -> r.candidate().reviewId()).toList();
        for (UUID id : idsP0) {
            assertFalse(idsP1.contains(id), "Página 1 não deve duplicar elementos da Página 0");
        }

        // Page 2, Size 5 -> Retorna 2 itens (página parcial)
        FeedV2CandidatePage p2 = feedV2Service.getCandidatePage(requester.getId(), 2, 5, referenceTime);
        assertEquals(2, p2.items().size());
        assertEquals(2, p2.page());
        assertEquals(12, p2.windowSize());
        assertTrue(p2.isLast());

        // Page 3, Size 5 -> Além da janela (vazio)
        FeedV2CandidatePage p3 = feedV2Service.getCandidatePage(requester.getId(), 3, 5, referenceTime);
        assertTrue(p3.isEmpty());
        assertEquals(3, p3.page());
        assertEquals(12, p3.windowSize());
        assertTrue(p3.isLast());
    }

    // -------------------------------------------------------------------------
    // 2. Diversidade Aplicada no Pipeline Real
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2. PostgreSQL → FeedV2Service: aplica diversidade de autor impedindo > 2 consecutivos")
    void shouldApplyAuthorDiversityInRealPipeline() {
        User requester = createActiveUser();
        User authorA = createActiveUser();
        User authorB = createActiveUser();

        Place placeA = createPlace();
        Place placeB = createPlace();
        RateableTarget targetA = createTarget();
        RateableTarget targetB = createTarget();

        userFollowRepository.follow(requester.getId(), authorA.getId());
        userFollowRepository.follow(requester.getId(), authorB.getId());

        Instant referenceTime = Instant.now();

        // Author A cria 4 reviews muito recentes (scores altos)
        createReview(authorA, placeA, targetA, "PUBLIC", ReviewStatus.ACTIVE, false, false, referenceTime.minusSeconds(10));
        createReview(authorA, placeA, targetA, "PUBLIC", ReviewStatus.ACTIVE, false, false, referenceTime.minusSeconds(20));
        createReview(authorA, placeA, targetA, "PUBLIC", ReviewStatus.ACTIVE, false, false, referenceTime.minusSeconds(30));
        createReview(authorA, placeA, targetA, "PUBLIC", ReviewStatus.ACTIVE, false, false, referenceTime.minusSeconds(40));

        // Author B cria 2 reviews um pouco menos recentes
        createReview(authorB, placeB, targetB, "PUBLIC", ReviewStatus.ACTIVE, false, false, referenceTime.minusSeconds(50));
        createReview(authorB, placeB, targetB, "PUBLIC", ReviewStatus.ACTIVE, false, false, referenceTime.minusSeconds(60));

        FeedV2CandidatePage page = feedV2Service.getCandidatePage(requester.getId(), 0, 10, referenceTime);

        assertNotNull(page);
        assertEquals(6, page.items().size());

        // Sem diversidade, a ordem seria: A1, A2, A3, A4, B1, B2 (4 de Author A consecutivos)
        // Com FeedV2Diversifier (MAX_CONSECUTIVE = 2):
        // Os dois primeiros são A, o terceiro DEVE ser B para quebrar a sequência de 2 de A!
        UUID firstAuthor = page.items().get(0).candidate().authorId();
        UUID secondAuthor = page.items().get(1).candidate().authorId();
        UUID thirdAuthor = page.items().get(2).candidate().authorId();

        assertEquals(authorA.getId(), firstAuthor, "Primeiro item é de Author A");
        assertEquals(authorA.getId(), secondAuthor, "Segundo item é de Author A");
        assertEquals(authorB.getId(), thirdAuthor, "Terceiro item deve ser de Author B para quebrar a sequência (diversidade)");
    }

    // -------------------------------------------------------------------------
    // 3. Sinais e Score de Votos Úteis
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("3. PostgreSQL → FeedV2Service: votos Helpful elevam a posição do candidato na página")
    void shouldScoreAndPrioritizeReviewWithHelpfulVotes() {
        User requester = createActiveUser();
        User author = createActiveUser();
        User voter = createActiveUser();

        Place place = createPlace();
        RateableTarget target = createTarget();

        userFollowRepository.follow(requester.getId(), author.getId());

        Instant referenceTime = Instant.now();
        Instant time = referenceTime.minusSeconds(100);

        // Duas reviews do mesmo autor publicadas no mesmo segundo
        Review withVotes = createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, false, time);
        createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, false, time);

        // Voto útil na primeira
        reviewReactionRepository.addHelpful(withVotes.getId(), voter.getId());

        FeedV2CandidatePage page = feedV2Service.getCandidatePage(requester.getId(), 0, 10, referenceTime);

        assertNotNull(page);
        assertEquals(2, page.items().size());

        // A review com votos úteis deve ter score maior e vir primeiro
        assertEquals(withVotes.getId(), page.items().get(0).candidate().reviewId(),
                "Review com votos úteis deve liderar a página");
        assertTrue(page.items().get(0).score().value() > page.items().get(1).score().value(),
                "Score da review com helpful deve ser estritamente maior");
    }

    // -------------------------------------------------------------------------
    // 4. Sem Seguidos → Fallback de Cold Start / Descoberta Pública sem Erro
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("4. PostgreSQL → FeedV2Service: requester sem seguidos aciona fallback de descoberta sem exceção")
    void shouldReturnDiscoveryCandidatesWhenNoFollowsExist() {
        User requester = createActiveUser();
        User author = createActiveUser();
        Place place = createPlace();
        RateableTarget target = createTarget();
        createReview(author, place, target, "PUBLIC", ReviewStatus.ACTIVE, false, false, Instant.now());

        FeedV2CandidatePage page = feedV2Service.getCandidatePage(requester.getId(), 0, 10, Instant.now());

        assertNotNull(page);
        assertFalse(page.isEmpty(), "Com cold start ativo e avaliações públicas existentes, a página não deve ser vazia");
        assertTrue(page.windowSize() > 0, "Window size deve refletir os candidatos de descoberta");
        assertTrue(page.totalPages() > 0);
        // Todos os candidatos de descoberta devem possuir isDirectFollow = false
        page.items().forEach(item ->
                assertFalse(item.candidate().isDirectFollow(), "Candidato de descoberta deve ter isDirectFollow=false")
        );
    }

    // -------------------------------------------------------------------------
    // 5. Personalização por Interesses: Match de categoria eleva score (+0.20)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("5. PostgreSQL → FeedV2Service: afinidade por interesses eleva o score do candidato compatível")
    void shouldPrioritizeCandidatesMatchingUserInterests() {
        User requester = createActiveUser();
        User author = createActiveUser();
        userFollowRepository.follow(requester.getId(), author.getId());

        // Define interesse em Cafés
        setUserInterests(requester.getId(), InterestCategory.CAFE, InterestCategory.RESTAURANT, InterestCategory.TECHNOLOGY);

        Place cafePlace = createPlace("CAFETERIA");
        Place gymPlace = createPlace("ACADEMIA");
        RateableTarget targetCafe = createTarget();
        RateableTarget targetGym = createTarget();

        Instant referenceTime = Instant.now();
        Instant sameTimestamp = referenceTime.minusSeconds(100);

        // Ambas as reviews com a mesma idade e mesmo autor
        Review reviewGym = createReview(author, gymPlace, targetGym, "PUBLIC", ReviewStatus.ACTIVE, false, false, sameTimestamp);
        Review reviewCafe = createReview(author, cafePlace, targetCafe, "PUBLIC", ReviewStatus.ACTIVE, false, false, sameTimestamp);

        FeedV2CandidatePage page = feedV2Service.getCandidatePage(requester.getId(), 0, 10, referenceTime);

        assertNotNull(page);
        assertEquals(2, page.items().size());

        // A review de cafeteria deve liderar devido ao boost de interesse (+0.20)
        assertEquals(reviewCafe.getId(), page.items().get(0).candidate().reviewId(),
                "Candidato compatível com interesse do usuário deve vir em primeiro lugar");
        assertEquals(reviewGym.getId(), page.items().get(1).candidate().reviewId());

        double cafeScore = page.items().get(0).score().value();
        double gymScore = page.items().get(1).score().value();

        // Diferença esperada entre match (1.0 * 0.20) e no-match (0.0 * 0.20) = exatamente 0.20
        assertEquals(0.20d, cafeScore - gymScore, 0.001d,
                "Diferença de score entre match e não-match deve ser o peso de interesse (0.20)");
    }

    // -------------------------------------------------------------------------
    // 6. Atualização Dinâmica: Alterar interesses inverte o ranking
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("6. PostgreSQL → FeedV2Service: alteração de interesses reflete imediatamente na ordenação")
    void shouldReorderCandidatesWhenUserChangesInterests() {
        User requester = createActiveUser();
        User author = createActiveUser();
        userFollowRepository.follow(requester.getId(), author.getId());

        // Inicialmente interessado em CAFE
        setUserInterests(requester.getId(), InterestCategory.CAFE, InterestCategory.RESTAURANT, InterestCategory.BOOKS);

        Place cafePlace = createPlace("CAFETERIA");
        Place gamesPlace = createPlace("LOJA DE GAMES");
        RateableTarget targetCafe = createTarget();
        RateableTarget targetGames = createTarget();

        Instant referenceTime = Instant.now();
        Instant sameTimestamp = referenceTime.minusSeconds(200);

        Review reviewCafe = createReview(author, cafePlace, targetCafe, "PUBLIC", ReviewStatus.ACTIVE, false, false, sameTimestamp);
        Review reviewGames = createReview(author, gamesPlace, targetGames, "PUBLIC", ReviewStatus.ACTIVE, false, false, sameTimestamp);

        // Consulta 1: cafe lidera
        FeedV2CandidatePage p1 = feedV2Service.getCandidatePage(requester.getId(), 0, 10, referenceTime);
        assertEquals(reviewCafe.getId(), p1.items().get(0).candidate().reviewId());

        // Altera interesse para GAMES
        setUserInterests(requester.getId(), InterestCategory.GAMES, InterestCategory.TECHNOLOGY, InterestCategory.CULTURE);

        // Consulta 2: games agora lidera
        FeedV2CandidatePage p2 = feedV2Service.getCandidatePage(requester.getId(), 0, 10, referenceTime);
        assertEquals(reviewGames.getId(), p2.items().get(0).candidate().reviewId(),
                "Após mudar interesses para GAMES, review do nicho de jogos deve assumir o topo");
    }

    // -------------------------------------------------------------------------
    // 7. Cold Start / Sem Interesses: Relevância Factual Preservada
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("7. PostgreSQL → FeedV2Service: usuário sem interesses mantém score neutro (0.0) de interesse")
    void shouldMaintainNeutralAffinityForUserWithoutInterests() {
        User requesterWithoutInterests = createActiveUser();
        User author = createActiveUser();
        userFollowRepository.follow(requesterWithoutInterests.getId(), author.getId());

        Place cafePlace = createPlace("CAFETERIA");
        Place gymPlace = createPlace("ACADEMIA");
        RateableTarget targetCafe = createTarget();
        RateableTarget targetGym = createTarget();

        Instant referenceTime = Instant.now();
        // reviewGym é mais recente (criada 10s atrás vs 50s atrás)
        Review reviewGym = createReview(author, gymPlace, targetGym, "PUBLIC", ReviewStatus.ACTIVE, false, false, referenceTime.minusSeconds(10));
        Review reviewCafe = createReview(author, cafePlace, targetCafe, "PUBLIC", ReviewStatus.ACTIVE, false, false, referenceTime.minusSeconds(50));

        FeedV2CandidatePage page = feedV2Service.getCandidatePage(requesterWithoutInterests.getId(), 0, 10, referenceTime);

        // Como não há preferências, recência prevalece: gym (10s) na frente de cafe (50s)
        assertEquals(reviewGym.getId(), page.items().get(0).candidate().reviewId());
        assertEquals(reviewCafe.getId(), page.items().get(1).candidate().reviewId());
    }

    // -------------------------------------------------------------------------
    // 8. Categoria Não Mapeada: Afinidade Neutra sem Exceção
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("8. PostgreSQL → FeedV2Service: alvo com categoria não mapeada recebe afinidade neutra e não é descartado")
    void shouldHandleCandidatesWithUnmappedCategoryGracefully() {
        User requester = createActiveUser();
        User author = createActiveUser();
        userFollowRepository.follow(requester.getId(), author.getId());

        setUserInterests(requester.getId(), InterestCategory.CAFE, InterestCategory.RESTAURANT, InterestCategory.TECHNOLOGY);

        // Categoria livre não mapeada na taxonomia de interesses
        Place unmappedPlace = createPlace("CATEGORIA_INVENTADA_XYZ");
        RateableTarget target = createTarget();

        Review review = createReview(author, unmappedPlace, target, "PUBLIC", ReviewStatus.ACTIVE, false, false, Instant.now());

        FeedV2CandidatePage page = feedV2Service.getCandidatePage(requester.getId(), 0, 10, Instant.now());

        assertNotNull(page);
        assertEquals(1, page.items().size());
        assertEquals(review.getId(), page.items().get(0).candidate().reviewId());
        assertNull(page.items().get(0).candidate().category(), "Categoria não mapeada resulta em InterestCategory nulo");
    }
}
