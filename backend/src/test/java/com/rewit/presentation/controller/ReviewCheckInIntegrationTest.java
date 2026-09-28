package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.CheckInRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.domain.enums.CheckInStatus;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.enums.VerificationMethod;
import com.rewit.domain.model.CheckIn;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.Review;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.CreateReviewRequest;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.CreateReviewTargetRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração de Presença Física e Check-in com PostGIS (Step 12.0)")
class ReviewCheckInIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private CheckInRepository checkInRepository;

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
        String email = prefix + "." + suffix + "@rewit.com";
        String password = "Password@" + suffix;
        String rawHandle = prefix + "_" + suffix;
        String displayName = "Nome " + suffix;

        RegisterRequest req = new RegisterRequest(email, password, "@" + rawHandle, displayName);
        MvcResult res = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        String token = node.get("accessToken").asText();
        UUID id = UUID.fromString(node.get("user").get("id").asText());
        return new TestUser(token, id, rawHandle, displayName);
    }

    private Place createPlace(double lat, double lon, int validationRadiusMeters) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
                null,
                "Restaurante PostGIS " + suffix,
                "restaurante-postgis-" + suffix,
                "RESTAURANTE",
                "Gastronomia contemporânea com validação espacial",
                "Av. Batel, 1200",
                "1200",
                "Batel",
                "Curitiba",
                "PR",
                "BR",
                lat,
                lon,
                validationRadiusMeters,
                "USER",
                false,
                null,
                "ACTIVE"
        );
        return placeRepository.save(place);
    }

    private RateableTarget createRateableTarget(TargetType type) {
        RateableTarget target = new RateableTarget(UUID.randomUUID(), type);
        return rateableTargetRepository.save(target);
    }

    @Test
    @DisplayName("Teste 1: Review com contextPlaceId, coordenadas dentro do raio e rating elegível gera CheckIn VERIFIED e isVerifiedOnSite=true")
    void test1_shouldCreateReviewWithVerifiedCheckInWhenCoordinatesAreWithinRadius() throws Exception {
        TestUser user = registerUser("chk_in_radius");
        double placeLat = -25.4385;
        double placeLon = -49.2850;
        int radiusMeters = 60;
        Place place = createPlace(placeLat, placeLon, radiusMeters);
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        // Usuário a ~15 metros do centróide (dentro do raio de 60m)
        double userLat = -25.4384;
        double userLon = -49.2849;
        double accuracyMeters = 8.5;

        CreateReviewRequest request = new CreateReviewRequest(
                place.getId(),
                "Experiência incrível almoçando aqui no Batel!",
                false,
                "PUBLIC",
                userLat,
                userLon,
                accuracyMeters,
                List.of(new CreateReviewTargetRequest(target.getId(), BigDecimal.valueOf(5.0), "Ambiente agradável"))
        );

        MvcResult result = mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.isVerifiedOnSite").value(true))
                .andExpect(jsonPath("$.author.id").value(user.userId().toString()))
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID reviewId = UUID.fromString(responseNode.get("id").asText());

        // Validar integridade e estado do CheckIn persistido
        Optional<CheckIn> checkInOpt = checkInRepository.findByReviewId(reviewId);
        assertTrue(checkInOpt.isPresent(), "CheckIn deve ser criado para avaliação no local");
        CheckIn checkIn = checkInOpt.get();

        assertEquals(reviewId, checkIn.getReviewId(), "check_in.review_id deve coincidir com review.id");
        assertEquals(user.userId(), checkIn.getUserId(), "check_in.user_id deve ser rigorosamente o autor da review");
        assertEquals(place.getId(), checkIn.getPlaceId(), "check_in.place_id deve coincidir com contextPlaceId");
        assertEquals(CheckInStatus.VERIFIED, checkIn.getStatus(), "Status do check-in deve ser VERIFIED");
        assertEquals(VerificationMethod.GPS, checkIn.getVerificationMethod(), "Método deve ser GPS");
        assertNotNull(checkIn.getVerifiedAt(), "verifiedAt deve ser preenchido quando VERIFIED");
        assertTrue(checkIn.getDistanceToCentroidMeters() >= 0.0 && checkIn.getDistanceToCentroidMeters() <= radiusMeters,
                "Distância calculada deve estar dentro do raio: " + checkIn.getDistanceToCentroidMeters());

        // Validar que GET /api/v1/reviews/{id} reflete presença física confirmada
        mockMvc.perform(get("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reviewId.toString()))
                .andExpect(jsonPath("$.isVerifiedOnSite").value(true));
    }

    @Test
    @DisplayName("Teste 2: Review com contextPlaceId, coordenadas fora do raio e rating elegível gera CheckIn REJECTED e isVerifiedOnSite=false")
    void test2_shouldCreateReviewWithRejectedCheckInWhenCoordinatesAreOutsideRadius() throws Exception {
        TestUser user = registerUser("chk_out_radius");
        double placeLat = -25.4385;
        double placeLon = -49.2850;
        int radiusMeters = 50;
        Place place = createPlace(placeLat, placeLon, radiusMeters);
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        // Usuário a ~1.5 km do centróide (muito fora do raio de 50m)
        double userLat = -25.4284;
        double userLon = -49.2733;

        CreateReviewRequest request = new CreateReviewRequest(
                place.getId(),
                "Tentativa de check-in remoto ou fora do raio",
                false,
                "PUBLIC",
                userLat,
                userLon,
                15.0,
                List.of(new CreateReviewTargetRequest(target.getId(), BigDecimal.valueOf(4.5), "Nota do local"))
        );

        MvcResult result = mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isVerifiedOnSite").value(false))
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID reviewId = UUID.fromString(responseNode.get("id").asText());

        // Verificar que o CheckIn foi registrado como REJECTED pelo domínio
        Optional<CheckIn> checkInOpt = checkInRepository.findByReviewId(reviewId);
        assertTrue(checkInOpt.isPresent(), "CheckIn rejeitado deve ser registrado para auditoria");
        CheckIn checkIn = checkInOpt.get();

        assertEquals(CheckInStatus.REJECTED, checkIn.getStatus(), "Status deve ser REJECTED quando fora do raio");
        assertNull(checkIn.getVerifiedAt(), "Check-in rejeitado não pode possuir verifiedAt");
        assertTrue(checkIn.getDistanceToCentroidMeters() > radiusMeters, "Distância deve exceder o raio de validação");

        // Validar que GET /api/v1/reviews/{id} retorna isVerifiedOnSite = false
        mockMvc.perform(get("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isVerifiedOnSite").value(false));
    }

    @Test
    @DisplayName("Teste 3: Review com contextPlaceId mas sem coordenadas não gera CheckIn e isVerifiedOnSite=false")
    void test3_shouldCreateReviewWithoutCheckInWhenCoordinatesAreOmitted() throws Exception {
        TestUser user = registerUser("chk_no_coords");
        Place place = createPlace(-25.4385, -49.2850, 50);
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        CreateReviewRequest request = new CreateReviewRequest(
                place.getId(),
                "Avaliação feita de casa sobre experiência passada",
                false,
                "PUBLIC",
                null,
                null,
                null,
                List.of(new CreateReviewTargetRequest(target.getId(), BigDecimal.valueOf(4.0), "Boa comida"))
        );

        MvcResult result = mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isVerifiedOnSite").value(false))
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID reviewId = UUID.fromString(responseNode.get("id").asText());

        Optional<CheckIn> checkInOpt = checkInRepository.findByReviewId(reviewId);
        assertTrue(checkInOpt.isEmpty(), "Não deve criar CheckIn quando coordenadas forem omitidas");
    }

    @Test
    @DisplayName("Teste 4: Review com coordenadas mas sem contextPlaceId não gera CheckIn e isVerifiedOnSite=false")
    void test4_shouldCreateReviewWithoutCheckInWhenContextPlaceIdIsNull() throws Exception {
        TestUser user = registerUser("chk_no_place");
        RateableTarget target = createRateableTarget(TargetType.PRODUCT);

        CreateReviewRequest request = new CreateReviewRequest(
                null, // contextPlaceId nulo
                "Avaliação de produto avulso",
                false,
                "PUBLIC",
                -25.4385,
                -49.2850,
                10.0,
                List.of(new CreateReviewTargetRequest(target.getId(), BigDecimal.valueOf(5.0), "Ótimo produto"))
        );

        MvcResult result = mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isVerifiedOnSite").value(false))
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID reviewId = UUID.fromString(responseNode.get("id").asText());

        Optional<CheckIn> checkInOpt = checkInRepository.findByReviewId(reviewId);
        assertTrue(checkInOpt.isEmpty(), "Não deve criar CheckIn quando contextPlaceId for nulo");
    }

    @Test
    @DisplayName("Teste 5: Rating abaixo do mínimo aceito pelo domínio ou com precisão inválida é rejeitado e não cria CheckIn")
    void test5_shouldValidateRatingThresholdAndNotCreateVerifiedCheckInWhenRatingIsInvalid() throws Exception {
        TestUser user = registerUser("chk_invalid_rating");
        Place place = createPlace(-25.4385, -49.2850, 50);
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        // Submissão com rating < 1.0 (ex: 0.5) via JSON bruto
        String invalidRatingPayload = String.format("""
            {
                "contextPlaceId": "%s",
                "experienceText": "Tentando nota menor que 1.0",
                "isAnonymous": false,
                "visibility": "PUBLIC",
                "userLatitude": -25.4385,
                "userLongitude": -49.2850,
                "targets": [
                    {
                        "rateableTargetId": "%s",
                        "rating": 0.5,
                        "specificComment": "Nota inválida"
                    }
                ]
            }
            """, place.getId(), target.getId());

        mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRatingPayload))
                .andExpect(status().isBadRequest());

        // Submissão com precisão inválida (mais de 1 casa decimal, ex: 4.55)
        String invalidScalePayload = String.format("""
            {
                "contextPlaceId": "%s",
                "experienceText": "Tentando nota com precisão inválida",
                "isAnonymous": false,
                "visibility": "PUBLIC",
                "userLatitude": -25.4385,
                "userLongitude": -49.2850,
                "targets": [
                    {
                        "rateableTargetId": "%s",
                        "rating": 4.55,
                        "specificComment": "Escala inválida"
                    }
                ]
            }
            """, place.getId(), target.getId());

        mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidScalePayload))
                .andExpect(status().is(HttpStatus.UNPROCESSABLE_CONTENT.value()));
    }

    @Test
    @DisplayName("Teste 6: isVerifiedOnSite não pode ser forjado pelo cliente no payload")
    void test6_shouldPreventClientFromForgingVerifiedOnSite() throws Exception {
        TestUser user = registerUser("chk_forge");
        Place place = createPlace(-25.4385, -49.2850, 50);
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        // Cliente tenta injetar "isVerifiedOnSite": true estando fora do raio
        String maliciousPayload = String.format("""
            {
                "contextPlaceId": "%s",
                "experienceText": "Tentando forjar presença física",
                "isAnonymous": false,
                "isVerifiedOnSite": true,
                "visibility": "PUBLIC",
                "userLatitude": -26.0000,
                "userLongitude": -49.0000,
                "targets": [
                    {
                        "rateableTargetId": "%s",
                        "rating": 5.0,
                        "specificComment": "Local falso"
                    }
                ]
            }
            """, place.getId(), target.getId());

        MvcResult result = mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isVerifiedOnSite").value(false))
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID reviewId = UUID.fromString(responseNode.get("id").asText());

        Review review = reviewRepository.findById(reviewId).orElseThrow();
        assertFalse(review.isVerifiedOnSite(), "Review persistido não pode ter isVerifiedOnSite=true quando fora do raio");
    }

    @Test
    @DisplayName("Teste 7: userId do CheckIn deve ser rigorosamente o mesmo autor autenticado da Review")
    void test7_shouldPersistCorrectUserIdInCheckIn() throws Exception {
        TestUser user = registerUser("chk_user_integrity");
        Place place = createPlace(-25.4385, -49.2850, 80);
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        CreateReviewRequest request = new CreateReviewRequest(
                place.getId(),
                "Validação estrita de user_id no check-in",
                false,
                "PUBLIC",
                -25.4385,
                -49.2850,
                5.0,
                List.of(new CreateReviewTargetRequest(target.getId(), BigDecimal.valueOf(5.0), "Nota"))
        );

        MvcResult result = mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID reviewId = UUID.fromString(responseNode.get("id").asText());

        CheckIn checkIn = checkInRepository.findByReviewId(reviewId).orElseThrow();
        assertEquals(user.userId(), checkIn.getUserId(), "O user_id do check-in deve ser exatamente o autor da avaliação");
    }

    @Test
    @DisplayName("Teste 8: Atomicidade transacional - falha na validação de integridade não deixa Review nem CheckIn órfãos")
    void test8_shouldEnsureAtomicityWhenIntegrityCheckFails() throws Exception {
        TestUser user = registerUser("chk_atomic");
        Place place = createPlace(-25.4385, -49.2850, 50);

        UUID nonExistentTargetId = UUID.randomUUID();

        // Submissão com target inexistente deve falhar na aplicação e não persistir nada
        CreateReviewRequest request = new CreateReviewRequest(
                place.getId(),
                "Teste de atomicidade",
                false,
                "PUBLIC",
                -25.4385,
                -49.2850,
                5.0,
                List.of(new CreateReviewTargetRequest(nonExistentTargetId, BigDecimal.valueOf(5.0), "Inexistente"))
        );

        mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RATEABLE_TARGET_NOT_FOUND"));

        // Nenhum review e nenhum checkin para o usuário
        assertTrue(reviewRepository.findByUserId(user.userId()).isEmpty(), "Nenhuma review deve ser persistida");
        assertTrue(checkInRepository.findByUserId(user.userId()).isEmpty(), "Nenhum check-in deve ser persistido");
    }

    @Test
    @DisplayName("Teste 9: Resposta HTTP de criação e consulta não expõe coordenadas brutas nem precisão do usuário")
    void test9_shouldNotExposeUserRawCoordinatesInHttpResponse() throws Exception {
        TestUser user = registerUser("chk_privacy");
        Place place = createPlace(-25.4385, -49.2850, 50);
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        CreateReviewRequest request = new CreateReviewRequest(
                place.getId(),
                "Garantia de privacidade e LGPD",
                false,
                "PUBLIC",
                -25.4385,
                -49.2850,
                4.2,
                List.of(new CreateReviewTargetRequest(target.getId(), BigDecimal.valueOf(5.0), "Privacidade total"))
        );

        // Resposta do POST
        MvcResult postResult = mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isVerifiedOnSite").value(true))
                .andExpect(jsonPath("$.userLatitude").doesNotExist())
                .andExpect(jsonPath("$.userLongitude").doesNotExist())
                .andExpect(jsonPath("$.locationAccuracyMeters").doesNotExist())
                .andExpect(jsonPath("$.latitude").doesNotExist())
                .andExpect(jsonPath("$.longitude").doesNotExist())
                .andExpect(jsonPath("$.coordinates").doesNotExist())
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(postResult.getResponse().getContentAsString());
        UUID reviewId = UUID.fromString(responseNode.get("id").asText());

        // Resposta do GET
        mockMvc.perform(get("/api/v1/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isVerifiedOnSite").value(true))
                .andExpect(jsonPath("$.userLatitude").doesNotExist())
                .andExpect(jsonPath("$.userLongitude").doesNotExist())
                .andExpect(jsonPath("$.locationAccuracyMeters").doesNotExist())
                .andExpect(jsonPath("$.latitude").doesNotExist())
                .andExpect(jsonPath("$.longitude").doesNotExist())
                .andExpect(jsonPath("$.coordinates").doesNotExist());
    }

    @Test
    @DisplayName("Teste 10: Validação de distância em metros utilizando PostGIS real")
    void test10_shouldCalculateDistanceUsingRealPostGis() throws Exception {
        TestUser user = registerUser("chk_postgis_dist");
        // Place em Curitiba: -25.430000, -49.270000 com raio de 200m
        double placeLat = -25.430000;
        double placeLon = -49.270000;
        int radiusMeters = 200;
        Place place = createPlace(placeLat, placeLon, radiusMeters);
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        // Ponto deslocado em ~110m no eixo da latitude (-25.431000 ~ 111 metros ao sul)
        double userLat = -25.431000;
        double userLon = -49.270000;

        CreateReviewRequest request = new CreateReviewRequest(
                place.getId(),
                "Validação de distância real PostGIS",
                false,
                "PUBLIC",
                userLat,
                userLon,
                10.0,
                List.of(new CreateReviewTargetRequest(target.getId(), BigDecimal.valueOf(5.0), "Distância real"))
        );

        MvcResult result = mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isVerifiedOnSite").value(true))
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID reviewId = UUID.fromString(responseNode.get("id").asText());

        CheckIn checkIn = checkInRepository.findByReviewId(reviewId).orElseThrow();
        double calculatedDist = checkIn.getDistanceToCentroidMeters();

        // 1 grau de latitude é aprox 111.139 metros -> 0.001 grau é aprox 111 metros
        assertTrue(calculatedDist > 100.0 && calculatedDist < 125.0,
                "Distância calculada pelo PostGIS deve ser em torno de 111 metros, obtido: " + calculatedDist);
        assertEquals(CheckInStatus.VERIFIED, checkIn.getStatus());
    }
}
