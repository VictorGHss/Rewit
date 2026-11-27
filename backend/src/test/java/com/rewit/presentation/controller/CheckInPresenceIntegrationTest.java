package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.CheckInRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.domain.enums.CheckInStatus;
import com.rewit.domain.enums.PresenceVerificationOutcome;
import com.rewit.domain.enums.TargetType;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import com.rewit.domain.model.CheckIn;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.RateableTarget;
import com.rewit.presentation.dto.auth.RegisterRequest;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.CreateReviewRequest;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.CreateReviewTargetRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Check-in V1 com PostgreSQL/PostGIS real: teto do raio de validação (DTO, domínio e banco), precisão do GPS e
 * no máximo um check-in verificado por usuário e local em 24 horas, inclusive sob requisições concorrentes.
 */
@SpringBootTest
@ActiveProfiles("local")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Integração: check-in V1 (raio, precisão do GPS e frequência)")
class CheckInPresenceIntegrationTest {

    private static final double PLACE_LAT = -25.4385;
    private static final double PLACE_LON = -49.2850;
    // ~15 m do centróide
    private static final double NEAR_LAT = -25.4384;
    private static final double NEAR_LON = -49.2849;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    @Autowired
    private CheckInRepository checkInRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Environment environment;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private record TestUser(String accessToken, UUID userId) {}

    private record Created(UUID reviewId, JsonNode body) {}

    private TestUser registerUser(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest req = new RegisterRequest(prefix + "." + suffix + "@rewit.com", "Password@" + suffix,
                prefix + "_" + suffix, "Nome " + suffix);
        MvcResult res = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        return new TestUser(node.get("accessToken").asText(), UUID.fromString(node.get("user").get("id").asText()));
    }

    private Place createPlace(int validationRadiusMeters) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return placeRepository.save(new Place(null, "Café Presença " + suffix, "cafe-presenca-" + suffix, "CAFETERIA",
                null, "Rua XV de Novembro, 100", "100", "Centro", "Curitiba", "PR", "BR",
                PLACE_LAT, PLACE_LON, validationRadiusMeters, "USER", false, null, "ACTIVE"));
    }

    private UUID target() {
        RateableTarget target = rateableTargetRepository.save(new RateableTarget(UUID.randomUUID(), TargetType.PLACE));
        return target.getId();
    }

    private Created review(TestUser user, Place place, Double lat, Double lon, Double accuracy) throws Exception {
        CreateReviewRequest request = new CreateReviewRequest(place.getId(), null, false, "PUBLIC", lat, lon, accuracy,
                List.of(new CreateReviewTargetRequest(target(), BigDecimal.valueOf(4.0), null)));
        MvcResult result = mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Created(UUID.fromString(body.get("id").asText()), body);
    }

    private static void assertCheckIn(Created created, String status, String reason) {
        JsonNode checkIn = created.body().get("checkIn");
        assertNotNull(checkIn, "a criação informa o resultado da verificação de presença");
        assertEquals(status, checkIn.get("status").asText());
        if (reason == null) {
            assertTrue(checkIn.get("reason") == null || checkIn.get("reason").isNull());
        } else {
            assertEquals(reason, checkIn.get("reason").asText());
        }
        assertEquals("VERIFIED".equals(status), created.body().get("isVerifiedOnSite").asBoolean());
        // Nada da medição volta ao cliente
        for (String field : List.of("latitude", "longitude", "distanceMeters", "distanceToCentroidMeters",
                "accuracyMeters")) {
            assertNull(checkIn.get(field), field);
        }
        assertNull(created.body().get("userLatitude"));
        assertNull(created.body().get("distanceToCentroidMeters"));
    }

    private long verifiedCount(UUID userId, UUID placeId) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM check_ins WHERE user_id = ? AND place_id = ? "
                + "AND status = 'VERIFIED'", Long.class, userId, placeId);
        return count != null ? count : 0;
    }

    private void assertNoCoordinatesPersisted(UUID reviewId, String expectedStatus, String expectedRejectionReason,
                                              boolean expectDistanceNull) {
        Map<String, Object> checkInRow = jdbcTemplate.queryForMap(
                "SELECT status, coordinates IS NULL AS coords_null, distance_to_centroid_meters, rejection_reason "
                        + "FROM check_ins WHERE review_id = ?", reviewId);
        assertEquals(expectedStatus, checkInRow.get("status"));
        assertEquals(Boolean.TRUE, checkInRow.get("coords_null"), "check_ins.coordinates deve ser NULL");
        if (expectDistanceNull) {
            assertNull(checkInRow.get("distance_to_centroid_meters"),
                    "distance_to_centroid_meters deve ser NULL para check-in REJECTED");
        } else {
            assertNotNull(checkInRow.get("distance_to_centroid_meters"));
        }
        assertEquals(expectedRejectionReason, checkInRow.get("rejection_reason"));

        Map<String, Object> reviewRow = jdbcTemplate.queryForMap(
                "SELECT is_verified_on_site, user_coordinates IS NULL AS user_coords_null, "
                        + "location_accuracy_meters IS NULL AS accuracy_null "
                        + "FROM reviews WHERE id = ?", reviewId);
        assertEquals("VERIFIED".equals(expectedStatus), reviewRow.get("is_verified_on_site"));
        assertEquals(Boolean.TRUE, reviewRow.get("user_coords_null"), "reviews.user_coordinates deve ser NULL");
        assertEquals(Boolean.TRUE, reviewRow.get("accuracy_null"), "reviews.location_accuracy_meters deve ser NULL");
    }

    private Map<String, Object> placeRequest(Integer radius) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Local " + UUID.randomUUID().toString().substring(0, 8));
        body.put("category", "CAFETERIA");
        body.put("addressText", "Rua das Flores, 10");
        body.put("city", "Curitiba");
        body.put("state", "PR");
        body.put("latitude", PLACE_LAT);
        body.put("longitude", PLACE_LON);
        if (radius != null) {
            body.put("validationRadiusMeters", radius);
        }
        return body;
    }

    // -----------------------------------------------------------------------------------------------------------
    // Raio de validação
    // -----------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("POST /places aceita raio até 250 m e usa 50 m sem raio; acima de 250 m responde 400 sem gravar")
    void placeCreationEnforcesMaximumRadius() throws Exception {
        TestUser user = registerUser("radius");

        for (Integer radius : new Integer[] {250, null}) {
            MvcResult res = mockMvc.perform(post("/api/v1/places")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(placeRequest(radius))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID id = UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
            assertEquals(radius != null ? radius : 50, jdbcTemplate.queryForObject(
                    "SELECT validation_radius_meters FROM places WHERE id = ?", Integer.class, id));
        }

        Map<String, Object> tooLarge = placeRequest(251);
        mockMvc.perform(post("/api/v1/places")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tooLarge)))
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM places WHERE name = ?", Integer.class,
                tooLarge.get("name")));

        // Adoção (local vindo de provedor externo) passa pelo mesmo limite
        Map<String, Object> adopt = placeRequest(20_000);
        adopt.put("externalReference", Map.of("provider", "GEOAPIFY", "externalId", "ext-" + UUID.randomUUID()));
        mockMvc.perform(post("/api/v1/places/adopt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adopt)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Banco recusa atualizar um local para raio acima de 250 m (chk_places_validation_radius_max)")
    void databaseRejectsRadiusAboveMaximumOnUpdate() {
        Place place = createPlace(250);
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () ->
                jdbcTemplate.update("UPDATE places SET validation_radius_meters = 251 WHERE id = ?", place.getId()));
        assertTrue(ex.getMessage().contains("chk_places_validation_radius_max"), ex.getMessage());
        assertEquals(250, jdbcTemplate.queryForObject("SELECT validation_radius_meters FROM places WHERE id = ?",
                Integer.class, place.getId()));
    }

    @Test
    @DisplayName("Dado legado acima de 250 m faz a verificação da V41 falhar com a contagem, sem alterar o registro")
    void legacyRadiusAboveMaximumStopsTheMigration() throws Exception {
        Place place = createPlace(100);
        String migration = new String(new ClassPathResource("db/migration/V41__checkin_presence_hardening.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String guard = migration.substring(migration.indexOf("DO $$"), migration.indexOf("$$;") + 3);

        // Role de migração, numa transação desfeita ao final: remove a CHECK, grava o raio legado e roda a guarda
        try (Connection conn = DriverManager.getConnection(environment.getProperty("spring.datasource.url"),
                environment.getProperty("spring.flyway.user"), environment.getProperty("spring.flyway.password"))) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE places DROP CONSTRAINT chk_places_validation_radius_max");
                stmt.execute("UPDATE places SET validation_radius_meters = 300 WHERE id = '" + place.getId() + "'");
                SQLException ex = assertThrows(SQLException.class, () -> stmt.execute(guard));
                assertTrue(ex.getMessage().contains("V41: 1 local(is) com validation_radius_meters acima de 250"),
                        ex.getMessage());
            } finally {
                conn.rollback();
            }
        }

        assertEquals(100, jdbcTemplate.queryForObject("SELECT validation_radius_meters FROM places WHERE id = ?",
                Integer.class, place.getId()), "a guarda não altera o raio gravado");
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM pg_constraint "
                + "WHERE conname = 'chk_places_validation_radius_max'", Integer.class), "CHECK preservada");
    }

    // -----------------------------------------------------------------------------------------------------------
    // Precisão do GPS e distância
    // -----------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Precisão de até 100 m verifica; acima de 100 m, ausente ou fora do raio publica sem check-in verificado e sem persistir coordenadas")
    void accuracyAndDistanceDecidePresence() throws Exception {
        TestUser user = registerUser("accuracy");

        Created atLimit = review(user, createPlace(60), NEAR_LAT, NEAR_LON, 100.0);
        assertCheckIn(atLimit, "VERIFIED", null);
        CheckIn verified = checkInRepository.findByReviewId(atLimit.reviewId()).orElseThrow();
        assertEquals(CheckInStatus.VERIFIED, verified.getStatus());
        assertNull(verified.getLatitude());
        assertNull(verified.getLongitude());
        assertNull(verified.getRejectionReason());
        assertNoCoordinatesPersisted(atLimit.reviewId(), "VERIFIED", null, false);

        Created tooImprecise = review(user, createPlace(60), NEAR_LAT, NEAR_LON, 100.5);
        assertCheckIn(tooImprecise, "NOT_VERIFIED", "ACCURACY_TOO_LOW");
        CheckIn rejected = checkInRepository.findByReviewId(tooImprecise.reviewId()).orElseThrow();
        assertEquals(CheckInStatus.REJECTED, rejected.getStatus());
        assertEquals(PresenceVerificationOutcome.ACCURACY_TOO_LOW, rejected.getRejectionReason());
        assertNull(rejected.getVerifiedAt());
        assertNull(rejected.getLatitude());
        assertNull(rejected.getLongitude());
        assertNull(rejected.getDistanceToCentroidMeters());
        assertNoCoordinatesPersisted(tooImprecise.reviewId(), "REJECTED", "ACCURACY_TOO_LOW", true);

        Created withoutAccuracy = review(user, createPlace(60), NEAR_LAT, NEAR_LON, null);
        assertCheckIn(withoutAccuracy, "NOT_VERIFIED", "ACCURACY_UNAVAILABLE");
        CheckIn noAccuracy = checkInRepository.findByReviewId(withoutAccuracy.reviewId()).orElseThrow();
        assertEquals(CheckInStatus.REJECTED, noAccuracy.getStatus());
        assertEquals(PresenceVerificationOutcome.ACCURACY_UNAVAILABLE, noAccuracy.getRejectionReason());
        assertNoCoordinatesPersisted(withoutAccuracy.reviewId(), "REJECTED", "ACCURACY_UNAVAILABLE", true);

        // ~1,5 km do centróide
        Created far = review(user, createPlace(250), -25.4284, -49.2733, 5.0);
        assertCheckIn(far, "NOT_VERIFIED", "OUTSIDE_RADIUS");
        CheckIn outside = checkInRepository.findByReviewId(far.reviewId()).orElseThrow();
        assertEquals(CheckInStatus.REJECTED, outside.getStatus());
        assertEquals(PresenceVerificationOutcome.OUTSIDE_RADIUS, outside.getRejectionReason());
        assertNoCoordinatesPersisted(far.reviewId(), "REJECTED", "OUTSIDE_RADIUS", true);

        // Sem coordenadas: nada foi pedido (ex.: o app não conseguiu a localização) e não há check-in
        Created notRequested = review(user, createPlace(60), null, null, null);
        assertCheckIn(notRequested, "NOT_REQUESTED", null);
        assertTrue(checkInRepository.findByReviewId(notRequested.reviewId()).isEmpty());
    }

    @Test
    @DisplayName("Precisão negativa continua recusada pelo contrato (400) e não cria avaliação nem check-in")
    void negativeAccuracyIsRejected() throws Exception {
        TestUser user = registerUser("negacc");
        Place place = createPlace(60);
        CreateReviewRequest request = new CreateReviewRequest(place.getId(), null, false, "PUBLIC", NEAR_LAT, NEAR_LON,
                -1.0, List.of(new CreateReviewTargetRequest(target(), BigDecimal.valueOf(4.0), null)));
        mockMvc.perform(post("/api/v1/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM reviews WHERE user_id = ?", Integer.class,
                user.userId()));
    }

    // -----------------------------------------------------------------------------------------------------------
    // Frequência
    // -----------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Segundo check-in no mesmo local em 24 h publica a avaliação sem verificar e não infla os contadores; "
            + "outro local ou outro usuário não são afetados")
    void repeatedCheckInWithin24HoursIsNotVerified() throws Exception {
        TestUser user = registerUser("repeat");
        TestUser other = registerUser("repeatother");
        Place place = createPlace(60);

        assertCheckIn(review(user, place, NEAR_LAT, NEAR_LON, 10.0), "VERIFIED", null);
        Created repeated = review(user, place, NEAR_LAT, NEAR_LON, 10.0);
        assertCheckIn(repeated, "NOT_VERIFIED", "RECENT_CHECK_IN");
        CheckIn repeatedCheckIn = checkInRepository.findByReviewId(repeated.reviewId()).orElseThrow();
        assertEquals(CheckInStatus.REJECTED, repeatedCheckIn.getStatus());
        assertEquals(PresenceVerificationOutcome.RECENT_CHECK_IN, repeatedCheckIn.getRejectionReason());
        assertNoCoordinatesPersisted(repeated.reviewId(), "REJECTED", "RECENT_CHECK_IN", true);
        assertEquals(1, verifiedCount(user.userId(), place.getId()));

        mockMvc.perform(get("/api/v1/users/" + user.userId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.totalReviews").value(2))
                .andExpect(jsonPath("$.stats.verifiedReviewsCount").value(1));
        mockMvc.perform(get("/api/v1/users/" + user.userId() + "/reputation")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signals.activeReviews").value(2))
                .andExpect(jsonPath("$.signals.verifiedReviews").value(1));

        assertCheckIn(review(user, createPlace(60), NEAR_LAT, NEAR_LON, 10.0), "VERIFIED", null);
        assertCheckIn(review(other, place, NEAR_LAT, NEAR_LON, 10.0), "VERIFIED", null);
    }

    @Test
    @DisplayName("Depois de 24 h do último check-in verificado, um novo check-in no mesmo local é verificado")
    void checkInIsAllowedAgainAfterTheWindow() throws Exception {
        TestUser user = registerUser("window");
        Place place = createPlace(60);
        Created first = review(user, place, NEAR_LAT, NEAR_LON, 10.0);
        assertCheckIn(first, "VERIFIED", null);

        jdbcTemplate.update("UPDATE check_ins SET verified_at = now() - interval '24 hours 1 minute' WHERE review_id = ?",
                first.reviewId());
        assertCheckIn(review(user, place, NEAR_LAT, NEAR_LON, 10.0), "VERIFIED", null);

        jdbcTemplate.update("UPDATE check_ins SET verified_at = now() - interval '23 hours 59 minutes' "
                + "WHERE user_id = ? AND place_id = ? AND status = 'VERIFIED' AND review_id <> ?",
                user.userId(), place.getId(), first.reviewId());
        assertCheckIn(review(user, place, NEAR_LAT, NEAR_LON, 10.0), "NOT_VERIFIED", "RECENT_CHECK_IN");
        assertEquals(2, verifiedCount(user.userId(), place.getId()));
    }

    @Test
    @DisplayName("Requisições simultâneas do mesmo usuário e local esperam a mesma trava e geram um único check-in verificado")
    void concurrentCheckInsSerializeOnTheAdvisoryLock() throws Exception {
        TestUser user = registerUser("concurrent");
        Place place = createPlace(60);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // Uma transação segura a trava de presença deste usuário e local
            Future<?> holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                checkInRepository.lockPresenceVerification(user.userId(), place.getId());
                holding.countDown();
                awaitQuietly(release);
            }));
            assertTrue(holding.await(10, TimeUnit.SECONDS));

            List<Future<Created>> attempts = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                attempts.add(executor.submit(() -> review(user, place, NEAR_LAT, NEAR_LON, 10.0)));
            }
            // As duas criações chegam à trava e esperam (sem trava, as duas consultariam a janela vazia)
            waitForAdvisoryLockWaiters(2);

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            List<String> results = new ArrayList<>();
            for (Future<Created> attempt : attempts) {
                Created created = attempt.get(30, TimeUnit.SECONDS);
                results.add(created.body().get("checkIn").get("status").asText());
            }
            assertEquals(1, results.stream().filter("VERIFIED"::equals).count(), results.toString());
            assertEquals(1, results.stream().filter("NOT_VERIFIED"::equals).count(), results.toString());
            assertEquals(1, verifiedCount(user.userId(), place.getId()));
            assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM reviews WHERE user_id = ?",
                    Integer.class, user.userId()), "as duas avaliações foram publicadas");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Rajada de requisições simultâneas sem trava externa também termina com um único check-in verificado")
    void concurrentBurstProducesSingleVerifiedCheckIn() throws Exception {
        TestUser user = registerUser("burst");
        Place place = createPlace(60);
        int attempts = 6;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Created>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                futures.add(executor.submit(() -> {
                    awaitQuietly(start);
                    return review(user, place, NEAR_LAT, NEAR_LON, 10.0);
                }));
            }
            start.countDown();
            long verified = 0;
            for (Future<Created> future : futures) {
                if ("VERIFIED".equals(future.get(60, TimeUnit.SECONDS).body().get("checkIn").get("status").asText())) {
                    verified++;
                }
            }
            assertEquals(1, verified);
            assertEquals(1, verifiedCount(user.userId(), place.getId()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Coordenadas recebidas nunca vazam em logs nem em DTOs de resposta (VERIFIED, REJECTED ou histórico)")
    void coordinatesNeverLeakInLogsOrResponseDtos(CapturedOutput output) throws Exception {
        TestUser user = registerUser("noleak");
        Place nearPlace = createPlace(60);
        Place farPlace = createPlace(60);

        double verifiedLat = -25.438417;
        double verifiedLon = -49.284923;
        double rejectedLat = -25.412345;
        double rejectedLon = -49.265432;

        Created verifiedCreated = review(user, nearPlace, verifiedLat, verifiedLon, 8.5);
        Created rejectedCreated = review(user, farPlace, rejectedLat, rejectedLon, 12.0);

        assertCheckIn(verifiedCreated, "VERIFIED", null);
        assertCheckIn(rejectedCreated, "NOT_VERIFIED", "OUTSIDE_RADIUS");

        String historyBody = mockMvc.perform(get("/api/v1/me/check-ins")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String reviewDetailBody = mockMvc.perform(get("/api/v1/reviews/" + verifiedCreated.reviewId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String logs = output.getAll();
        for (String sensitive : List.of("-25.438417", "-49.284923", "-25.412345", "-49.265432")) {
            assertFalse(logs.contains(sensitive), "Coordenada não pode aparecer em logs: " + sensitive);
            assertFalse(verifiedCreated.body().toString().contains(sensitive));
            assertFalse(rejectedCreated.body().toString().contains(sensitive));
            assertFalse(historyBody.contains(sensitive));
            assertFalse(reviewDetailBody.contains(sensitive));
        }
    }

    private void waitForAdvisoryLockWaiters(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted", Integer.class);
            if (waiting != null && waiting >= expected) {
                return;
            }
            Thread.sleep(50);
        }
        fail("As criações não chegaram a esperar a trava de presença");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("tempo esgotado aguardando o latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
