package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.*;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewTarget;
import com.rewit.presentation.dto.auth.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testes de integração de API / MockMvc para o Feed V2 com personalização real por interesses (V40 / Step 24.5.3).
 *
 * <p>Cenários cobertos com PostgreSQL Real:
 * <ol>
 *   <li>Usuário com interesses A e B prioriza candidatos compatíveis sobre incompatíveis (soft boost +0.20)</li>
 *   <li>Usuário sem interesses cadastrados recebe feed factual sem quebras nem penalidades</li>
 *   <li>Alteração de interesses via PUT /api/v1/me/interests reflete imediatamente na próxima consulta do Feed V2</li>
 *   <li>Ausência de candidatos compatíveis entrega feed factual completo sem descartar nada</li>
 *   <li>Empate de relevância e desempate determinístico (createdAt DESC, reviewId ASC)</li>
 *   <li>Paginação consecutiva determinística sem duplicatas</li>
 *   <li>Regras de moderação (UNDER_REVIEW, REMOVED) e privacidade (PRIVATE) prevalecem sobre match de interesses</li>
 *   <li>Isolamento real entre usuários com preferências distintas consultando o mesmo catálogo</li>
 *   <li>Alvo com categoria não mapeada ("GERAL", "OUTROS") recebe afinidade neutra (0.0)</li>
 *   <li>Soft boost preserva rigorosamente a diversificação de autor (máx 2 consecutivos)</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração HTTP: Personalização do Feed V2 por Interesses com PostgreSQL Real (Step 24.5.3)")
class FeedV2PersonalizationIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ReviewTargetRepository reviewTargetRepository;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private record TestUser(String accessToken, UUID userId, String handle, String displayName) {}

    private TestUser registerUser(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = (prefix + "." + suffix + "@rewit.com").toLowerCase(java.util.Locale.ROOT);
        String handle = (prefix + "_" + suffix).toLowerCase(java.util.Locale.ROOT);

        RegisterRequest request = new RegisterRequest(email, "SenhaSegura123!", handle, "Nome " + prefix);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = json.get("accessToken").asText();
        UUID userId = UUID.fromString(json.get("user").get("id").asText());
        return new TestUser(token, userId, handle, "Nome " + prefix);
    }

    private Place createPlace(String category) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
                null,
                "Place V2 " + suffix,
                "place-v2-" + suffix,
                category != null ? category : "RESTAURANTE",
                "Descrição",
                "Rua Teste, 100",
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

    private RateableTarget createTarget(TargetType type) {
        RateableTarget target = new RateableTarget(UUID.randomUUID(), type != null ? type : TargetType.PLACE);
        return rateableTargetRepository.save(target);
    }

    private Review createReview(UUID authorId, Place place, RateableTarget target,
                                 String visibility, ReviewStatus status, boolean isAnonymous, Instant createdAt) {
        UUID revId = UUID.randomUUID();
        Review review = new Review(
                revId,
                authorId,
                place.getId(),
                "Avaliação para testes de relevância por interesses Feed V2.",
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

        ReviewTarget rt = new ReviewTarget(
                null,
                revId,
                target.getId(),
                BigDecimal.valueOf(4.5),
                "Nota do alvo",
                createdAt != null ? createdAt : Instant.now()
        );
        review.addTarget(rt);

        Review saved = reviewRepository.save(review);
        reviewTargetRepository.save(rt);
        return saved;
    }

    private void setUserInterests(TestUser user, boolean complete, String... codes) throws Exception {
        mockMvc.perform(put("/api/v1/me/interests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("interests", List.of(codes), "completeOnboarding", complete))))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // Cenário 1: Relevância por Interesses (Afinidade Suave / Soft Boost)
    // =========================================================================

    @Test
    @DisplayName("Cenário 1: Candidato compatível com interesse do usuário recebe boost (+0.20) e lidera sobre incompatível")
    void shouldPrioritizeMatchingCandidateOverNonMatchingOne() throws Exception {
        TestUser requester = registerUser("p1Req");
        TestUser author = registerUser("p1Author");
        userFollowRepository.follow(requester.userId(), author.userId());

        // Usuário tem interesse em Cafeterias
        setUserInterests(requester, true, "CAFE", "RESTAURANT", "BOOKS");

        Place placeCafe = createPlace("CAFETERIA");
        Place placeGym = createPlace("ACADEMIA");
        RateableTarget targetCafe = createTarget(TargetType.PLACE);
        RateableTarget targetGym = createTarget(TargetType.PLACE);

        Instant now = Instant.now();
        Instant sameTimestamp = now.minusSeconds(100);

        Review reviewGym = createReview(author.userId(), placeGym, targetGym, "PUBLIC", ReviewStatus.ACTIVE, false, sameTimestamp);
        Review reviewCafe = createReview(author.userId(), placeCafe, targetCafe, "PUBLIC", ReviewStatus.ACTIVE, false, sameTimestamp);

        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(reviewCafe.getId().toString()))
                .andExpect(jsonPath("$.items[1].id").value(reviewGym.getId().toString()));
    }

    // =========================================================================
    // Cenário 2: Usuário sem interesses (Cold Start / Sem Onboarding)
    // =========================================================================

    @Test
    @DisplayName("Cenário 2: Usuário sem interesses cadastrados recebe feed factual normalmente")
    void shouldDeliverFactualFeedForUserWithoutInterests() throws Exception {
        TestUser requesterNoInterests = registerUser("p2NoInt");
        TestUser author = registerUser("p2Author");
        userFollowRepository.follow(requesterNoInterests.userId(), author.userId());

        Place placeCafe = createPlace("CAFETERIA");
        Place placeGym = createPlace("ACADEMIA");
        RateableTarget targetCafe = createTarget(TargetType.PLACE);
        RateableTarget targetGym = createTarget(TargetType.PLACE);

        Instant now = Instant.now();
        // reviewGym é mais recente (10s atrás vs 80s atrás)
        Review reviewGym = createReview(author.userId(), placeGym, targetGym, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(10));
        Review reviewCafe = createReview(author.userId(), placeCafe, targetCafe, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(80));

        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requesterNoInterests.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                // Sem afinidade, a recência comanda a ordem: Gym vem antes de Cafe
                .andExpect(jsonPath("$.items[0].id").value(reviewGym.getId().toString()))
                .andExpect(jsonPath("$.items[1].id").value(reviewCafe.getId().toString()));
    }

    // =========================================================================
    // Cenário 3: Alteração de Interesses após o Onboarding
    // =========================================================================

    @Test
    @DisplayName("Cenário 3: Atualização de interesses via PUT /api/v1/me/interests reordena o Feed V2 na chamada seguinte")
    void shouldImmediatelyReflectInterestChangesInFeedRanking() throws Exception {
        TestUser requester = registerUser("p3Dyn");
        TestUser author = registerUser("p3Author");
        userFollowRepository.follow(requester.userId(), author.userId());

        // Inicialmente interessado em Gastronomia (CAFE)
        setUserInterests(requester, true, "CAFE", "RESTAURANT", "BAKERY");

        Place placeCafe = createPlace("CAFETERIA");
        Place placeGames = createPlace("LOJA DE GAMES");
        RateableTarget targetCafe = createTarget(TargetType.PLACE);
        RateableTarget targetGames = createTarget(TargetType.PLACE);

        Instant now = Instant.now();
        Instant sameTimestamp = now.minusSeconds(200);

        Review reviewCafe = createReview(author.userId(), placeCafe, targetCafe, "PUBLIC", ReviewStatus.ACTIVE, false, sameTimestamp);
        Review reviewGames = createReview(author.userId(), placeGames, targetGames, "PUBLIC", ReviewStatus.ACTIVE, false, sameTimestamp);

        // Feed 1: Cafe lidera
        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(reviewCafe.getId().toString()))
                .andExpect(jsonPath("$.items[1].id").value(reviewGames.getId().toString()));

        // Atualiza perfil para Entretenimento (GAMES)
        setUserInterests(requester, true, "GAMES", "TECHNOLOGY", "CULTURE");

        // Feed 2: Games agora lidera imediatamente
        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(reviewGames.getId().toString()))
                .andExpect(jsonPath("$.items[1].id").value(reviewCafe.getId().toString()));
    }

    // =========================================================================
    // Cenário 4: Ausência de Itens Compatíveis (Feed Completo Preservado)
    // =========================================================================

    @Test
    @DisplayName("Cenário 4: Sem candidatos compatíveis com os interesses, entrega feed factual completo sem descarte")
    void shouldDeliverAllCandidatesEvenWhenNoneMatchUserInterests() throws Exception {
        TestUser requester = registerUser("p4NoMatch");
        TestUser author = registerUser("p4Author");
        userFollowRepository.follow(requester.userId(), author.userId());

        // Interesses em Saúde e Pet
        setUserInterests(requester, true, "HEALTH", "PET", "AUTOMOTIVE");

        // Candidatos de outros nichos
        Place placeCafe = createPlace("CAFETERIA");
        Place placeGames = createPlace("LOJA DE GAMES");
        RateableTarget targetCafe = createTarget(TargetType.PLACE);
        RateableTarget targetGames = createTarget(TargetType.PLACE);

        Instant now = Instant.now();
        Review r1 = createReview(author.userId(), placeCafe, targetCafe, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(30));
        Review r2 = createReview(author.userId(), placeGames, targetGames, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(60));

        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(r1.getId().toString()))
                .andExpect(jsonPath("$.items[1].id").value(r2.getId().toString()));
    }

    // =========================================================================
    // Cenário 5: Empate de Relevância e Desempate Determinístico
    // =========================================================================

    @Test
    @DisplayName("Cenário 5: Candidatos com mesmo score desempatam deterministicamente por createdAt DESC e reviewId ASC")
    void shouldTieBreakDeterministicallyOnIdenticalScores() throws Exception {
        TestUser requester = registerUser("p5TieReq");
        TestUser author = registerUser("p5TieAuthor");
        userFollowRepository.follow(requester.userId(), author.userId());

        setUserInterests(requester, true, "CAFE", "RESTAURANT", "BAKERY");

        Place placeCafe1 = createPlace("CAFETERIA");
        Place placeCafe2 = createPlace("CAFETERIA");
        RateableTarget target1 = createTarget(TargetType.PLACE);
        RateableTarget target2 = createTarget(TargetType.PLACE);

        Instant now = Instant.now();
        Instant exactTimestamp = now.minusSeconds(300);

        // Ambas com exatamente o mesmo autor, categoria, idade e sem helpful/verified
        Review revA = createReview(author.userId(), placeCafe1, target1, "PUBLIC", ReviewStatus.ACTIVE, false, exactTimestamp);
        Review revB = createReview(author.userId(), placeCafe2, target2, "PUBLIC", ReviewStatus.ACTIVE, false, exactTimestamp);

        // Desempate obrigatório por reviewId ASC
        UUID firstId = revA.getId().compareTo(revB.getId()) < 0 ? revA.getId() : revB.getId();
        UUID secondId = revA.getId().compareTo(revB.getId()) < 0 ? revB.getId() : revA.getId();

        MvcResult result = mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andReturn();

        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
        assertEquals(firstId.toString(), items.get(0).get("id").asText(), "Primeiro item deve obedecer reviewId ASC");
        assertEquals(secondId.toString(), items.get(1).get("id").asText(), "Segundo item deve obedecer reviewId ASC");
    }

    // =========================================================================
    // Cenário 6: Paginação Consecutiva sem Duplicação
    // =========================================================================

    @Test
    @DisplayName("Cenário 6: Paginação consecutiva fatia perfeitamente sem duplicar itens entre páginas")
    void shouldPaginateConsecutivelyWithoutDuplicatesWithInterestRanking() throws Exception {
        TestUser requester = registerUser("p6PageReq");
        TestUser author = registerUser("p6PageAuthor");
        userFollowRepository.follow(requester.userId(), author.userId());

        setUserInterests(requester, true, "CAFE", "RESTAURANT", "BAKERY");

        Place placeCafe = createPlace("CAFETERIA");
        RateableTarget target = createTarget(TargetType.PLACE);

        Instant now = Instant.now();
        Review r1 = createReview(author.userId(), placeCafe, target, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(10));
        Review r2 = createReview(author.userId(), placeCafe, target, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(20));
        Review r3 = createReview(author.userId(), placeCafe, target, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(30));

        // Página 0 tamanho 2
        MvcResult p0Result = mockMvc.perform(get("/api/v2/feed")
                        .param("page", "0")
                        .param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andReturn();

        // Página 1 tamanho 2
        MvcResult p1Result = mockMvc.perform(get("/api/v2/feed")
                        .param("page", "1")
                        .param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andReturn();

        JsonNode p0Items = objectMapper.readTree(p0Result.getResponse().getContentAsString()).get("items");
        JsonNode p1Items = objectMapper.readTree(p1Result.getResponse().getContentAsString()).get("items");

        String p0_0 = p0Items.get(0).get("id").asText();
        String p0_1 = p0Items.get(1).get("id").asText();
        String p1_0 = p1Items.get(0).get("id").asText();

        // r3 deve ser a página 1 e nunca estar na página 0
        assertEquals(r1.getId().toString(), p0_0);
        assertEquals(r2.getId().toString(), p0_1);
        assertEquals(r3.getId().toString(), p1_0);
    }

    // =========================================================================
    // Cenário 7: Moderação e Privacidade Prevalecem sobre Afinidade
    // =========================================================================

    @Test
    @DisplayName("Cenário 7: Conteúdo UNDER_REVIEW, REMOVED ou PRIVATE nunca aparece no Feed V2 mesmo com match de interesses")
    void shouldExcludeModeratedOrPrivateContentRegardlessOfInterests() throws Exception {
        TestUser requester = registerUser("p7ModReq");
        TestUser author = registerUser("p7ModAuthor");
        userFollowRepository.follow(requester.userId(), author.userId());

        setUserInterests(requester, true, "CAFE", "RESTAURANT", "BAKERY");

        Place placeCafe = createPlace("CAFETERIA");
        RateableTarget target = createTarget(TargetType.PLACE);

        Review rPublic = createReview(author.userId(), placeCafe, target, "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now().minusSeconds(10));
        Review rPrivate = createReview(author.userId(), placeCafe, target, "PRIVATE", ReviewStatus.ACTIVE, false, Instant.now().minusSeconds(20));
        Review rUnderReview = createReview(author.userId(), placeCafe, target, "PUBLIC", ReviewStatus.UNDER_REVIEW, false, Instant.now().minusSeconds(30));
        Review rRemoved = createReview(author.userId(), placeCafe, target, "PUBLIC", ReviewStatus.REMOVED, false, Instant.now().minusSeconds(40));

        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(rPublic.getId().toString()))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(rPrivate.getId().toString()))))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(rUnderReview.getId().toString()))))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(rRemoved.getId().toString()))));
    }

    // =========================================================================
    // Cenário 8: Isolamento entre Usuários com Preferências Distintas
    // =========================================================================

    @Test
    @DisplayName("Cenário 8: Dois usuários com interesses opostos recebem ordenações espelhadas no mesmo catálogo")
    void shouldIsolateRankingsBetweenTwoUsersWithDifferentInterests() throws Exception {
        TestUser userCafe = registerUser("p8CafeUser");
        TestUser userGames = registerUser("p8GamesUser");
        TestUser author = registerUser("p8Author");

        // Ambos seguem o mesmo autor
        userFollowRepository.follow(userCafe.userId(), author.userId());
        userFollowRepository.follow(userGames.userId(), author.userId());

        setUserInterests(userCafe, true, "CAFE", "RESTAURANT", "BAKERY");
        setUserInterests(userGames, true, "GAMES", "TECHNOLOGY", "CULTURE");

        Place placeCafe = createPlace("CAFETERIA");
        Place placeGames = createPlace("LOJA DE GAMES");
        RateableTarget targetCafe = createTarget(TargetType.PLACE);
        RateableTarget targetGames = createTarget(TargetType.PLACE);

        Instant now = Instant.now();
        Instant sameTimestamp = now.minusSeconds(150);

        Review reviewCafe = createReview(author.userId(), placeCafe, targetCafe, "PUBLIC", ReviewStatus.ACTIVE, false, sameTimestamp);
        Review reviewGames = createReview(author.userId(), placeGames, targetGames, "PUBLIC", ReviewStatus.ACTIVE, false, sameTimestamp);

        // Usuário Café: Cafe em 1º lugar
        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userCafe.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(reviewCafe.getId().toString()))
                .andExpect(jsonPath("$.items[1].id").value(reviewGames.getId().toString()));

        // Usuário Games: Games em 1º lugar
        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userGames.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(reviewGames.getId().toString()))
                .andExpect(jsonPath("$.items[1].id").value(reviewCafe.getId().toString()));
    }

    // =========================================================================
    // Cenário 9: Alvo com Categoria Não Mapeada ou Livre
    // =========================================================================

    @Test
    @DisplayName("Cenário 9: Categoria não mapeada na taxonomia canônica recebe afinidade neutra (0.0) sem falhas")
    void shouldAssignNeutralAffinityForUnmappedCategories() throws Exception {
        TestUser requester = registerUser("p9UnmapReq");
        TestUser author = registerUser("p9Author");
        userFollowRepository.follow(requester.userId(), author.userId());

        setUserInterests(requester, true, "CAFE", "RESTAURANT", "BAKERY");

        // Alvo com categoria livre desconhecida
        Place placeGeneral = createPlace("GERAL_SEM_MAPEAMENTO");
        RateableTarget targetGeneral = createTarget(TargetType.PLACE);

        Review reviewGeneral = createReview(author.userId(), placeGeneral, targetGeneral, "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now());

        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(reviewGeneral.getId().toString()));
    }

    // =========================================================================
    // Cenário 10: Soft Boost Preserva Diversificação de Autor
    // =========================================================================

    @Test
    @DisplayName("Cenário 10: Soft Boost de interesse respeita a diversidade de autor impedindo > 2 consecutivos")
    void shouldRespectAuthorDiversityEvenWithStrongInterestMatch() throws Exception {
        TestUser requester = registerUser("p10DivReq");
        TestUser authorA = registerUser("p10AuthorA");
        TestUser authorB = registerUser("p10AuthorB");

        userFollowRepository.follow(requester.userId(), authorA.userId());
        userFollowRepository.follow(requester.userId(), authorB.userId());

        setUserInterests(requester, true, "CAFE", "RESTAURANT", "BAKERY");

        Place placeCafe = createPlace("CAFETERIA");
        Place placeGym = createPlace("ACADEMIA");
        RateableTarget targetCafe = createTarget(TargetType.PLACE);
        RateableTarget targetGym = createTarget(TargetType.PLACE);

        Instant now = Instant.now();

        // Autor A publica 4 reviews sobre CAFE (todas com score alto + afinidade 0.20)
        createReview(authorA.userId(), placeCafe, targetCafe, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(10));
        createReview(authorA.userId(), placeCafe, targetCafe, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(20));
        createReview(authorA.userId(), placeCafe, targetCafe, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(30));
        createReview(authorA.userId(), placeCafe, targetCafe, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(40));

        // Autor B publica 1 review sobre GYM (sem afinidade)
        createReview(authorB.userId(), placeGym, targetGym, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(50));

        // Sem diversidade, Author A ocuparia os 4 primeiros lugares.
        // Com MAX_CONSECUTIVE = 2, Author B deve quebrar a sequência na posição index 2!
        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[0].author.id").value(authorA.userId().toString()))
                .andExpect(jsonPath("$.items[1].author.id").value(authorA.userId().toString()))
                .andExpect(jsonPath("$.items[2].author.id").value(authorB.userId().toString()))
                .andExpect(jsonPath("$.items[3].author.id").value(authorA.userId().toString()))
                .andExpect(jsonPath("$.items[4].author.id").value(authorA.userId().toString()));
    }
}
