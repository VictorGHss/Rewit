package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceMediaRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.service.PlaceMediaService;
import com.rewit.config.PrivilegedTestDatabase;
import com.rewit.domain.enums.PlaceMediaStatus;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceMedia;
import com.rewit.presentation.dto.auth.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Troca da foto principal ({@code PUT /api/v1/places/{id}/media/{mediaId}/primary}) contra o PostgreSQL real: a capa
 * antiga deixa de ser principal antes de a nova ser gravada, sob o índice único parcial uq_place_media_primary_active
 * (V31), que não é adiável. Mocks não exercitam esse índice nem a ordem de flush do Hibernate.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Integração: troca da foto principal do local")
class PlaceMediaPrimaryIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private PlaceMediaRepository placeMediaRepository;

    @Autowired
    private PlaceMediaService placeMediaService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record TestUser(UUID id, String token) {}

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("Trocar a capa entre duas fotos ativas deixa só a nova como principal ativa")
    void swapsPrimaryBetweenTwoActivePhotos() throws Exception {
        TestUser creator = registerUser("capa");
        UUID placeId = createPlace(creator);
        UUID first = createActiveMedia(placeId, creator, true, 0);
        UUID second = createActiveMedia(placeId, creator, false, 1);
        assertEquals(List.of(first), activePrimaryIds(placeId));

        mockMvc.perform(withToken(put("/api/v1/places/{placeId}/media/{mediaId}/primary", placeId, second), creator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(second.toString()))
                .andExpect(jsonPath("$.isPrimary").value(true));

        assertEquals(List.of(second), activePrimaryIds(placeId));
        assertEquals(2, activeCount(placeId), "A troca não remove nem oculta fotos");

        JsonNode listing = read(mockMvc.perform(withToken(get("/api/v1/places/{placeId}/media", placeId), creator))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(2, listing.size());
        assertEquals(first.toString(), listing.get(0).get("id").asText());
        assertEquals(false, listing.get(0).get("isPrimary").asBoolean());
        assertEquals(second.toString(), listing.get(1).get("id").asText());
        assertEquals(true, listing.get(1).get("isPrimary").asBoolean());
    }

    @Test
    @DisplayName("Trocar a capa de volta para a primeira foto mantém uma única principal")
    void swapsPrimaryBackAndForth() throws Exception {
        TestUser creator = registerUser("capaida");
        UUID placeId = createPlace(creator);
        UUID first = createActiveMedia(placeId, creator, true, 0);
        UUID second = createActiveMedia(placeId, creator, false, 1);

        mockMvc.perform(withToken(put("/api/v1/places/{placeId}/media/{mediaId}/primary", placeId, second), creator))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(put("/api/v1/places/{placeId}/media/{mediaId}/primary", placeId, first), creator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPrimary").value(true));

        assertEquals(List.of(first), activePrimaryIds(placeId));
    }

    @Test
    @DisplayName("Escolher de novo a foto que já é principal responde 200 sem gravar nada")
    void repeatingCurrentPrimaryIsIdempotent() throws Exception {
        TestUser creator = registerUser("capamesma");
        UUID placeId = createPlace(creator);
        UUID first = createActiveMedia(placeId, creator, true, 0);
        UUID second = createActiveMedia(placeId, creator, false, 1);
        Timestamp firstUpdatedAt = updatedAt(first);
        Timestamp secondUpdatedAt = updatedAt(second);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(withToken(put("/api/v1/places/{placeId}/media/{mediaId}/primary", placeId, first), creator))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(first.toString()))
                    .andExpect(jsonPath("$.isPrimary").value(true));
        }

        assertEquals(List.of(first), activePrimaryIds(placeId));
        assertEquals(firstUpdatedAt, updatedAt(first));
        assertEquals(secondUpdatedAt, updatedAt(second));
    }

    @Test
    @DisplayName("Falha depois de desmarcar a capa antiga desfaz a troca inteira")
    void failureAfterUnsettingOldPrimaryRollsBackTheSwap() throws Exception {
        TestUser creator = registerUser("caparollback");
        UUID placeId = createPlace(creator);
        UUID first = createActiveMedia(placeId, creator, true, 0);
        UUID second = createActiveMedia(placeId, creator, false, 1);

        // Falha só na gravação da nova capa, depois de a antiga já ter sido desmarcada
        JdbcTemplate privileged = PrivilegedTestDatabase.jdbc();
        privileged.execute("""
                CREATE OR REPLACE FUNCTION fn_test_fail_primary_swap() RETURNS TRIGGER AS $$
                BEGIN
                    RAISE EXCEPTION 'Falha forçada na troca de capa para teste de rollback'
                        USING ERRCODE = 'integrity_constraint_violation';
                END;
                $$ LANGUAGE plpgsql;
                """);
        privileged.execute("""
                CREATE TRIGGER trg_test_fail_primary_swap
                    BEFORE UPDATE ON place_media
                    FOR EACH ROW
                    WHEN (NEW.id = '%s'::uuid AND NEW.is_primary)
                    EXECUTE FUNCTION fn_test_fail_primary_swap();
                """.formatted(second));
        try {
            DataAccessException thrown = assertThrows(DataAccessException.class,
                    () -> placeMediaService.setPrimaryMedia(creator.id(), placeId, second));
            String cause = String.valueOf(thrown.getMostSpecificCause().getMessage());
            assertTrue(cause.contains("Falha forçada na troca de capa para teste de rollback"),
                    "A falha deve vir do trigger de teste, não do índice de capa única: " + cause);

            assertEquals(List.of(first), activePrimaryIds(placeId), "A capa antiga continua a única principal");
        } finally {
            privileged.execute("DROP TRIGGER IF EXISTS trg_test_fail_primary_swap ON place_media");
            privileged.execute("DROP FUNCTION IF EXISTS fn_test_fail_primary_swap()");
        }

        // Sem o trigger, a mesma troca conclui normalmente
        mockMvc.perform(withToken(put("/api/v1/places/{placeId}/media/{mediaId}/primary", placeId, second), creator))
                .andExpect(status().isOk());
        assertEquals(List.of(second), activePrimaryIds(placeId));
    }

    private List<UUID> activePrimaryIds(UUID placeId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM place_media WHERE place_id = ? AND is_primary AND status = 'ACTIVE' ORDER BY id",
                UUID.class, placeId);
    }

    private int activeCount(UUID placeId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_media WHERE place_id = ? AND status = 'ACTIVE'", Integer.class, placeId);
        return count == null ? 0 : count;
    }

    private Timestamp updatedAt(UUID mediaId) {
        return jdbcTemplate.queryForObject("SELECT updated_at FROM place_media WHERE id = ?", Timestamp.class, mediaId);
    }

    private UUID createActiveMedia(UUID placeId, TestUser author, boolean primary, int order) {
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60L - order);
        UUID mediaId = UUID.randomUUID();
        placeMediaRepository.save(new PlaceMedia(
                mediaId, placeId, author.id(), "places/" + placeId + "/" + mediaId + ".jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 1024L, 800, 600,
                PlaceMediaStatus.ACTIVE, primary, null, null, createdAt, createdAt
        ));
        return mediaId;
    }

    private UUID createPlace(TestUser creator) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place saved = placeRepository.save(new Place(
                null, "Local Capa " + suffix, "capa-" + UUID.randomUUID(),
                "RESTAURANTE", "Descrição", "Rua da Capa, " + suffix, "100", "Centro",
                "Curitiba", "PR", "BR", -25.43, -49.27, 50, "USER", false, null, creator.id(), "ACTIVE"
        ));
        return saved.getId();
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, TestUser user) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private TestUser registerUser(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(
                                prefix + "." + suffix + "@rewit.test", "Senha@" + suffix,
                                prefix + "_" + suffix, "Nome " + suffix))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = read(result);
        return new TestUser(UUID.fromString(node.get("user").get("id").asText()), node.get("accessToken").asText());
    }
}
