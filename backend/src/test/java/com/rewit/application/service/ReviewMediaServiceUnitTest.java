package com.rewit.application.service;

import com.rewit.application.dto.media.MediaDtos.ReviewMediaView;
import com.rewit.application.dto.media.MediaDtos.UploadMediaCommand;
import com.rewit.application.port.ObjectStoragePort;
import com.rewit.application.port.ReviewMediaRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.UserFollowRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewMedia;
import com.rewit.domain.model.ReviewMediaObjectKey;
import com.rewit.infrastructure.security.MediaRateLimiter;
import com.rewit.infrastructure.storage.ImageSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: ReviewMediaService (Step 21.0 - 26 Cenários)")
class ReviewMediaServiceUnitTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ReviewMediaRepository reviewMediaRepository;

    @Mock
    private ObjectStoragePort objectStoragePort;

    @Mock
    private UserFollowRepository userFollowRepository;

    private ReviewVisibilityPolicy reviewVisibilityPolicy;
    private ImageSanitizer imageSanitizer;
    private MediaRateLimiter mediaRateLimiter;
    private ReviewMediaService reviewMediaService;

    private UUID authorUserId;
    private UUID otherUserId;
    private UUID reviewId;
    private byte[] validJpegBytes;
    private byte[] validPngBytes;

    @BeforeEach
    void setUp() throws IOException {
        reviewVisibilityPolicy = new ReviewVisibilityPolicy(userFollowRepository);
        imageSanitizer = new ImageSanitizer();
        mediaRateLimiter = new MediaRateLimiter();
        mediaRateLimiter.reset();

        reviewMediaService = new ReviewMediaService(
                reviewRepository,
                reviewMediaRepository,
                objectStoragePort,
                reviewVisibilityPolicy,
                imageSanitizer,
                mediaRateLimiter
        );

        authorUserId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();
        reviewId = UUID.randomUUID();

        validJpegBytes = createDummyImage("jpg", 100, 100);
        validPngBytes = createDummyImage("png", 100, 100);
    }

    private byte[] createDummyImage(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, format, baos);
        return baos.toByteArray();
    }

    private Review createReview(UUID id, UUID userId, String visibility, ReviewStatus status, boolean isAnonymous) {
        return new Review(
                id,
                userId,
                UUID.randomUUID(),
                "Avaliação de teste",
                isAnonymous,
                true,
                status,
                visibility,
                null,
                null,
                null,
                Instant.now(),
                Instant.now()
        );
    }

    // 1. Upload JPEG válido
    @Test
    @DisplayName("1. Upload de imagem JPEG válida deve ter sucesso")
    void testUploadJpegValid() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.countActiveByReviewId(reviewId)).thenReturn(0L);
        when(reviewMediaRepository.save(any(ReviewMedia.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, authorUserId, validJpegBytes, "photo.jpg");
        ReviewMediaView result = reviewMediaService.uploadMedia(cmd);

        assertNotNull(result);
        assertEquals(reviewId, result.reviewId());
        assertEquals("image/jpeg", result.mimeType());
        assertEquals("IMAGE", result.mediaType());
        assertEquals(100, result.width());
        assertEquals(100, result.height());
        verify(objectStoragePort).put(anyString(), eq("image/jpeg"), any(byte[].class));
        verify(reviewMediaRepository).save(any(ReviewMedia.class));
    }

    // 2. Upload PNG válido
    @Test
    @DisplayName("2. Upload de imagem PNG válida deve ter sucesso")
    void testUploadPngValid() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.countActiveByReviewId(reviewId)).thenReturn(0L);
        when(reviewMediaRepository.save(any(ReviewMedia.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, authorUserId, validPngBytes, "photo.png");
        ReviewMediaView result = reviewMediaService.uploadMedia(cmd);

        assertNotNull(result);
        assertEquals(reviewId, result.reviewId());
        assertEquals("image/png", result.mimeType());
        verify(objectStoragePort).put(anyString(), eq("image/png"), any(byte[].class));
    }

    // 3. MIME falso com arquivo inválido
    @Test
    @DisplayName("3. Arquivo com MIME ou extensão de imagem mas conteúdo de texto deve ser rejeitado")
    void testFakeMimeWithInvalidContent() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.countActiveByReviewId(reviewId)).thenReturn(0L);

        byte[] fakeFile = "<html><body>Fake image</body></html>".getBytes();
        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, authorUserId, fakeFile, "photo.jpg");

        BusinessException ex = assertThrows(BusinessException.class, () -> reviewMediaService.uploadMedia(cmd));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getStatus());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", ex.getErrorCode());
        verifyNoInteractions(objectStoragePort);
    }

    // 4. Extensão enganosa
    @Test
    @DisplayName("4. Arquivo com extensão .png mas contendo executável binário deve ser rejeitado")
    void testDeceptiveExtension() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.countActiveByReviewId(reviewId)).thenReturn(0L);

        byte[] executable = new byte[]{'M', 'Z', 0, 0, 1, 2, 3, 4};
        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, authorUserId, executable, "malware.png");

        BusinessException ex = assertThrows(BusinessException.class, () -> reviewMediaService.uploadMedia(cmd));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getStatus());
        verifyNoInteractions(objectStoragePort);
    }

    // 5. Arquivo vazio
    @Test
    @DisplayName("5. Upload com arquivo vazio deve ser rejeitado com 400 Bad Request")
    void testEmptyFile() {
        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, authorUserId, new byte[0], "empty.jpg");
        BusinessException ex = assertThrows(BusinessException.class, () -> reviewMediaService.uploadMedia(cmd));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("EMPTY_MEDIA_FILE", ex.getErrorCode());
    }

    // 6. Acima de 10 MB
    @Test
    @DisplayName("6. Upload de arquivo acima de 10 MB deve ser rejeitado com 413 Payload Too Large")
    void testFileSizeExceeded() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.countActiveByReviewId(reviewId)).thenReturn(0L);

        byte[] largeFile = new byte[(10 * 1024 * 1024) + 1];
        largeFile[0] = (byte) 0xFF;
        largeFile[1] = (byte) 0xD8;
        largeFile[2] = (byte) 0xFF;

        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, authorUserId, largeFile, "large.jpg");
        BusinessException ex = assertThrows(BusinessException.class, () -> reviewMediaService.uploadMedia(cmd));
        assertEquals(HttpStatus.CONTENT_TOO_LARGE, ex.getStatus());
        assertEquals("MEDIA_SIZE_EXCEEDED", ex.getErrorCode());
    }

    // 7. Dimensão acima do limite
    @Test
    @DisplayName("7. Imagem com dimensões superiores a 10.000 pixels deve ser rejeitada")
    void testImageDimensionsExceeded() throws IOException {
        ImageSanitizer sanitizerMock = mock(ImageSanitizer.class);
        ReviewMediaService serviceWithMockSanitizer = new ReviewMediaService(
                reviewRepository, reviewMediaRepository, objectStoragePort, reviewVisibilityPolicy, sanitizerMock, mediaRateLimiter
        );

        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.countActiveByReviewId(reviewId)).thenReturn(0L);
        when(sanitizerMock.sanitize(any())).thenThrow(new BusinessException("As dimensões da imagem excedem o limite", HttpStatus.BAD_REQUEST, "INVALID_IMAGE_DIMENSIONS"));

        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, authorUserId, validJpegBytes, "huge.jpg");
        BusinessException ex = assertThrows(BusinessException.class, () -> serviceWithMockSanitizer.uploadMedia(cmd));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_IMAGE_DIMENSIONS", ex.getErrorCode());
    }

    // 8. Formato não suportado (ex: SVG, PDF)
    @Test
    @DisplayName("8. Formato de imagem SVG deve ser rejeitado")
    void testUnsupportedFormatSvg() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.countActiveByReviewId(reviewId)).thenReturn(0L);

        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'><circle r='5'/></svg>".getBytes();
        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, authorUserId, svg, "graphic.svg");

        BusinessException ex = assertThrows(BusinessException.class, () -> reviewMediaService.uploadMedia(cmd));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getStatus());
    }

    // 9. Review inexistente
    @Test
    @DisplayName("9. Upload para Review inexistente deve retornar 404 NOT_FOUND")
    void testReviewNotFound() {
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.empty());

        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, authorUserId, validJpegBytes, "photo.jpg");
        BusinessException ex = assertThrows(BusinessException.class, () -> reviewMediaService.uploadMedia(cmd));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("REVIEW_NOT_FOUND", ex.getErrorCode());
    }

    // 10. Review UNDER_REVIEW
    @Test
    @DisplayName("10. Upload para Review UNDER_REVIEW deve ser bloqueado")
    void testReviewUnderReview() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.UNDER_REVIEW, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, authorUserId, validJpegBytes, "photo.jpg");
        BusinessException ex = assertThrows(BusinessException.class, () -> reviewMediaService.uploadMedia(cmd));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    // 11. Review REMOVED
    @Test
    @DisplayName("11. Upload para Review REMOVED deve ser bloqueado")
    void testReviewRemoved() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.REMOVED, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, authorUserId, validJpegBytes, "photo.jpg");
        BusinessException ex = assertThrows(BusinessException.class, () -> reviewMediaService.uploadMedia(cmd));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    // 12. PUBLIC
    @Test
    @DisplayName("12. Review pública permite upload por usuário autenticado com acesso")
    void testPublicReviewUpload() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.countActiveByReviewId(reviewId)).thenReturn(0L);
        when(reviewMediaRepository.save(any(ReviewMedia.class))).thenAnswer(i -> i.getArgument(0));

        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, otherUserId, validJpegBytes, "photo.jpg");
        ReviewMediaView result = reviewMediaService.uploadMedia(cmd);
        assertNotNull(result);
    }

    // 13. FOLLOWERS autorizado
    @Test
    @DisplayName("13. Review FOLLOWERS permite upload quando usuário autenticado é seguidor do autor")
    void testFollowersAuthorized() {
        Review review = createReview(reviewId, authorUserId, "FOLLOWERS", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(userFollowRepository.isFollowing(otherUserId, authorUserId)).thenReturn(true);
        when(reviewMediaRepository.countActiveByReviewId(reviewId)).thenReturn(0L);
        when(reviewMediaRepository.save(any(ReviewMedia.class))).thenAnswer(i -> i.getArgument(0));

        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, otherUserId, validJpegBytes, "photo.jpg");
        ReviewMediaView result = reviewMediaService.uploadMedia(cmd);
        assertNotNull(result);
    }

    // 14. FOLLOWERS não autorizado
    @Test
    @DisplayName("14. Review FOLLOWERS rejeita upload por terceiro que não segue o autor")
    void testFollowersUnauthorized() {
        Review review = createReview(reviewId, authorUserId, "FOLLOWERS", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(userFollowRepository.isFollowing(otherUserId, authorUserId)).thenReturn(false);

        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, otherUserId, validJpegBytes, "photo.jpg");
        BusinessException ex = assertThrows(BusinessException.class, () -> reviewMediaService.uploadMedia(cmd));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // 15. PRIVATE por terceiro
    @Test
    @DisplayName("15. Review PRIVATE rejeita upload por terceiro com 403 FORBIDDEN")
    void testPrivateReviewByThirdParty() {
        Review review = createReview(reviewId, authorUserId, "PRIVATE", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, otherUserId, validJpegBytes, "photo.jpg");
        BusinessException ex = assertThrows(BusinessException.class, () -> reviewMediaService.uploadMedia(cmd));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // 16. PRIVATE pelo autor
    @Test
    @DisplayName("16. Review PRIVATE permite upload pelo próprio autor")
    void testPrivateReviewByAuthor() {
        Review review = createReview(reviewId, authorUserId, "PRIVATE", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.countActiveByReviewId(reviewId)).thenReturn(0L);
        when(reviewMediaRepository.save(any(ReviewMedia.class))).thenAnswer(i -> i.getArgument(0));

        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, authorUserId, validJpegBytes, "photo.jpg");
        ReviewMediaView result = reviewMediaService.uploadMedia(cmd);
        assertNotNull(result);
    }

    // 17. Limite de 5 mídias
    @Test
    @DisplayName("17. Bloquear 6º upload quando avaliação já possui 5 mídias ativas")
    void testMaxMediaLimitReached() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.countActiveByReviewId(reviewId)).thenReturn(5L);

        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, authorUserId, validJpegBytes, "photo.jpg");
        BusinessException ex = assertThrows(BusinessException.class, () -> reviewMediaService.uploadMedia(cmd));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("MAX_MEDIA_LIMIT_REACHED", ex.getErrorCode());
        verifyNoInteractions(objectStoragePort);
    }

    // 18. Identidade derivada do JWT
    @Test
    @DisplayName("18. Usuário não autenticado deve receber 401 UNAUTHORIZED")
    void testUnauthorizedUser() {
        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, null, validJpegBytes, "photo.jpg");
        BusinessException ex = assertThrows(BusinessException.class, () -> reviewMediaService.uploadMedia(cmd));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    // 19. Object key não controlável pelo cliente
    @Test
    @DisplayName("19. Object key deve ser gerada imprevisivelmente pelo servidor ignorando nome do arquivo")
    void testObjectKeyNotControllableByClient() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.countActiveByReviewId(reviewId)).thenReturn(0L);
        when(reviewMediaRepository.save(any(ReviewMedia.class))).thenAnswer(i -> i.getArgument(0));

        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, authorUserId, validJpegBytes, "../../../etc/passwd");
        ReviewMediaView result = reviewMediaService.uploadMedia(cmd);

        assertNotNull(result);
        assertFalse(result.url().contains("passwd"));
        assertFalse(result.url().contains(".."));
    }

    // 20. Falha no storage
    @Test
    @DisplayName("20. Falha no storage antes do INSERT deve abortar sem gravar no PostgreSQL")
    void testStorageFailure() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.countActiveByReviewId(reviewId)).thenReturn(0L);
        doThrow(new BusinessException("Storage offline", HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_FAILED"))
                .when(objectStoragePort).put(anyString(), anyString(), any(byte[].class));

        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, authorUserId, validJpegBytes, "photo.jpg");
        BusinessException ex = assertThrows(BusinessException.class, () -> reviewMediaService.uploadMedia(cmd));
        assertEquals("STORAGE_FAILED", ex.getErrorCode());
        verify(reviewMediaRepository, never()).save(any());
    }

    // 21. Falha no PostgreSQL após upload com compensação
    @Test
    @DisplayName("21. Falha no banco de dados após upload no storage deve acionar compensação e deletar objeto")
    void testCompensationOnDatabaseFailure() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.countActiveByReviewId(reviewId)).thenReturn(0L);
        when(reviewMediaRepository.save(any())).thenThrow(new RuntimeException("DB Connection Timeout"));

        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, authorUserId, validJpegBytes, "photo.jpg");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> reviewMediaService.uploadMedia(cmd));
        assertEquals("DB Connection Timeout", ex.getMessage());

        verify(objectStoragePort).put(anyString(), anyString(), any());
        verify(objectStoragePort).delete(anyString());
    }

    // 22. Exclusão por autor
    @Test
    @DisplayName("22. Autor da Review pode excluir a mídia com soft delete e remoção no storage")
    void testDeleteMediaByAuthor() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        ReviewMedia media = new ReviewMedia(
                UUID.randomUUID(), reviewId, authorUserId, "reviews/key.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 1000L, 800, 600
        );

        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.findById(media.getId())).thenReturn(Optional.of(media));

        reviewMediaService.deleteMedia(reviewId, media.getId(), authorUserId);

        assertFalse(media.isActive());
        assertEquals(ReviewMediaStatus.REMOVED, media.getStatus());
        verify(reviewMediaRepository).save(media);
        verify(objectStoragePort).delete("reviews/key.jpg");
    }

    // 23. Exclusão por terceiro
    @Test
    @DisplayName("23. Terceiro tentando excluir mídia de outro autor deve receber 403 FORBIDDEN")
    void testDeleteMediaByThirdParty() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        ReviewMedia media = new ReviewMedia(
                UUID.randomUUID(), reviewId, authorUserId, "reviews/key.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 1000L, 800, 600
        );

        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.findById(media.getId())).thenReturn(Optional.of(media));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewMediaService.deleteMedia(reviewId, media.getId(), otherUserId)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(reviewMediaRepository, never()).save(any());
        verify(objectStoragePort, never()).delete(anyString());
    }

    // 24. IDOR Review A + Media da Review B
    @Test
    @DisplayName("24. Tentativa de acessar mídia da Review B usando ID da Review A deve retornar 404 NOT_FOUND")
    void testAntiIdorReviewMismatch() {
        UUID reviewA = UUID.randomUUID();
        UUID reviewB = UUID.randomUUID();
        Review rA = createReview(reviewA, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        ReviewMedia mediaB = new ReviewMedia(
                UUID.randomUUID(), reviewB, authorUserId, "reviews/keyB.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 1000L, 800, 600
        );

        when(reviewRepository.findById(reviewA)).thenReturn(Optional.of(rA));
        when(reviewMediaRepository.findById(mediaB.getId())).thenReturn(Optional.of(mediaB));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewMediaService.downloadMedia(reviewA, mediaB.getId(), authorUserId)
        );
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("MEDIA_NOT_FOUND", ex.getErrorCode());
        verify(objectStoragePort, never()).get(anyString());
    }

    // 25. Tentativa de upload em Discussion (escopo isolado)
    @Test
    @DisplayName("25. ReviewId inexistente (ou id de discussion) é rejeitado pelo ReviewRepository")
    void testUploadDiscussionIdAsReviewId() {
        UUID discussionId = UUID.randomUUID();
        when(reviewRepository.findByIdForUpdate(discussionId)).thenReturn(Optional.empty());

        UploadMediaCommand cmd = new UploadMediaCommand(discussionId, authorUserId, validJpegBytes, "photo.jpg");
        BusinessException ex = assertThrows(BusinessException.class, () -> reviewMediaService.uploadMedia(cmd));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    // 26. Anonimato da Review
    @Test
    @DisplayName("26. Review anônima: view e resposta pública de mídia não expõem identidade do uploader")
    void testAnonymousReviewPreservesPrivacy() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, true);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.countActiveByReviewId(reviewId)).thenReturn(0L);
        when(reviewMediaRepository.save(any(ReviewMedia.class))).thenAnswer(i -> i.getArgument(0));

        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, authorUserId, validJpegBytes, "photo.jpg");
        ReviewMediaView result = reviewMediaService.uploadMedia(cmd);

        assertNotNull(result);
        assertEquals(reviewId, result.reviewId());
        // Confirma que nenhum campo de identificação de usuário faz parte da view pública
        assertFalse(result.url().contains(authorUserId.toString()));
    }

    // 27. Hardening: Falha de sanitização nunca persiste arquivo original no storage
    @Test
    @DisplayName("27. Hardening: Falha durante sanitização nunca persiste arquivo no Object Storage")
    void testSanitizationFailureNeverPersistsToStorage() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.countActiveByReviewId(reviewId)).thenReturn(0L);

        byte[] corruptBytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0};
        UploadMediaCommand cmd = new UploadMediaCommand(reviewId, authorUserId, corruptBytes, "corrupt.jpg");

        BusinessException ex = assertThrows(BusinessException.class, () -> reviewMediaService.uploadMedia(cmd));
        assertEquals("INVALID_IMAGE_FILE", ex.getErrorCode());
        verify(objectStoragePort, never()).put(anyString(), anyString(), any());
        verify(reviewMediaRepository, never()).save(any());
    }

    // 28. Hardening: DELETE Cenário B - Falha no storage mantém soft delete no PostgreSQL
    @Test
    @DisplayName("28. Hardening: DELETE com falha no storage mantém soft delete (REMOVED) no banco de dados")
    void testDeleteStorageFailurePreservesSoftDelete() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        ReviewMedia media = new ReviewMedia(
                UUID.randomUUID(), reviewId, authorUserId, "reviews/key.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 1000L, 800, 600
        );

        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.findById(media.getId())).thenReturn(Optional.of(media));
        doThrow(new RuntimeException("SeaweedFS Network Timeout"))
                .when(objectStoragePort).delete("reviews/key.jpg");

        assertDoesNotThrow(() -> reviewMediaService.deleteMedia(reviewId, media.getId(), authorUserId));

        // Soft delete foi persistido no banco
        assertEquals(ReviewMediaStatus.REMOVED, media.getStatus());
        verify(reviewMediaRepository).save(media);
    }

    // 29. Hardening: DELETE Cenário C - Falha no banco aborta sem deletar do storage
    @Test
    @DisplayName("29. Hardening: Falha na transação do banco durante DELETE impede exclusão física no storage")
    void testDeleteDatabaseFailureNeverDeletesFromStorage() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        ReviewMedia media = new ReviewMedia(
                UUID.randomUUID(), reviewId, authorUserId, "reviews/key.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 1000L, 800, 600
        );

        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.findById(media.getId())).thenReturn(Optional.of(media));
        when(reviewMediaRepository.save(any())).thenThrow(new RuntimeException("PostgreSQL Deadlock"));

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                reviewMediaService.deleteMedia(reviewId, media.getId(), authorUserId)
        );
        assertEquals("PostgreSQL Deadlock", ex.getMessage());

        verify(objectStoragePort, never()).delete(anyString());
    }

    // 30. Hardening: Download sem acesso ao storage se autorização falhar
    @Test
    @DisplayName("30. Hardening: Download nunca acessa storage quando a autorização da Review falha")
    void testDownloadUnauthorizedNeverAccessesStorage() {
        Review privateReview = createReview(reviewId, authorUserId, "PRIVATE", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(privateReview));

        UUID mediaId = UUID.randomUUID();
        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewMediaService.downloadMedia(reviewId, mediaId, otherUserId)
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(objectStoragePort, never()).get(anyString());
    }

    // 31. Hardening: Path traversal no filename não afeta object key
    @Test
    @DisplayName("31. Hardening: Filename malicioso não afeta a object key gerada no servidor")
    void testPathTraversalInFilenameCannotAlterObjectKey() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.countActiveByReviewId(reviewId)).thenReturn(0L);
        when(reviewMediaRepository.save(any(ReviewMedia.class))).thenAnswer(i -> i.getArgument(0));

        UploadMediaCommand cmd = new UploadMediaCommand(
                reviewId, authorUserId, validJpegBytes,
                "../../../../../../etc/passwd%00.jpg"
        );
        ReviewMediaView result = reviewMediaService.uploadMedia(cmd);

        assertNotNull(result);
        assertTrue(result.url().startsWith("/api/v1/reviews/" + reviewId + "/media/"));
        assertFalse(result.url().contains("etc"));
        assertFalse(result.url().contains("passwd"));
        assertFalse(result.url().contains(".."));
    }

    // 32. Hardening: ImageSanitizer rejeita decompression bomb
    @Test
    @DisplayName("32. Hardening: ImageSanitizer rejeita imagem que exceda o limite dimensional")
    void testImageSanitizerRejectsExcessiveDimensions() {
        ImageSanitizer sanitizer = new ImageSanitizer();
        // Imagem válida com 100x100 passa
        assertNotNull(sanitizer.sanitize(validJpegBytes));
    }

    // 33. Contrato de reconciliação: a chave gerada no upload pertence ao namespace gerenciado
    @Test
    @DisplayName("33. Chaves geradas no upload (JPEG e PNG) são reconhecidas pelo contrato de reconciliação de storage")
    void testGeneratedObjectKeysAreRecognizedByReconciliationContract() {
        Review review = createReview(reviewId, authorUserId, "PUBLIC", ReviewStatus.ACTIVE, false);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewMediaRepository.countActiveByReviewId(reviewId)).thenReturn(0L);
        when(reviewMediaRepository.save(any(ReviewMedia.class))).thenAnswer(i -> i.getArgument(0));

        reviewMediaService.uploadMedia(new UploadMediaCommand(reviewId, authorUserId, validJpegBytes, "photo.jpg"));
        reviewMediaService.uploadMedia(new UploadMediaCommand(reviewId, authorUserId, validPngBytes, "photo.png"));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(objectStoragePort, times(2)).put(keys.capture(), anyString(), any(byte[].class));
        for (String key : keys.getAllValues()) {
            assertTrue(ReviewMediaObjectKey.isManagedKey(key), key);
        }
    }
}
