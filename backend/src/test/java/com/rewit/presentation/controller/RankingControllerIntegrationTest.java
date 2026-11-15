package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceRepository;
import com.rewit.domain.enums.VerificationMethod;
import com.rewit.domain.model.Place;
import com.rewit.infrastructure.persistence.entity.CheckInJpaEntity;
import com.rewit.infrastructure.persistence.entity.RankingConfigurationJpaEntity;
import com.rewit.infrastructure.persistence.entity.ReviewJpaEntity;
import com.rewit.infrastructure.persistence.entity.ReviewReactionJpaEntity;
import com.rewit.infrastructure.persistence.repository.CheckInJpaRepository;
import com.rewit.infrastructure.persistence.repository.RankingConfigurationJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewReactionJpaRepository;
import com.rewit.presentation.dto.auth.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração MockMvc / HTTP: Rankings V1 (ADR-016)")
class RankingControllerIntegrationTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private ReviewJpaRepository reviewJpaRepository;

    @Autowired
    private CheckInJpaRepository checkInJpaRepository;

    @Autowired
    private ReviewReactionJpaRepository reviewReactionJpaRepository;

    @Autowired
    private RankingConfigurationJpaRepository rankingConfigurationJpaRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String defaultCallerToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        // Garante que a ativação dos rankings esteja configurada no passado para os testes
        rankingConfigurationJpaRepository.save(new RankingConfigurationJpaEntity(
                "rankings_activated_at",
                Instant.now().minus(30, ChronoUnit.DAYS)
        ));

        defaultCallerToken = registerUserWithOptIn("caller", false).accessToken();
    }

    private record TestUser(String accessToken, UUID userId, String handle, String displayName) {}

    private TestUser registerUserWithOptIn(String prefix, boolean optIn) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = (prefix + "." + suffix + "@rankingtest.com").toLowerCase();
        String handle = (prefix + suffix).toLowerCase();
        String displayName = "User " + suffix;

        RegisterRequest req = new RegisterRequest(email, "SenhaSegura123!", handle, displayName);
        MvcResult res = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        String token = node.get("accessToken").asText();
        UUID id = UUID.fromString(node.get("user").get("id").asText());

        if (optIn) {
            mockMvc.perform(put("/api/v1/me/ranking-preferences")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"showInRankings\": true}"))
                    .andExpect(status().isOk());
        }

        return new TestUser(token, id, handle, displayName);
    }

    private Place createPlace(String name, String category, String city, String state) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
                null,
                name + " " + suffix,
                "place-" + suffix,
                category,
                "Descrição do local",
                "Rua de Teste, " + suffix,
                "100",
                "Centro",
                city,
                state,
                "BR",
                -22.9056,
                -47.0608,
                100,
                "USER",
                false,
                null,
                "ACTIVE"
        );
        return placeRepository.save(place);
    }

    private ReviewJpaEntity createReview(UUID userId, UUID placeId, boolean anonymous, String status, String visibility) {
        ReviewJpaEntity review = new ReviewJpaEntity();
        review.setId(UUID.randomUUID());
        review.setUserId(userId);
        review.setContextPlaceId(placeId);
        review.setExperienceText("Excelente experiência!");
        review.setAnonymous(anonymous);
        review.setStatus(status);
        review.setVisibility(visibility);
        review.setVerifiedOnSite(false);
        return reviewJpaRepository.saveAndFlush(review);
    }

    private CheckInJpaEntity createCheckIn(UUID userId, UUID placeId, UUID reviewId, String status) {
        CheckInJpaEntity checkIn = new CheckInJpaEntity();
        checkIn.setId(UUID.randomUUID());
        checkIn.setUserId(userId);
        checkIn.setPlaceId(placeId);
        checkIn.setReviewId(reviewId);
        checkIn.setStatus(status);
        checkIn.setVerificationMethod(VerificationMethod.GPS.name());
        checkIn.setCoordinates(GEOMETRY_FACTORY.createPoint(new Coordinate(-47.0608, -22.9056)));
        checkIn.setDistanceToCentroidMeters(BigDecimal.valueOf(15.5));
        checkIn.setVerifiedAt("VERIFIED".equals(status) ? Instant.now() : null);
        CheckInJpaEntity saved = checkInJpaRepository.saveAndFlush(checkIn);
        if ("VERIFIED".equals(status)) {
            reviewJpaRepository.findById(reviewId).ifPresent(r -> {
                r.setVerifiedOnSite(true);
                reviewJpaRepository.saveAndFlush(r);
            });
        }
        return saved;
    }

    private ReviewReactionJpaEntity createReaction(UUID voterId, UUID reviewId, String type) {
        ReviewReactionJpaEntity reaction = new ReviewReactionJpaEntity(
                UUID.randomUUID(),
                reviewId,
                voterId,
                type,
                Instant.now()
        );
        return reviewReactionJpaRepository.saveAndFlush(reaction);
    }

    // =========================================================================
    // 1. Autenticação Obrigatória nos Rankings (ADR-016 / Item 4)
    // =========================================================================

    @Test
    @DisplayName("Requisição sem token nos endpoints de rankings retorna 401 Unauthorized")
    void shouldRequireAuthenticationForRankingEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/rankings/check-ins?state=SP"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/rankings/places-explored?state=SP"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/rankings/helpful-votes?state=SP"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Requisição com token inválido retorna 401 Unauthorized")
    void shouldRejectInvalidToken() throws Exception {
        mockMvc.perform(get("/api/v1/rankings/check-ins?state=SP")
                        .header("Authorization", "Bearer token_completamente_invalido_xyz"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Requisição com token válido é permitida")
    void shouldAllowAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/api/v1/rankings/check-ins?state=SP")
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("isCurrentUser é true somente para a conta autenticada correspondente")
    void shouldSetIsCurrentUserOnlyForAuthenticatedUser() throws Exception {
        String city = "SaoPauloAuth" + UUID.randomUUID().toString().substring(0, 6);
        Place place = createPlace("Teatro Municipal", "CULTURE", city, "SP");

        TestUser participantA = registerUserWithOptIn("parta", true);
        TestUser participantB = registerUserWithOptIn("partb", true);

        ReviewJpaEntity revA = createReview(participantA.userId(), place.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(participantA.userId(), place.getId(), revA.getId(), "VERIFIED");

        ReviewJpaEntity revB = createReview(participantB.userId(), place.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(participantB.userId(), place.getId(), revB.getId(), "VERIFIED");

        // Consulta autenticada como participantA: item de A tem isCurrentUser=true, item de B tem false
        mockMvc.perform(get("/api/v1/rankings/check-ins?state=SP&city=" + city)
                        .header("Authorization", "Bearer " + participantA.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[?(@.user.id == '" + participantA.userId() + "')].isCurrentUser").value(contains(true)))
                .andExpect(jsonPath("$.content[?(@.user.id == '" + participantB.userId() + "')].isCurrentUser").value(contains(false)));

        // Consulta autenticada como participantB: item de B tem isCurrentUser=true, item de A tem false
        mockMvc.perform(get("/api/v1/rankings/check-ins?state=SP&city=" + city)
                        .header("Authorization", "Bearer " + participantB.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[?(@.user.id == '" + participantA.userId() + "')].isCurrentUser").value(contains(false)))
                .andExpect(jsonPath("$.content[?(@.user.id == '" + participantB.userId() + "')].isCurrentUser").value(contains(true)));
    }

    // =========================================================================
    // 2. Validações HTTP e Tratamento RFC 7807 (Autenticado)
    // =========================================================================

    @Test
    @DisplayName("Falta de parâmetro obrigatório state retorna 400 MISSING_PARAMETER ou MISSING_STATE")
    void shouldRequireStateParameter() throws Exception {
        mockMvc.perform(get("/api/v1/rankings/check-ins")
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));

        mockMvc.perform(get("/api/v1/rankings/check-ins?state=")
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_STATE"));
    }

    @Test
    @DisplayName("Sigla de estado inválida retorna 400 INVALID_STATE")
    void shouldRejectInvalidStateCode() throws Exception {
        mockMvc.perform(get("/api/v1/rankings/check-ins?state=XX")
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    @DisplayName("Período inválido ou não suportado (ex: ALL_TIME) retorna 400 INVALID_RANKING_PERIOD")
    void shouldRejectAllTimeOrInvalidPeriod() throws Exception {
        mockMvc.perform(get("/api/v1/rankings/check-ins?state=SP&period=ALL_TIME")
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANKING_PERIOD"));
    }

    @Test
    @DisplayName("Categoria fora do catálogo e não mapeável retorna 400 INVALID_CATEGORY")
    void shouldRejectInvalidCategory() throws Exception {
        mockMvc.perform(get("/api/v1/rankings/check-ins?state=SP&category=CATEGORIA_TOTALMENTE_INVALIDA")
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CATEGORY"));
    }

    // =========================================================================
    // 3. Normalização de Categorias e Isolamento sem Falsos Positivos (Item 1)
    // =========================================================================

    @Test
    @DisplayName("Normalização canônica de categorias: Café, Bistrô, Hambúrguer, catering.restaurant, catering.fast_food")
    void shouldNormalizeCategoriesAndPreventFalsePositives() throws Exception {
        String city = "CatTest" + UUID.randomUUID().toString().substring(0, 6);

        Place cafePlace = createPlace("Aroma Café", "Café", city, "SP");
        Place bistroPlace = createPlace("Le Paris", "Bistrô", city, "SP");
        Place burgerPlace = createPlace("Burger House", "Hambúrguer", city, "SP");
        Place catRestPlace = createPlace("Restaurante Vila", "catering.restaurant", city, "SP");
        Place catFastPlace = createPlace("Snack Express", "catering.fast_food", city, "SP");

        TestUser userCafe = registerUserWithOptIn("ucafe", true);
        TestUser userBistro = registerUserWithOptIn("ubistro", true);
        TestUser userBurger = registerUserWithOptIn("uburger", true);
        TestUser userCatRest = registerUserWithOptIn("ucatrest", true);
        TestUser userCatFast = registerUserWithOptIn("ucatfast", true);

        // Check-in no Café
        ReviewJpaEntity r1 = createReview(userCafe.userId(), cafePlace.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(userCafe.userId(), cafePlace.getId(), r1.getId(), "VERIFIED");

        // Check-in no Bistrô
        ReviewJpaEntity r2 = createReview(userBistro.userId(), bistroPlace.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(userBistro.userId(), bistroPlace.getId(), r2.getId(), "VERIFIED");

        // Check-in no Hambúrguer
        ReviewJpaEntity r3 = createReview(userBurger.userId(), burgerPlace.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(userBurger.userId(), burgerPlace.getId(), r3.getId(), "VERIFIED");

        // Check-in no catering.restaurant
        ReviewJpaEntity r4 = createReview(userCatRest.userId(), catRestPlace.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(userCatRest.userId(), catRestPlace.getId(), r4.getId(), "VERIFIED");

        // Check-in no catering.fast_food
        ReviewJpaEntity r5 = createReview(userCatFast.userId(), catFastPlace.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(userCatFast.userId(), catFastPlace.getId(), r5.getId(), "VERIFIED");

        // 1. Consulta category=CAFE: somente userCafe aparece
        mockMvc.perform(get("/api/v1/rankings/check-ins?state=SP&city=" + city + "&category=CAFE")
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].user.id").value(userCafe.userId().toString()));

        // 2. Consulta category=RESTAURANT: aparecem Bistrô e catering.restaurant, NÃO aparece Hambúrguer nem catering.fast_food
        mockMvc.perform(get("/api/v1/rankings/check-ins?state=SP&city=" + city + "&category=RESTAURANT")
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[*].user.id", containsInAnyOrder(
                        userBistro.userId().toString(),
                        userCatRest.userId().toString()
                )))
                .andExpect(jsonPath("$.content[*].user.id", not(hasItem(userBurger.userId().toString()))))
                .andExpect(jsonPath("$.content[*].user.id", not(hasItem(userCatFast.userId().toString()))));

        // 3. Consulta category=FAST_FOOD: aparecem Hambúrguer e catering.fast_food, NÃO aparece Bistrô nem catering.restaurant
        mockMvc.perform(get("/api/v1/rankings/check-ins?state=SP&city=" + city + "&category=FAST_FOOD")
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[*].user.id", containsInAnyOrder(
                        userBurger.userId().toString(),
                        userCatFast.userId().toString()
                )))
                .andExpect(jsonPath("$.content[*].user.id", not(hasItem(userBistro.userId().toString()))))
                .andExpect(jsonPath("$.content[*].user.id", not(hasItem(userCatRest.userId().toString()))));
    }

    // =========================================================================
    // 4. Normalização de Cidade com rewit_search_normalize (Item 2)
    // =========================================================================

    @Test
    @DisplayName("Normalização de cidade trata acentos, caixa e espaços, e isola cidades homônimas pela UF")
    void shouldNormalizeCityAndIsolateHomonymousAcrossStates() throws Exception {
        String cityFloripa = "Florianópolis";
        Place placeFloripa = createPlace("Pizzaria Lagoa", "RESTAURANT", cityFloripa, "SC");

        TestUser floripaUser = registerUserWithOptIn("flp", true);
        ReviewJpaEntity r1 = createReview(floripaUser.userId(), placeFloripa.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(floripaUser.userId(), placeFloripa.getId(), r1.getId(), "VERIFIED");

        // 1. Diferença de acento: busca "Florianopolis" encontra "Florianópolis"
        mockMvc.perform(get("/api/v1/rankings/check-ins?state=SC&city=Florianopolis")
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].user.id").value(floripaUser.userId().toString()));

        // 2. Maiúsculas e minúsculas: busca "FLORIANOPOLIS" e "florianopolis"
        mockMvc.perform(get("/api/v1/rankings/check-ins?state=SC&city=FLORIANOPOLIS")
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].user.id").value(floripaUser.userId().toString()));

        // 3. Espaços antes e depois: busca "  Florianopolis  "
        mockMvc.perform(get("/api/v1/rankings/check-ins?state=SC")
                        .param("city", "  Florianopolis  ")
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].user.id").value(floripaUser.userId().toString()));

        // 4. Cidades homônimas em estados diferentes permanecem isoladas pela UF
        String homonymCity = "Cruzeiro" + UUID.randomUUID().toString().substring(0, 4);
        Place placeSP = createPlace("Local SP", "BAR", homonymCity, "SP");
        Place placeDF = createPlace("Local DF", "BAR", homonymCity, "DF");

        TestUser userSP = registerUserWithOptIn("usp", true);
        TestUser userDF = registerUserWithOptIn("udf", true);

        ReviewJpaEntity rSP = createReview(userSP.userId(), placeSP.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(userSP.userId(), placeSP.getId(), rSP.getId(), "VERIFIED");

        ReviewJpaEntity rDF = createReview(userDF.userId(), placeDF.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(userDF.userId(), placeDF.getId(), rDF.getId(), "VERIFIED");

        // Consulta em SP: retorna apenas userSP
        mockMvc.perform(get("/api/v1/rankings/check-ins?state=SP&city=" + homonymCity)
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].user.id").value(userSP.userId().toString()));

        // Consulta no DF: retorna apenas userDF
        mockMvc.perform(get("/api/v1/rankings/check-ins?state=DF&city=" + homonymCity)
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].user.id").value(userDF.userId().toString()));
    }

    // =========================================================================
    // 5. Opt-in e Exclusão de Não Participantes (Privacidade)
    // =========================================================================

    @Test
    @DisplayName("Usuário sem opt-in (showInRankings=false) não aparece nos rankings")
    void shouldExcludeUsersWithoutOptIn() throws Exception {
        String city = "CampinasOpt" + UUID.randomUUID().toString().substring(0, 6);
        Place place = createPlace("Padaria do Centro", "BAKERY", city, "SP");

        // User 1: SEM opt-in (padrão)
        TestUser userWithoutOptIn = registerUserWithOptIn("noopt", false);
        ReviewJpaEntity r1 = createReview(userWithoutOptIn.userId(), place.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(userWithoutOptIn.userId(), place.getId(), r1.getId(), "VERIFIED");

        // User 2: COM opt-in
        TestUser userWithOptIn = registerUserWithOptIn("withopt", true);
        ReviewJpaEntity r2 = createReview(userWithOptIn.userId(), place.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(userWithOptIn.userId(), place.getId(), r2.getId(), "VERIFIED");

        mockMvc.perform(get("/api/v1/rankings/check-ins?state=SP&city=" + city)
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].user.id").value(userWithOptIn.userId().toString()))
                .andExpect(jsonPath("$.content[0].score").value(1));
    }

    // =========================================================================
    // 6. Ranking de Check-ins: Status VERIFIED e Avaliações Elegíveis
    // =========================================================================

    @Test
    @DisplayName("Ranking de check-ins conta apenas VERIFIED, avaliações públicas não anônimas e respeita o estado")
    void shouldFilterCheckInsCorrectly() throws Exception {
        String citySP = "CitySP" + UUID.randomUUID().toString().substring(0, 6);
        String cityRJ = "CityRJ" + UUID.randomUUID().toString().substring(0, 6);

        Place placeSP = createPlace("Restaurante Paulista", "RESTAURANT", citySP, "SP");
        Place placeRJ = createPlace("Quiosque Carioca", "BAR", cityRJ, "RJ");

        TestUser leader = registerUserWithOptIn("lead", true);
        TestUser runnerUp = registerUserWithOptIn("run", true);
        TestUser ignoredUser = registerUserWithOptIn("ign", true);

        // Leader: 2 check-ins válidos em SP
        ReviewJpaEntity r1 = createReview(leader.userId(), placeSP.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(leader.userId(), placeSP.getId(), r1.getId(), "VERIFIED");
        ReviewJpaEntity r2 = createReview(leader.userId(), placeSP.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(leader.userId(), placeSP.getId(), r2.getId(), "VERIFIED");

        // RunnerUp: 1 check-in válido em SP
        ReviewJpaEntity r3 = createReview(runnerUp.userId(), placeSP.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(runnerUp.userId(), placeSP.getId(), r3.getId(), "VERIFIED");

        // Ignored: check-in rejeitado
        ReviewJpaEntity r4 = createReview(ignoredUser.userId(), placeSP.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(ignoredUser.userId(), placeSP.getId(), r4.getId(), "REJECTED");

        // Ignored: check-in com review anônimo
        ReviewJpaEntity r5 = createReview(ignoredUser.userId(), placeSP.getId(), true, "ACTIVE", "PUBLIC");
        createCheckIn(ignoredUser.userId(), placeSP.getId(), r5.getId(), "VERIFIED");

        // Leader em outro estado (RJ) - não deve contar no ranking de SP
        ReviewJpaEntity r6 = createReview(leader.userId(), placeRJ.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(leader.userId(), placeRJ.getId(), r6.getId(), "VERIFIED");

        mockMvc.perform(get("/api/v1/rankings/check-ins?state=SP&city=" + citySP + "&period=WEEK")
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].user.id").value(leader.userId().toString()))
                .andExpect(jsonPath("$.content[0].score").value(2))
                .andExpect(jsonPath("$.content[0].rankPosition").value(1))
                .andExpect(jsonPath("$.content[1].user.id").value(runnerUp.userId().toString()))
                .andExpect(jsonPath("$.content[1].score").value(1))
                .andExpect(jsonPath("$.content[1].rankPosition").value(2))
                .andExpect(jsonPath("$.metadata.rankingType").value("CHECK_INS"))
                .andExpect(jsonPath("$.metadata.state").value("SP"));
    }

    // =========================================================================
    // 7. Ranking de Locais Distintos Explorados (/places-explored)
    // =========================================================================

    @Test
    @DisplayName("Ranking de locais explorados conta DISTINCT context_place_id (múltiplas avaliações no mesmo local contam 1)")
    void shouldCountDistinctPlacesExplored() throws Exception {
        String city = "Santos" + UUID.randomUUID().toString().substring(0, 6);
        Place placeA = createPlace("Café Aroma", "CAFE", city, "SP");
        Place placeB = createPlace("Pizzaria Bela", "RESTAURANT", city, "SP");

        TestUser explorer = registerUserWithOptIn("expl", true);

        // Explorer avalia Place A duas vezes
        createReview(explorer.userId(), placeA.getId(), false, "ACTIVE", "PUBLIC");
        createReview(explorer.userId(), placeA.getId(), false, "ACTIVE", "PUBLIC");

        // Explorer avalia Place B uma vez
        createReview(explorer.userId(), placeB.getId(), false, "ACTIVE", "PUBLIC");

        mockMvc.perform(get("/api/v1/rankings/places-explored?state=SP&city=" + city)
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].user.id").value(explorer.userId().toString()))
                .andExpect(jsonPath("$.content[0].score").value(2)) // 2 locais distintos, mesmo com 3 avaliações
                .andExpect(jsonPath("$.content[0].rankPosition").value(1))
                .andExpect(jsonPath("$.metadata.rankingType").value("PLACES_EXPLORED"));
    }

    // =========================================================================
    // 8. Ranking de Votos Úteis (/helpful-votes)
    // =========================================================================

    @Test
    @DisplayName("Ranking de votos úteis conta reações HELPFUL excluindo voto próprio e avaliações anônimas")
    void shouldCountHelpfulVotesCorrectly() throws Exception {
        String city = "CampinasVot" + UUID.randomUUID().toString().substring(0, 6);
        Place place = createPlace("Churrascaria Gaúcha", "RESTAURANT", city, "SP");

        TestUser author = registerUserWithOptIn("auth", true);
        TestUser voter1 = registerUserWithOptIn("vot1", true);
        TestUser voter2 = registerUserWithOptIn("vot2", true);

        // Review público de author
        ReviewJpaEntity reviewPublic = createReview(author.userId(), place.getId(), false, "ACTIVE", "PUBLIC");

        // Voto útil legítimo de voter1
        createReaction(voter1.userId(), reviewPublic.getId(), "HELPFUL");

        // Voto útil legítimo de voter2
        createReaction(voter2.userId(), reviewPublic.getId(), "HELPFUL");

        // Voto próprio do autor (não deve contar)
        createReaction(author.userId(), reviewPublic.getId(), "HELPFUL");

        // Review anônimo do autor com voto útil (não deve contar)
        ReviewJpaEntity reviewAnon = createReview(author.userId(), place.getId(), true, "ACTIVE", "PUBLIC");
        createReaction(voter1.userId(), reviewAnon.getId(), "HELPFUL");

        mockMvc.perform(get("/api/v1/rankings/helpful-votes?state=SP&city=" + city)
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].user.id").value(author.userId().toString()))
                .andExpect(jsonPath("$.content[0].score").value(2))
                .andExpect(jsonPath("$.content[0].rankPosition").value(1))
                .andExpect(jsonPath("$.metadata.rankingType").value("HELPFUL_VOTES"));
    }

    // =========================================================================
    // 9. Empate Real via DENSE_RANK() e Ordenação Determinística
    // =========================================================================

    @Test
    @DisplayName("Empates reais recebem a mesma posição via DENSE_RANK() e ordenação secundária por userId ASC")
    void shouldHandleDenseRankTiesDeterministically() throws Exception {
        String city = "Ribeirao" + UUID.randomUUID().toString().substring(0, 6);
        Place place = createPlace("Livraria Cultura", "BOOKS", city, "SP");

        TestUser userA = registerUserWithOptIn("tiea", true);
        TestUser userB = registerUserWithOptIn("tieb", true);
        TestUser userC = registerUserWithOptIn("tiec", true);

        // User A e User B empatam com 2 check-ins
        ReviewJpaEntity ra1 = createReview(userA.userId(), place.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(userA.userId(), place.getId(), ra1.getId(), "VERIFIED");
        ReviewJpaEntity ra2 = createReview(userA.userId(), place.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(userA.userId(), place.getId(), ra2.getId(), "VERIFIED");

        ReviewJpaEntity rb1 = createReview(userB.userId(), place.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(userB.userId(), place.getId(), rb1.getId(), "VERIFIED");
        ReviewJpaEntity rb2 = createReview(userB.userId(), place.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(userB.userId(), place.getId(), rb2.getId(), "VERIFIED");

        // User C faz 1 check-in
        ReviewJpaEntity rc1 = createReview(userC.userId(), place.getId(), false, "ACTIVE", "PUBLIC");
        createCheckIn(userC.userId(), place.getId(), rc1.getId(), "VERIFIED");

        mockMvc.perform(get("/api/v1/rankings/check-ins?state=SP&city=" + city)
                        .header("Authorization", "Bearer " + defaultCallerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[0].rankPosition").value(1))
                .andExpect(jsonPath("$.content[0].score").value(2))
                .andExpect(jsonPath("$.content[1].rankPosition").value(1))
                .andExpect(jsonPath("$.content[1].score").value(2))
                .andExpect(jsonPath("$.content[2].rankPosition").value(2))
                .andExpect(jsonPath("$.content[2].score").value(1));
    }
}
