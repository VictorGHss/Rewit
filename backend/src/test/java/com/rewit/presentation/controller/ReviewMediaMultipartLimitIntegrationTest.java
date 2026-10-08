package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewMedia;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Upload de mídia pelo servidor HTTP real (C4). O MockMvc não passa pelo resolver de multipart, então só este teste
 * enxerga o limite configurado em {@code spring.servlet.multipart}: o padrão do Spring (1 MB) recusava fotos comuns
 * antes de chegar ao controller.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "management.health.redis.enabled=false"
        }
)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Upload de mídia pelo servidor real: limites de multipart e de negócio (C4)")
class ReviewMediaMultipartLimitIntegrationTest {

    private static final long LIMIT = ReviewMedia.MAX_FILE_SIZE_BYTES;

    @Autowired private PlaceRepository placeRepository;
    @Autowired private ReviewRepository reviewRepository;

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String token;
    private UUID userId;

    @BeforeAll
    void registerUser() throws Exception {
        String handle = "mp" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"email":"%s@rewit.test","password":"Password-123!","handle":"%s","displayName":"Uploader"}
                """.formatted(handle, handle);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url("/api/v1/auth/register")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        JsonNode node = objectMapper.readTree(response.body());
        token = node.get("accessToken").asText();
        userId = UUID.fromString(node.get("user").get("id").asText());
    }

    @Test
    @DisplayName("Foto comum de alguns MB é aceita (o padrão de 1 MB do multipart a recusava)")
    void typicalPhotoIsAccepted() throws Exception {
        byte[] photo = randomPng(900, 900);
        assertTrue(photo.length > 2 * 1024 * 1024, "o arquivo precisa passar de 1 MB para provar o limite: " + photo.length);

        HttpResponse<String> response = upload(createReview(), photo, "foto.png", "image/png");

        assertEquals(201, response.statusCode(), response.body());
        assertEquals("image/png", objectMapper.readTree(response.body()).get("mimeType").asText());
    }

    @Test
    @DisplayName("Limite de 10 MB pelo HTTP real: limite - 1 e limite aceitos, limite + 1 recusado com 413 MEDIA_SIZE_EXCEEDED")
    void exactSizeLimit() throws Exception {
        assertEquals(201, upload(createReview(), paddedJpeg(LIMIT - 1), "a.jpg", "image/jpeg").statusCode());
        assertEquals(201, upload(createReview(), paddedJpeg(LIMIT), "b.jpg", "image/jpeg").statusCode());

        HttpResponse<String> over = upload(createReview(), paddedJpeg(LIMIT + 1), "c.jpg", "image/jpeg");
        assertEquals(413, over.statusCode(), over.body());
        assertEquals("MEDIA_SIZE_EXCEEDED", objectMapper.readTree(over.body()).get("code").asText());
    }

    // ---------------------------------------------------------------------------------------------------------

    private UUID createReview() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = placeRepository.save(new Place(null, "Local Upload " + suffix, "local-upload-" + suffix, "RESTAURANTE",
                "Descrição", "Rua Upload, 1", "1", "Centro", "Curitiba", "PR", "BR", -25.43, -49.27, 50, "USER", false,
                null, "ACTIVE"));
        Review review = reviewRepository.save(new Review(null, userId, place.getId(), "Avaliação com mídia", false, false,
                ReviewStatus.ACTIVE, "PUBLIC", null, null, null, Instant.now(), Instant.now()));
        return review.getId();
    }

    private HttpResponse<String> upload(UUID reviewId, byte[] bytes, String filename, String contentType) throws Exception {
        String boundary = "rewit-" + UUID.randomUUID();
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + bytes.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(bytes, 0, body, head.length, bytes.length);
        System.arraycopy(tail, 0, body, head.length + bytes.length, tail.length);

        return http.send(HttpRequest.newBuilder(URI.create(url("/api/v1/reviews/" + reviewId + "/media")))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** PNG de pixels aleatórios: não comprime, então o tamanho cresce com as dimensões. */
    private static byte[] randomPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** JPEG válido completado com zeros após o marcador de fim até o tamanho exato pedido. */
    static byte[] paddedJpeg(long size) throws Exception {
        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        byte[] jpeg = out.toByteArray();
        return Arrays.copyOf(jpeg, Math.toIntExact(size));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
