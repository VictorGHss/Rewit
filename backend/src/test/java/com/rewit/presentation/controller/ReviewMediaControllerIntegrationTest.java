package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateLimiter;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Review;
import com.rewit.presentation.dto.auth.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração MockMvc / HTTP: Mídias de Reviews (Step 21.0 - Requisito 27)")
class ReviewMediaControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateLimiter rateLimiter;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private byte[] validJpegBytes;
    private byte[] validPngBytes;

    @BeforeEach
    void setUp() throws IOException {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        BufferedImage img = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        validJpegBytes = baos.toByteArray();

        BufferedImage imgPng = new BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream baosPng = new ByteArrayOutputStream();
        ImageIO.write(imgPng, "png", baosPng);
        validPngBytes = baosPng.toByteArray();
    }

    private record TestUser(String token, UUID userId, String handle) {}

    private TestUser createAuthenticatedUser(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = (prefix + "." + suffix + "@rewit.test").toLowerCase(java.util.Locale.ROOT);
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
        return new TestUser(token, userId, handle);
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
                null,
                "Lugar Media " + suffix,
                "lugar-media-" + suffix,
                "RESTAURANTE",
                "Descrição",
                "Rua Teste, 100",
                "100",
                "Centro",
                "São Paulo",
                "SP",
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

    private Review createReview(UUID authorId, Place place, String visibility, ReviewStatus status, boolean isAnonymous) {
        Review review = new Review(
                null,
                authorId,
                place.getId(),
                "Avaliação para testes HTTP de mídia",
                isAnonymous,
                false,
                status,
                visibility,
                null,
                null,
                null,
                Instant.now(),
                Instant.now()
        );
        return reviewRepository.save(review);
    }

    // 1. 401 Unauthorized
    @Test
    @DisplayName("1. Deve retornar 401 Unauthorized para requisições sem token JWT")
    void shouldReturn401WhenUnauthenticated() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", validJpegBytes);

        mockMvc.perform(multipart("/api/v1/reviews/{reviewId}/media", reviewId).file(file))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/reviews/{reviewId}/media", reviewId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/reviews/{reviewId}/media/{mediaId}", reviewId, mediaId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/v1/reviews/{reviewId}/media/{mediaId}", reviewId, mediaId))
                .andExpect(status().isUnauthorized());
    }

    // 2. 201 Created (JPEG)
    @Test
    @DisplayName("2. Deve realizar upload de JPEG válido retornando 201 Created e DTO público seguro")
    void shouldUploadJpegSuccessfullyWith201() throws Exception {
        TestUser user = createAuthenticatedUser("med_auth1");
        Place place = createPlace();
        Review review = createReview(user.userId(), place, "PUBLIC", ReviewStatus.ACTIVE, false);

        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", validJpegBytes);

        mockMvc.perform(multipart("/api/v1/reviews/{reviewId}/media", review.getId())
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.reviewId").value(review.getId().toString()))
                .andExpect(jsonPath("$.url").value(containsString("/api/v1/reviews/" + review.getId() + "/media/")))
                .andExpect(jsonPath("$.mediaType").value("IMAGE"))
                .andExpect(jsonPath("$.mimeType").value("image/jpeg"))
                .andExpect(jsonPath("$.width").value(80))
                .andExpect(jsonPath("$.height").value(80))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                // Invariante de privacidade: objectKey e userId não podem ser expostos
                .andExpect(jsonPath("$.objectKey").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist());
    }

    // 3. 201 Created (PNG)
    @Test
    @DisplayName("3. Deve realizar upload de PNG válido retornando 201 Created")
    void shouldUploadPngSuccessfullyWith201() throws Exception {
        TestUser user = createAuthenticatedUser("med_png");
        Place place = createPlace();
        Review review = createReview(user.userId(), place, "PUBLIC", ReviewStatus.ACTIVE, false);

        MockMultipartFile file = new MockMultipartFile("file", "image.png", "image/png", validPngBytes);

        mockMvc.perform(multipart("/api/v1/reviews/{reviewId}/media", review.getId())
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mimeType").value("image/png"));
    }

    // 4. 200 OK (Listagem)
    @Test
    @DisplayName("4. Deve listar mídias ativas da avaliação ordenadas com 200 OK")
    void shouldReturn200WhenListingMedia() throws Exception {
        TestUser user = createAuthenticatedUser("med_list");
        Place place = createPlace();
        Review review = createReview(user.userId(), place, "PUBLIC", ReviewStatus.ACTIVE, false);

        MockMultipartFile file1 = new MockMultipartFile("file", "1.jpg", "image/jpeg", validJpegBytes);
        MockMultipartFile file2 = new MockMultipartFile("file", "2.jpg", "image/jpeg", validJpegBytes);

        mockMvc.perform(multipart("/api/v1/reviews/{reviewId}/media", review.getId()).file(file1)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())).andExpect(status().isCreated());
        mockMvc.perform(multipart("/api/v1/reviews/{reviewId}/media", review.getId()).file(file2)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/reviews/{reviewId}/media", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].mediaType").value("IMAGE"))
                .andExpect(jsonPath("$[1].mediaType").value("IMAGE"));
    }

    // 5. 200 OK (Download de bytes)
    @Test
    @DisplayName("5. Deve retornar bytes da imagem sanitizada com Content-Type correto em 200 OK")
    void shouldReturn200AndBytesWhenDownloadingMedia() throws Exception {
        TestUser user = createAuthenticatedUser("med_dl");
        Place place = createPlace();
        Review review = createReview(user.userId(), place, "PUBLIC", ReviewStatus.ACTIVE, false);

        MockMultipartFile file = new MockMultipartFile("file", "pic.jpg", "image/jpeg", validJpegBytes);
        MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/reviews/{reviewId}/media", review.getId())
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(uploadResult.getResponse().getContentAsString());
        UUID mediaId = UUID.fromString(json.get("id").asText());

        byte[] downloaded = mockMvc.perform(get("/api/v1/reviews/{reviewId}/media/{mediaId}", review.getId(), mediaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andReturn().getResponse().getContentAsByteArray();

        assertTrue(downloaded.length > 0);
    }

    // 6. 204 No Content (Exclusão pelo autor)
    @Test
    @DisplayName("6. Deve excluir mídia pelo autor retornando 204 No Content")
    void shouldReturn204WhenDeletingMediaByAuthor() throws Exception {
        TestUser user = createAuthenticatedUser("med_del");
        Place place = createPlace();
        Review review = createReview(user.userId(), place, "PUBLIC", ReviewStatus.ACTIVE, false);

        MockMultipartFile file = new MockMultipartFile("file", "pic.jpg", "image/jpeg", validJpegBytes);
        MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/reviews/{reviewId}/media", review.getId())
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(uploadResult.getResponse().getContentAsString());
        UUID mediaId = UUID.fromString(json.get("id").asText());

        mockMvc.perform(delete("/api/v1/reviews/{reviewId}/media/{mediaId}", review.getId(), mediaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isNoContent());

        // Confirma que download subsequente retorna 404
        mockMvc.perform(get("/api/v1/reviews/{reviewId}/media/{mediaId}", review.getId(), mediaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isNotFound());
    }

    // 7. 400 Bad Request (Arquivo vazio)
    @Test
    @DisplayName("7. Deve retornar 400 Bad Request ao tentar enviar arquivo vazio")
    void shouldReturn400WhenFileIsEmpty() throws Exception {
        TestUser user = createAuthenticatedUser("med_empty");
        Place place = createPlace();
        Review review = createReview(user.userId(), place, "PUBLIC", ReviewStatus.ACTIVE, false);

        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);

        mockMvc.perform(multipart("/api/v1/reviews/{reviewId}/media", review.getId())
                        .file(emptyFile)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPTY_MEDIA_FILE"));
    }

    // 8. 403 Forbidden (Exclusão por terceiro)
    @Test
    @DisplayName("8. Deve retornar 403 Forbidden ao tentar excluir mídia de outra pessoa")
    void shouldReturn403WhenDeletingByThirdParty() throws Exception {
        TestUser author = createAuthenticatedUser("med_a");
        TestUser thirdParty = createAuthenticatedUser("med_b");
        Place place = createPlace();
        Review review = createReview(author.userId(), place, "PUBLIC", ReviewStatus.ACTIVE, false);

        MockMultipartFile file = new MockMultipartFile("file", "pic.jpg", "image/jpeg", validJpegBytes);
        MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/reviews/{reviewId}/media", review.getId())
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.token()))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(uploadResult.getResponse().getContentAsString());
        UUID mediaId = UUID.fromString(json.get("id").asText());

        mockMvc.perform(delete("/api/v1/reviews/{reviewId}/media/{mediaId}", review.getId(), mediaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + thirdParty.token()))
                .andExpect(status().isForbidden());
    }

    // 9. 403 Forbidden (Review PRIVATE acessada por terceiro)
    @Test
    @DisplayName("9. Deve retornar 403 Forbidden ao acessar mídia de avaliação privada de outro usuário")
    void shouldReturn403WhenAccessingPrivateReviewByThirdParty() throws Exception {
        TestUser author = createAuthenticatedUser("med_priv_a");
        TestUser thirdParty = createAuthenticatedUser("med_priv_b");
        Place place = createPlace();
        Review review = createReview(author.userId(), place, "PRIVATE", ReviewStatus.ACTIVE, false);

        MockMultipartFile file = new MockMultipartFile("file", "pic.jpg", "image/jpeg", validJpegBytes);

        mockMvc.perform(multipart("/api/v1/reviews/{reviewId}/media", review.getId())
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + thirdParty.token()))
                .andExpect(status().isForbidden());
    }

    // 10. 404 Not Found (Review Inexistente)
    @Test
    @DisplayName("10. Deve retornar 404 Not Found para Review inexistente")
    void shouldReturn404WhenReviewNotFound() throws Exception {
        TestUser user = createAuthenticatedUser("med_404");
        UUID randomReviewId = UUID.randomUUID();

        MockMultipartFile file = new MockMultipartFile("file", "pic.jpg", "image/jpeg", validJpegBytes);

        mockMvc.perform(multipart("/api/v1/reviews/{reviewId}/media", randomReviewId)
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    // 11. 404 Not Found (Review UNDER_REVIEW)
    @Test
    @DisplayName("11. Deve retornar 404 Not Found para Review com moderação preventiva UNDER_REVIEW")
    void shouldReturn404WhenReviewUnderReview() throws Exception {
        TestUser user = createAuthenticatedUser("med_ur");
        Place place = createPlace();
        Review review = createReview(user.userId(), place, "PUBLIC", ReviewStatus.UNDER_REVIEW, false);

        MockMultipartFile file = new MockMultipartFile("file", "pic.jpg", "image/jpeg", validJpegBytes);

        mockMvc.perform(multipart("/api/v1/reviews/{reviewId}/media", review.getId())
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isNotFound());
    }

    // 12. IDOR Prevention (Review A + Media da Review B)
    @Test
    @DisplayName("12. Deve prevenir IDOR retornando 404 quando mediaId não pertencer à reviewId do path")
    void shouldReturn404OnIdorAttempt() throws Exception {
        TestUser user = createAuthenticatedUser("med_idor");
        Place place = createPlace();
        Review reviewA = createReview(user.userId(), place, "PUBLIC", ReviewStatus.ACTIVE, false);
        Review reviewB = createReview(user.userId(), place, "PUBLIC", ReviewStatus.ACTIVE, false);

        MockMultipartFile file = new MockMultipartFile("file", "pic.jpg", "image/jpeg", validJpegBytes);
        MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/reviews/{reviewId}/media", reviewB.getId())
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(uploadResult.getResponse().getContentAsString());
        UUID mediaIdB = UUID.fromString(json.get("id").asText());

        // Requisição para Review A usando media da Review B -> deve retornar 404
        mockMvc.perform(get("/api/v1/reviews/{reviewId}/media/{mediaId}", reviewA.getId(), mediaIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));
    }

    // 13. 413 Payload Too Large (> 10 MB)
    @Test
    @DisplayName("13. Deve retornar 413 Payload Too Large para arquivo superior a 10 MB")
    void shouldReturn413WhenFileExceeds10MB() throws Exception {
        TestUser user = createAuthenticatedUser("med_large");
        Place place = createPlace();
        Review review = createReview(user.userId(), place, "PUBLIC", ReviewStatus.ACTIVE, false);

        byte[] largeBytes = new byte[(10 * 1024 * 1024) + 1];
        MockMultipartFile file = new MockMultipartFile("file", "large.jpg", "image/jpeg", largeBytes);

        mockMvc.perform(multipart("/api/v1/reviews/{reviewId}/media", review.getId())
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("MEDIA_SIZE_EXCEEDED"));
    }

    // 14. 415 Unsupported Media Type (Não-imagem / SVG / HTML)
    @Test
    @DisplayName("14. Deve retornar 415 Unsupported Media Type para arquivos que não são imagens JPEG/PNG")
    void shouldReturn415WhenMediaTypeUnsupported() throws Exception {
        TestUser user = createAuthenticatedUser("med_unsupp");
        Place place = createPlace();
        Review review = createReview(user.userId(), place, "PUBLIC", ReviewStatus.ACTIVE, false);

        byte[] fakeSvg = "<svg>malicious</svg>".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "image.svg", "image/svg+xml", fakeSvg);

        mockMvc.perform(multipart("/api/v1/reviews/{reviewId}/media", review.getId())
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    // 15. 429 Too Many Requests (Rate Limiting)
    @Test
    @DisplayName("15. Deve retornar 429 Too Many Requests quando usuário exceder o limite de uploads")
    void shouldReturn429WhenRateLimitExceeded() throws Exception {
        TestUser user = createAuthenticatedUser("med_rate");
        Place place = createPlace();
        Review review = createReview(user.userId(), place, "PUBLIC", ReviewStatus.ACTIVE, false);

        MockMultipartFile file = new MockMultipartFile("file", "pic.jpg", "image/jpeg", validJpegBytes);

        // Dispara 10 uploads (limite por janela de 60s)
        for (int i = 0; i < 10; i++) {
            rateLimiter.acquireOrThrow(RateLimitedAction.MEDIA_UPLOAD, RateLimitSubject.ofUser(user.userId()));
        }

        // 11º upload via endpoint deve resultar em 429
        mockMvc.perform(multipart("/api/v1/reviews/{reviewId}/media", review.getId())
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("^[1-9]\\d*$")))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    // 16. Anonimato de Review
    @Test
    @DisplayName("16. Review anônima: upload e listagem de mídia não devem expor a identidade do autor")
    void shouldPreservePrivacyOnAnonymousReview() throws Exception {
        TestUser author = createAuthenticatedUser("med_anon");
        Place place = createPlace();
        Review review = createReview(author.userId(), place, "PUBLIC", ReviewStatus.ACTIVE, true);

        MockMultipartFile file = new MockMultipartFile("file", "anon.jpg", "image/jpeg", validJpegBytes);

        MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/reviews/{reviewId}/media", review.getId())
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.token()))
                .andExpect(status().isCreated())
                .andReturn();

        String responseBody = uploadResult.getResponse().getContentAsString();
        // Não expõe userId nem handle do autor
        assertFalse(responseBody.contains(author.userId().toString()));
        assertFalse(responseBody.contains(author.handle()));

        // Listagem pública
        TestUser viewer = createAuthenticatedUser("med_viewer");
        MvcResult listResult = mockMvc.perform(get("/api/v1/reviews/{reviewId}/media", review.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer.token()))
                .andExpect(status().isOk())
                .andReturn();

        String listResponseBody = listResult.getResponse().getContentAsString();
        assertFalse(listResponseBody.contains(author.userId().toString()));
        assertFalse(listResponseBody.contains(author.handle()));
    }

    // 17-19. Cache-Control do download conforme a visibilidade da avaliação
    @Test
    @DisplayName("17. Mídia de avaliação PUBLIC continua publicamente cacheável")
    void publicReviewMediaIsPubliclyCacheable() throws Exception {
        TestUser author = createAuthenticatedUser("med_cache_pub");
        TestUser viewer = createAuthenticatedUser("med_cache_pub_v");
        Review review = createReview(author.userId(), createPlace(), "PUBLIC", ReviewStatus.ACTIVE, false);
        UUID mediaId = uploadJpeg(review, author);

        mockMvc.perform(get("/api/v1/reviews/{reviewId}/media/{mediaId}", review.getId(), mediaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer.token()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=86400"));
    }

    @Test
    @DisplayName("18. Mídia de avaliação PRIVATE nunca é publicamente cacheável (nem para o autor)")
    void privateReviewMediaIsNotPubliclyCacheable() throws Exception {
        TestUser author = createAuthenticatedUser("med_cache_priv");
        Review review = createReview(author.userId(), createPlace(), "PRIVATE", ReviewStatus.ACTIVE, false);
        UUID mediaId = uploadJpeg(review, author);

        mockMvc.perform(get("/api/v1/reviews/{reviewId}/media/{mediaId}", review.getId(), mediaId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.token()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
    }

    @Test
    @DisplayName("19. Mídia de avaliação FOLLOWERS nunca é publicamente cacheável (seguidor e autor)")
    void followersReviewMediaIsNotPubliclyCacheable() throws Exception {
        TestUser author = createAuthenticatedUser("med_cache_fol");
        TestUser follower = createAuthenticatedUser("med_cache_fol_f");
        Review review = createReview(author.userId(), createPlace(), "FOLLOWERS", ReviewStatus.ACTIVE, false);
        UUID mediaId = uploadJpeg(review, author);

        mockMvc.perform(post("/api/v1/users/{id}/follow", author.userId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + follower.token()))
                .andExpect(status().isOk());

        for (TestUser reader : new TestUser[]{follower, author}) {
            mockMvc.perform(get("/api/v1/reviews/{reviewId}/media/{mediaId}", review.getId(), mediaId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + reader.token()))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        }
    }

    private UUID uploadJpeg(Review review, TestUser author) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "cache.jpg", "image/jpeg", validJpegBytes);
        MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/reviews/{reviewId}/media", review.getId())
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author.token()))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(uploadResult.getResponse().getContentAsString()).get("id").asText());
    }
}
