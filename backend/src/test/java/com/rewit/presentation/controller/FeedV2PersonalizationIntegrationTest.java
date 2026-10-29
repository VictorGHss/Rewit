package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.*;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Product;
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
 *   <li>Precedência do local de contexto físico sobre produto avaliado com categorias diferentes</li>
 *   <li>Local de contexto com categoria desconhecida/genérica mantém o candidato elegível com ordenação factual</li>
 *   <li>Avaliação anônima não expõe autor no Feed V2 mesmo com match de interesses</li>
 *   <li>Enriquecimento em lote projeta múltiplos alvos heterogêneos sem consultas N+1 nem falhas</li>
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
    private ProductRepository productRepository;

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

    private Product createProduct(String category) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Product product = new Product(
                UUID.randomUUID(),
                "Produto V2 " + suffix,
                "Marca V2",
                "Modelo V2",
                "Descrição do produto",
                category != null ? category : "GERAL",
                null,
                "ACTIVE"
        );
        return productRepository.save(product);
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
                place != null ? place.getId() : null,
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

    // =========================================================================
    // Cenário 11: Precedência do Contexto Físico sobre Alvo Principal
    // =========================================================================

    @Test
    @DisplayName("Cenário 11: Local de contexto prevalece sobre categoria do produto avaliado e inverte ranking para perfis distintos")
    void shouldPrioritizeContextCategoryOverProductCategoryInRanking() throws Exception {
        TestUser userCafe = registerUser("p11CafeReq");
        TestUser userGames = registerUser("p11GamesReq");
        TestUser author = registerUser("p11Author");

        userFollowRepository.follow(userCafe.userId(), author.userId());
        userFollowRepository.follow(userGames.userId(), author.userId());

        // Conclusão de onboarding exige no mínimo 3 interesses (MIN_INTERESTS_TO_COMPLETE = 3)
        setUserInterests(userCafe, true, "CAFE", "BAKERY", "RESTAURANT");
        setUserInterests(userGames, true, "GAMES", "TECHNOLOGY", "CULTURE");

        // Estabelecimento de contexto: Cafeteria
        Place placeCafe = createPlace("CAFETERIA");

        // Produto 1: Game avaliado dentro da Cafeteria (contextPlaceId = placeCafe)
        Product productGameInCafe = createProduct("GAMES");

        // Produto 2: Game avaliado sem local de contexto (contextPlaceId = null)
        Product productGameStandalone = createProduct("GAMES");

        Instant now = Instant.now();
        Instant sameTimestamp = now.minusSeconds(120);

        // Review 1: contextPlace = Cafeteria, target = Game -> Categoria do candidato é CAFE (precedência do contexto)
        Review reviewInCafe = createReview(author.userId(), placeCafe, productGameInCafe, "PUBLIC", ReviewStatus.ACTIVE, false, sameTimestamp);

        // Review 2: sem contextPlace, target = Game -> Categoria do candidato é GAMES (alvo principal)
        Review reviewStandalone = createReview(author.userId(), null, productGameStandalone, "PUBLIC", ReviewStatus.ACTIVE, false, sameTimestamp);

        // Usuário Café: reviewInCafe ganha soft boost (+0.20), reviewStandalone tem afinidade neutra (0.0) -> reviewInCafe lidera!
        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userCafe.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(reviewInCafe.getId().toString()))
                .andExpect(jsonPath("$.items[1].id").value(reviewStandalone.getId().toString()));

        // Usuário Games: reviewStandalone ganha soft boost (+0.20), reviewInCafe tem afinidade neutra (0.0) -> reviewStandalone lidera!
        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userGames.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(reviewStandalone.getId().toString()))
                .andExpect(jsonPath("$.items[1].id").value(reviewInCafe.getId().toString()));
    }

    // =========================================================================
    // Cenário 12: Contexto Desconhecido não Elimina Candidato e Preserva Ordem
    // =========================================================================

    @Test
    @DisplayName("Cenário 12: Local de contexto com categoria desconhecida/genérica mantém o candidato elegível com ordenação factual")
    void shouldKeepCandidatesWithUnmappedContextPlaceEligible() throws Exception {
        TestUser requester = registerUser("p12Req");
        TestUser author = registerUser("p12Author");
        userFollowRepository.follow(requester.userId(), author.userId());

        setUserInterests(requester, true, "GAMES", "TECHNOLOGY", "CULTURE");

        // Local de contexto com categoria "GERAL" (não mapeável)
        Place placeGeneral = createPlace("GERAL");
        Product productGame = createProduct("GAMES");

        Instant now = Instant.now();
        Review r1 = createReview(author.userId(), placeGeneral, productGame, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(10));
        Review r2 = createReview(author.userId(), placeGeneral, productGame, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(60));

        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(r1.getId().toString()))
                .andExpect(jsonPath("$.items[1].id").value(r2.getId().toString()));
    }

    // =========================================================================
    // Cenário 13: Proteção de Privacidade e Anonimato no Feed V2
    // =========================================================================

    @Test
    @DisplayName("Cenário 13: Avaliação anônima não expõe autor no Feed V2 mesmo com match de interesses")
    void shouldProtectAnonymousAuthorPrivacyInFeedV2() throws Exception {
        // Usuário isolado sem seguir ninguém -> consome discovery candidates
        TestUser discoveryUser = registerUser("p13DiscReq");
        TestUser anonymousAuthor = registerUser("p13AnonAuth");

        setUserInterests(discoveryUser, true, "CAFE", "RESTAURANT", "BAKERY");

        Place placeCafe = createPlace("CAFETERIA");
        RateableTarget targetCafe = createTarget(TargetType.PLACE);

        // Timestamp futuro garante presença dentro da janela de candidatos do feed de descoberta mesmo com dados residuais
        Instant futureTs = Instant.now().plusSeconds(180);
        Review anonReview = createReview(anonymousAuthor.userId(), placeCafe, targetCafe,
                "PUBLIC", ReviewStatus.ACTIVE, true, futureTs);

        MvcResult result = mockMvc.perform(get("/api/v2/feed")
                        .param("size", "50")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + discoveryUser.accessToken()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode items = root.get("items");
        JsonNode anonItem = null;
        for (JsonNode item : items) {
            if (anonReview.getId().toString().equals(item.get("id").asText())) {
                anonItem = item;
                break;
            }
        }

        org.junit.jupiter.api.Assertions.assertNotNull(anonItem, "Review anônima deve estar presente no Feed V2");
        org.junit.jupiter.api.Assertions.assertTrue(anonItem.get("isAnonymous").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals("Anônimo", anonItem.get("author").get("displayName").asText());
        org.junit.jupiter.api.Assertions.assertTrue(anonItem.get("author").get("id") == null || anonItem.get("author").get("id").isNull());
        org.junit.jupiter.api.Assertions.assertTrue(anonItem.get("author").get("handle") == null || anonItem.get("author").get("handle").isNull());
        org.junit.jupiter.api.Assertions.assertTrue(anonItem.get("author").get("avatarUrl") == null || anonItem.get("author").get("avatarUrl").isNull());
    }

    // =========================================================================
    // Cenário 14: Carregamento em Lote Heterogêneo sem N+1
    // =========================================================================

    @Test
    @DisplayName("Cenário 14: Enriquecimento em lote projeta múltiplos alvos heterogêneos sem falha de consulta")
    void shouldResolveBatchCategoriesHeterogeneouslyWithoutFailure() throws Exception {
        TestUser requester = registerUser("p14BatchReq");
        TestUser author = registerUser("p14BatchAuth");
        userFollowRepository.follow(requester.userId(), author.userId());

        setUserInterests(requester, true, "BOOKS", "TECHNOLOGY", "SUPERMARKET");

        Place placeBakery = createPlace("PADARIA");
        Place placeMall = createPlace("SHOPPING");
        Product productBook = createProduct("LIVRARIA");
        Product productPhone = createProduct("SMARTPHONE");
        RateableTarget targetBakery = createTarget(TargetType.PLACE);

        Instant now = Instant.now();
        Review r1 = createReview(author.userId(), placeBakery, targetBakery, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(10));
        Review r2 = createReview(author.userId(), placeMall, productBook, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(20));
        Review r3 = createReview(author.userId(), null, productPhone, "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(30));

        // r3 (TECHNOLOGY) recebe soft boost (+0.20) e ultrapassa r1 e r2 no ranking!
        // r1 e r2 desempatam pela recência factual: r1 (-10s) antes de r2 (-20s)
        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].id").value(r3.getId().toString()))
                .andExpect(jsonPath("$.items[1].id").value(r1.getId().toString()))
                .andExpect(jsonPath("$.items[2].id").value(r2.getId().toString()));
    }

    // =========================================================================
    // Cenário 15: Semântica Distinta de FAST_FOOD vs RESTAURANT no Ranking
    // =========================================================================

    @Test
    @DisplayName("Cenário 15: Preferência FAST_FOOD prioriza hamburgueria/fast food e não restaurante tradicional")
    void shouldPrioritizeFastFoodOverRestaurantWhenFastFoodInterestSelected() throws Exception {
        TestUser requesterFastFood = registerUser("p15FfReq");
        TestUser requesterRestaurant = registerUser("p15RestReq");
        TestUser author = registerUser("p15Auth");

        userFollowRepository.follow(requesterFastFood.userId(), author.userId());
        userFollowRepository.follow(requesterRestaurant.userId(), author.userId());

        // requesterFastFood tem FAST_FOOD (mas NÃO RESTAURANT)
        setUserInterests(requesterFastFood, true, "FAST_FOOD", "CAFE", "BAKERY");
        // requesterRestaurant tem RESTAURANT (mas NÃO FAST_FOOD)
        setUserInterests(requesterRestaurant, true, "RESTAURANT", "CAFE", "BAKERY");

        Place placeBurger = createPlace("HAMBURGUERIA"); // maps to FAST_FOOD
        Place placeBistro = createPlace("RESTAURANTE");  // maps to RESTAURANT

        Instant now = Instant.now();
        Review rBurger = createReview(author.userId(), placeBurger, createTarget(TargetType.PLACE),
                "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(10));
        Review rRestaurant = createReview(author.userId(), placeBistro, createTarget(TargetType.PLACE),
                "PUBLIC", ReviewStatus.ACTIVE, false, now.minusSeconds(10));

        // Para requesterFastFood: rBurger ganha boost de interesse (+0.20) e fica em 1º
        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requesterFastFood.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(rBurger.getId().toString()))
                .andExpect(jsonPath("$.items[1].id").value(rRestaurant.getId().toString()));

        // Para requesterRestaurant: rRestaurant ganha boost de interesse (+0.20) e fica em 1º
        mockMvc.perform(get("/api/v2/feed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requesterRestaurant.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(rRestaurant.getId().toString()))
                .andExpect(jsonPath("$.items[1].id").value(rBurger.getId().toString()));
    }
}
