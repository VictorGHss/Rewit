package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.ObjectStoragePort;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Review;
import com.rewit.presentation.dto.auth.LoginRequest;
import com.rewit.presentation.dto.auth.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mídia de reviews (C4) pela API real, com PostgreSQL e o storage do projeto (spy sobre o adaptador real, para simular
 * falhas): upload só pelo autor, leitura com a mesma regra do detalhe da review, cache por visibilidade, exclusão e
 * compensação de objetos quando a metadata não é confirmada.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Mídia de reviews (C4): upload, leitura, cache, exclusão e compensação")
class ReviewMediaLifecycleIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private ReviewRepository reviewRepository;
    @MockitoSpyBean private ObjectStoragePort objectStoragePort;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record TestUser(UUID id, String email, String password, String handle, String accessToken) {}

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        reset(objectStoragePort);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Upload
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Upload: só o autor; terceiro que vê a review 403; conta inativa 401; review inexistente ou removida 404")
    void uploadAuthorization() throws Exception {
        TestUser author = register("up_autor");
        TestUser follower = register("up_seguidor");
        TestUser inactive = register("up_inativo");
        follow(follower, author);
        UUID publicReview = review(author, "PUBLIC", ReviewStatus.ACTIVE);
        UUID followersReview = review(author, "FOLLOWERS", ReviewStatus.ACTIVE);

        upload(author, publicReview, jpeg()).andExpect(status().isCreated());
        for (UUID reviewId : List.of(publicReview, followersReview)) {
            upload(follower, reviewId, jpeg())
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        UUID inactiveReview = review(inactive, "PUBLIC", ReviewStatus.ACTIVE);
        perform(post("/api/v1/me/deactivate"), inactive).andExpect(status().isNoContent());
        upload(inactive, inactiveReview, jpeg()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        upload(author, UUID.randomUUID(), jpeg()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
        UUID removed = review(author, "PUBLIC", ReviewStatus.REMOVED);
        upload(author, removed, jpeg()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));

        assertEquals(1, count("SELECT count(*) FROM review_media WHERE review_id IN (?, ?)", publicReview, followersReview));
        assertEquals(0, count("SELECT count(*) FROM review_media WHERE review_id IN (?, ?)", inactiveReview, removed));
    }

    @Test
    @DisplayName("Upload: conteúdo decide o tipo, não a extensão; formato não suportado 415")
    void uploadContentType() throws Exception {
        TestUser author = register("up_tipo");
        UUID reviewId = review(author, "PUBLIC", ReviewStatus.ACTIVE);

        upload(author, reviewId, new MockMultipartFile("file", "foto.jpg", "image/jpeg", png()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mimeType").value("image/png"));
        upload(author, reviewId, new MockMultipartFile("file", "foto.jpg", "image/jpeg",
                "não é uma imagem".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("Limite de 5 mídias ativas: 4ª e 5ª aceitas, 6ª recusada 400 MAX_MEDIA_LIMIT_REACHED; após excluir, a vaga volta")
    void mediaCountLimit() throws Exception {
        TestUser author = register("up_limite");
        UUID reviewId = review(author, "PUBLIC", ReviewStatus.ACTIVE);
        for (int i = 0; i < 3; i++) {
            upload(author, reviewId, jpeg()).andExpect(status().isCreated());
        }

        upload(author, reviewId, jpeg()).andExpect(status().isCreated()); // limite - 1
        UUID fifth = id(upload(author, reviewId, jpeg()).andExpect(status().isCreated())); // limite
        upload(author, reviewId, jpeg()) // limite + 1
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MAX_MEDIA_LIMIT_REACHED"))
                .andExpect(jsonPath("$.type").exists());
        assertEquals(5, count("SELECT count(*) FROM review_media WHERE review_id = ? AND status = 'ACTIVE'", reviewId));

        perform(delete("/api/v1/reviews/{r}/media/{m}", reviewId, fifth), author).andExpect(status().isNoContent());
        upload(author, reviewId, jpeg()).andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Leitura e cache
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Leitura por visibilidade: PUBLIC cache público; FOLLOWERS e PRIVATE privados, terceiro sem acesso 403")
    void readByVisibility() throws Exception {
        TestUser author = register("le_autor");
        TestUser follower = register("le_seguidor");
        TestUser stranger = register("le_terceiro");
        follow(follower, author);

        UUID publicReview = review(author, "PUBLIC", ReviewStatus.ACTIVE);
        UUID publicMedia = id(upload(author, publicReview, jpeg()));
        download(stranger, publicReview, publicMedia).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=86400"));

        UUID followersReview = review(author, "FOLLOWERS", ReviewStatus.ACTIVE);
        UUID followersMedia = id(upload(author, followersReview, jpeg()));
        download(follower, followersReview, followersMedia).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        download(stranger, followersReview, followersMedia).andExpect(status().isForbidden());
        perform(get("/api/v1/reviews/{r}/media", followersReview), stranger).andExpect(status().isForbidden());

        UUID privateReview = review(author, "PRIVATE", ReviewStatus.ACTIVE);
        UUID privateMedia = id(upload(author, privateReview, jpeg()));
        download(author, privateReview, privateMedia).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        download(stranger, privateReview, privateMedia).andExpect(status().isForbidden());

        download(stranger, publicReview, UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));
        download(stranger, UUID.randomUUID(), publicMedia).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    @Test
    @DisplayName("Review REMOVED ou UNDER_REVIEW: terceiro 404 sem mídia; autor lê como no detalhe, sempre sem cache público")
    void inactiveReviewMedia() throws Exception {
        TestUser author = register("in_autor");
        TestUser stranger = register("in_terceiro");

        for (String state : List.of("REMOVED", "UNDER_REVIEW")) {
            UUID reviewId = review(author, "PUBLIC", ReviewStatus.ACTIVE);
            UUID mediaId = id(upload(author, reviewId, jpeg()));
            jdbcTemplate.update("UPDATE reviews SET status = ? WHERE id = ?", state, reviewId);

            download(stranger, reviewId, mediaId).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
            perform(get("/api/v1/reviews/{r}/media", reviewId), stranger).andExpect(status().isNotFound());

            perform(get("/api/v1/reviews/{id}", reviewId), author).andExpect(status().isOk());
            download(author, reviewId, mediaId).andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
            perform(get("/api/v1/reviews/{r}/media", reviewId), author).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(mediaId.toString()));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Exclusão
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Delete: próprio 204 e objeto removido; terceiro 403 sem efeito; mídia inexistente 404")
    void deleteMedia() throws Exception {
        TestUser author = register("del_autor");
        TestUser stranger = register("del_terceiro");
        UUID reviewId = review(author, "PUBLIC", ReviewStatus.ACTIVE);
        UUID mediaId = id(upload(author, reviewId, jpeg()));
        String objectKey = objectKey(mediaId);
        assertTrue(objectStoragePort.exists(objectKey));

        perform(delete("/api/v1/reviews/{r}/media/{m}", reviewId, mediaId), stranger)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertEquals("ACTIVE", mediaStatus(mediaId));
        assertTrue(objectStoragePort.exists(objectKey));

        perform(delete("/api/v1/reviews/{r}/media/{m}", reviewId, UUID.randomUUID()), author)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));

        perform(delete("/api/v1/reviews/{r}/media/{m}", reviewId, mediaId), author).andExpect(status().isNoContent());
        assertEquals("REMOVED", mediaStatus(mediaId));
        assertFalse(objectStoragePort.exists(objectKey));
        download(author, reviewId, mediaId).andExpect(status().isNotFound());
        // Idempotente
        perform(delete("/api/v1/reviews/{r}/media/{m}", reviewId, mediaId), author).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Delete com storage falhando: mídia removida logicamente e inacessível; o objeto fica (ADR-010)")
    void deleteWithStorageFailure() throws Exception {
        TestUser author = register("del_falha");
        UUID reviewId = review(author, "PUBLIC", ReviewStatus.ACTIVE);
        UUID mediaId = id(upload(author, reviewId, jpeg()));
        String objectKey = objectKey(mediaId);
        doThrow(new IllegalStateException("storage indisponível")).when(objectStoragePort).delete(objectKey);

        perform(delete("/api/v1/reviews/{r}/media/{m}", reviewId, mediaId), author).andExpect(status().isNoContent());

        assertEquals("REMOVED", mediaStatus(mediaId));
        download(author, reviewId, mediaId).andExpect(status().isNotFound());
        perform(get("/api/v1/reviews/{r}/media", reviewId), author).andExpect(jsonPath("$.length()").value(0));
        assertTrue(objectStoragePort.exists(objectKey), "a remoção física é de melhor esforço");
    }

    @Test
    @DisplayName("Delete cujo commit falha: a mídia continua ACTIVE e o objeto continua no storage (sem apontar para o vazio)")
    void deleteCommitFailureKeepsObject() throws Exception {
        TestUser author = register("del_commit");
        UUID reviewId = review(author, "PUBLIC", ReviewStatus.ACTIVE);
        UUID mediaId = id(upload(author, reviewId, jpeg()));
        String objectKey = objectKey(mediaId);

        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION test_c4_reject_media_update() RETURNS trigger AS $$
                BEGIN
                    IF NEW.id = '%s' THEN
                        RAISE EXCEPTION 'exclusão recusada pelo teste';
                    END IF;
                    RETURN NEW;
                END $$ LANGUAGE plpgsql""".formatted(mediaId));
        jdbcTemplate.execute("CREATE TRIGGER test_c4_reject_media_update BEFORE UPDATE ON review_media "
                + "FOR EACH ROW EXECUTE FUNCTION test_c4_reject_media_update()");
        try {
            MvcResult result = perform(delete("/api/v1/reviews/{r}/media/{m}", reviewId, mediaId), author).andReturn();
            assertNotEquals(204, result.getResponse().getStatus(), "a API não afirma uma exclusão que não foi confirmada");
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_c4_reject_media_update ON review_media");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_c4_reject_media_update()");
        }

        assertEquals("ACTIVE", mediaStatus(mediaId));
        assertTrue(objectStoragePort.exists(objectKey), "o objeto só é removido depois do commit");
        download(author, reviewId, mediaId).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Compensação do upload
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Metadata recusada no commit: o upload falha e o objeto enviado é removido do storage (sem órfão)")
    void uploadRollbackRemovesStoredObject() throws Exception {
        TestUser author = register("comp_autor");
        UUID reviewId = review(author, "PUBLIC", ReviewStatus.ACTIVE);

        // O INSERT de review_media só é executado no flush do commit, depois do upload no storage
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION test_c4_reject_media() RETURNS trigger AS $$
                BEGIN
                    IF NEW.review_id = '%s' THEN
                        RAISE EXCEPTION 'metadata recusada pelo teste';
                    END IF;
                    RETURN NEW;
                END $$ LANGUAGE plpgsql""".formatted(reviewId));
        jdbcTemplate.execute("CREATE TRIGGER test_c4_reject_media BEFORE INSERT ON review_media "
                + "FOR EACH ROW EXECUTE FUNCTION test_c4_reject_media()");
        try {
            MvcResult result = upload(author, reviewId, jpeg()).andReturn();
            assertNotEquals(201, result.getResponse().getStatus());
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_c4_reject_media ON review_media");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_c4_reject_media()");
        }

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(objectStoragePort, atLeastOnce()).put(key.capture(), any(), any());
        String uploadedKey = key.getValue();
        assertTrue(uploadedKey.startsWith("reviews/" + reviewId + "/"));
        verify(objectStoragePort).delete(eq(uploadedKey));
        assertFalse(objectStoragePort.exists(uploadedKey), "o objeto sem metadata foi removido");
        assertEquals(0, count("SELECT count(*) FROM review_media WHERE review_id = ?", reviewId));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Privacidade
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Respostas de mídia sem e-mail, handle, UUID do autor nem chave de storage, inclusive de conta DELETED")
    void mediaResponsesHaveNoIdentity() throws Exception {
        TestUser author = register("priv_autor");
        TestUser viewer = register("priv_leitor");
        UUID reviewId = review(author, "PUBLIC", ReviewStatus.ACTIVE);
        String uploadBody = body(upload(author, reviewId, jpeg()).andExpect(status().isCreated()));
        perform(delete("/api/v1/admin/users/{id}", author.id()), admin()).andExpect(status().isNoContent());

        String listBody = body(perform(get("/api/v1/reviews/{r}/media", reviewId), viewer).andExpect(status().isOk()));
        for (String response : List.of(uploadBody, listBody)) {
            assertFalse(response.contains(author.id().toString()), response);
            assertFalse(response.contains(author.email()), response);
            assertFalse(response.contains(author.handle()), response);
            assertFalse(response.contains("objectKey") || response.contains("/image."), "sem chave de storage: " + response);
            assertFalse(response.contains("userId"), response);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private UUID review(TestUser author, String visibility, ReviewStatus status) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = placeRepository.save(new Place(null, "Local Mídia " + suffix, "local-midia-" + suffix, "RESTAURANTE",
                "Descrição", "Rua Mídia, 1", "1", "Centro", "Curitiba", "PR", "BR", -25.43, -49.27, 50, "USER", false,
                null, "ACTIVE"));
        return reviewRepository.save(new Review(null, author.id(), place.getId(), "Avaliação com mídia", false, false,
                status, visibility, null, null, null, Instant.now(), Instant.now())).getId();
    }

    private ResultActions upload(TestUser user, UUID reviewId, byte[] bytes) throws Exception {
        return upload(user, reviewId, new MockMultipartFile("file", "foto.jpg", "image/jpeg", bytes));
    }

    private ResultActions upload(TestUser user, UUID reviewId, MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart("/api/v1/reviews/{r}/media", reviewId).file(file)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()));
    }

    private ResultActions download(TestUser user, UUID reviewId, UUID mediaId) throws Exception {
        return perform(get("/api/v1/reviews/{r}/media/{m}", reviewId, mediaId), user);
    }

    private void follow(TestUser follower, TestUser target) throws Exception {
        perform(post("/api/v1/users/{id}/follow", target.id()), follower).andExpect(status().isOk());
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, TestUser user) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()));
    }

    private UUID id(ResultActions actions) throws Exception {
        return UUID.fromString(objectMapper.readTree(body(actions)).get("id").asText());
    }

    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String objectKey(UUID mediaId) {
        return jdbcTemplate.queryForObject("SELECT object_key FROM review_media WHERE id = ?", String.class, mediaId);
    }

    private String mediaStatus(UUID mediaId) {
        return jdbcTemplate.queryForObject("SELECT status FROM review_media WHERE id = ?", String.class, mediaId);
    }

    private static byte[] jpeg() throws Exception {
        return image("jpg");
    }

    private static byte[] png() throws Exception {
        return image("png");
    }

    private static byte[] image(String format) throws Exception {
        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private TestUser register(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "." + suffix + "@rewit.test";
        String password = "Senha@" + suffix;
        String handle = (prefix + "_" + suffix).toLowerCase();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, password, handle, "Nome " + suffix))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return new TestUser(UUID.fromString(node.get("user").get("id").asText()), email, password, handle,
                node.get("accessToken").asText());
    }

    private TestUser admin() throws Exception {
        TestUser user = register("midia_admin");
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.id());
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isOk())
                .andReturn();
        return new TestUser(user.id(), user.email(), user.password(), user.handle(),
                objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("accessToken").asText());
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
