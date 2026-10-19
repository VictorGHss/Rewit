package com.rewit.application.service;

import com.rewit.application.dto.media.MediaDtos.MediaDownloadResult;
import com.rewit.application.dto.media.MediaDtos.PlaceMediaView;
import com.rewit.application.dto.media.MediaDtos.UploadPlaceMediaCommand;
import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.application.port.ObjectStoragePort;
import com.rewit.application.port.PlaceMediaRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateLimiter;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import com.rewit.domain.enums.AccountStatus;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.enums.Role;
import com.rewit.domain.enums.VerificationStatus;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceMedia;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.persistence.entity.PlaceMediaReportJpaEntity;
import com.rewit.infrastructure.persistence.repository.PlaceMediaReportJpaRepository;
import com.rewit.infrastructure.storage.ImageSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: PlaceMediaService (A-02, A-06, A-07, A-08)")
class PlaceMediaServiceTest {

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private PlaceMediaRepository placeMediaRepository;

    @Mock
    private AccountStatusPolicy accountStatusPolicy;

    @Mock
    private ObjectStoragePort objectStoragePort;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private BusinessAccountRepository businessAccountRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PlaceMediaReportJpaRepository placeMediaReportJpaRepository;

    private PlaceMediaService placeMediaService;
    private UUID userId;
    private UUID placeId;
    private Place activePlace;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        placeId = UUID.randomUUID();
        activePlace = new Place(
                placeId,
                "Pastel da Dona Ana",
                "pastel-da-dona-ana",
                "GERAL",
                null,
                "Av. Brasil, 500 - Centro",
                null,
                null,
                null,
                null,
                "BR",
                null,
                null,
                50,
                "USER",
                false,
                null,
                userId,
                "ACTIVE"
        );
        placeMediaService = new PlaceMediaService(
                placeRepository,
                placeMediaRepository,
                accountStatusPolicy,
                objectStoragePort,
                new ImageSanitizer(),
                rateLimiter,
                businessAccountRepository,
                userRepository,
                placeMediaReportJpaRepository
        );
    }

    private byte[] createValidPngBytes() throws IOException {
        BufferedImage img = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    @DisplayName("1. Upload de fotografia válida sanitiza imagem, persiste no storage sob places/{placeId}/{mediaId} e salva metadata")
    void shouldUploadAndSanitizePlaceMediaSuccessfully() throws IOException {
        byte[] pngBytes = createValidPngBytes();

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.countActiveByPlaceId(placeId)).thenReturn(0L);
        when(placeMediaRepository.save(any(PlaceMedia.class))).thenAnswer(inv -> inv.getArgument(0));

        PlaceMediaView result = placeMediaService.uploadMedia(
                new UploadPlaceMediaCommand(placeId, userId, pngBytes, "fachada.png")
        );

        assertNotNull(result);
        assertEquals(placeId, result.placeId());
        assertEquals("IMAGE", result.mediaType());
        assertEquals("image/png", result.mimeType());
        assertEquals(32, result.width());
        assertEquals(24, result.height());
        assertTrue(result.url().startsWith("/api/v1/places/" + placeId + "/media/"));

        verify(objectStoragePort).put(startsWith("places/" + placeId + "/"), eq("image/png"), any(byte[].class));
        verify(placeMediaRepository).save(any(PlaceMedia.class));
    }

    @Test
    @DisplayName("2. Falha ao persistir metadata no banco remove objeto enviado do storage (compensação)")
    void shouldDeleteUploadedObjectWhenDatabaseSaveFails() throws IOException {
        byte[] pngBytes = createValidPngBytes();

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.countActiveByPlaceId(placeId)).thenReturn(0L);
        when(placeMediaRepository.save(any(PlaceMedia.class))).thenThrow(new RuntimeException("DB failure"));

        assertThrows(RuntimeException.class, () ->
                placeMediaService.uploadMedia(new UploadPlaceMediaCommand(placeId, userId, pngBytes, "fachada.png"))
        );

        verify(objectStoragePort).put(startsWith("places/" + placeId + "/"), eq("image/png"), any(byte[].class));
        verify(objectStoragePort).delete(startsWith("places/" + placeId + "/"));
    }

    @Test
    @DisplayName("3. Arquivo inválido (magic bytes não JPEG/PNG) é rejeitado antes de tocar no storage")
    void shouldRejectInvalidImageMagicBytesBeforeStorage() {
        byte[] fakeBytes = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};

        BusinessException ex = assertThrows(BusinessException.class, () ->
                placeMediaService.uploadMedia(new UploadPlaceMediaCommand(placeId, userId, fakeBytes, "exploit.jpg"))
        );

        assertEquals("UNSUPPORTED_MEDIA_TYPE", ex.getErrorCode());
        verifyNoInteractions(objectStoragePort);
        verify(placeMediaRepository, never()).save(any());
        verifyNoInteractions(placeRepository);
    }

    @Test
    @DisplayName("4. Download de fotografia valida vínculo placeId <-> mediaId (prevenção de IDOR)")
    void shouldRejectDownloadWhenMediaBelongsToAnotherPlace() {
        UUID mediaId = UUID.randomUUID();
        UUID otherPlaceId = UUID.randomUUID();
        PlaceMedia mediaOfOtherPlace = new PlaceMedia(
                mediaId,
                otherPlaceId,
                userId,
                "places/" + otherPlaceId + "/" + mediaId + "/image.png",
                ReviewMediaType.IMAGE,
                "image/png",
                1024L,
                32,
                24
        );

        when(placeMediaRepository.findById(mediaId)).thenReturn(Optional.of(mediaOfOtherPlace));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                placeMediaService.downloadMedia(placeId, mediaId)
        );

        assertEquals("MEDIA_NOT_FOUND", ex.getErrorCode());
        verifyNoInteractions(objectStoragePort);
    }

    @Test
    @DisplayName("5. findPrimaryPhotoUrl retorna a URL pública da fotografia primária ativa do local")
    void shouldReturnPrimaryPhotoUrlWhenActiveMediaExists() {
        UUID mediaId = UUID.randomUUID();
        PlaceMedia media = new PlaceMedia(
                mediaId,
                placeId,
                userId,
                "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE,
                "image/jpeg",
                2048L,
                100,
                80
        );

        when(placeMediaRepository.findPrimaryByPlaceId(placeId)).thenReturn(Optional.of(media));

        String photoUrl = placeMediaService.findPrimaryPhotoUrl(placeId);

        assertEquals("/api/v1/places/" + placeId + "/media/" + mediaId, photoUrl);
    }

    @Test
    @DisplayName("6. Usuário não autorizado (nem criador, nem representante comercial, nem moderador) tem upload rejeitado com 403 FORBIDDEN")
    void shouldRejectUploadByUnauthorizedUser() throws IOException {
        byte[] pngBytes = createValidPngBytes();
        UUID unauthorizedUserId = UUID.randomUUID();

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(userRepository.findById(unauthorizedUserId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                placeMediaService.uploadMedia(new UploadPlaceMediaCommand(placeId, unauthorizedUserId, pngBytes, "foto.png"))
        );

        assertEquals("FORBIDDEN", ex.getErrorCode());
        verifyNoInteractions(objectStoragePort);
        verify(placeMediaRepository, never()).save(any());
    }

    @Test
    @DisplayName("7. Representante comercial aprovado tem permissão para upload de fotos do local reivindicado")
    void shouldAllowUploadByApprovedBusinessRepresentative() throws IOException {
        byte[] pngBytes = createValidPngBytes();
        UUID repUserId = UUID.randomUUID();
        UUID businessId = UUID.randomUUID();

        Place claimedPlace = new Place(
                placeId, "Pastel da Dona Ana", "pastel-da-dona-ana", "GERAL", null,
                "Av. Brasil, 500", null, null, null, null, "BR", null, null, 50,
                "USER", true, businessId, UUID.randomUUID(), "ACTIVE"
        );

        BusinessAccount approvedAccount = new BusinessAccount(
                businessId, repUserId, "Pastelaria Dona Ana LTDA", "12345678000199",
                VerificationStatus.APPROVED, "FREE"
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(claimedPlace));
        when(businessAccountRepository.findById(businessId)).thenReturn(Optional.of(approvedAccount));
        when(placeMediaRepository.countActiveByPlaceId(placeId)).thenReturn(0L);
        when(placeMediaRepository.save(any(PlaceMedia.class))).thenAnswer(inv -> inv.getArgument(0));

        PlaceMediaView result = placeMediaService.uploadMedia(
                new UploadPlaceMediaCommand(placeId, repUserId, pngBytes, "foto.png")
        );

        assertNotNull(result);
        verify(objectStoragePort).put(startsWith("places/" + placeId + "/"), eq("image/png"), any(byte[].class));
        verify(placeMediaRepository).save(any(PlaceMedia.class));
    }

    @Test
    @DisplayName("8. Moderador tem permissão para upload mesmo não sendo criador nem representante")
    void shouldAllowUploadByModerator() throws IOException {
        byte[] pngBytes = createValidPngBytes();
        UUID modUserId = UUID.randomUUID();

        User moderator = User.rehydrate(
                modUserId, "mod@rewit.com", "hash", AuthProvider.LOCAL, null,
                AccountStatus.ACTIVE, true, Role.MODERATOR, null, Instant.now(), Instant.now()
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(userRepository.findById(modUserId)).thenReturn(Optional.of(moderator));
        when(placeMediaRepository.countActiveByPlaceId(placeId)).thenReturn(0L);
        when(placeMediaRepository.save(any(PlaceMedia.class))).thenAnswer(inv -> inv.getArgument(0));

        PlaceMediaView result = placeMediaService.uploadMedia(
                new UploadPlaceMediaCommand(placeId, modUserId, pngBytes, "foto.png")
        );

        assertNotNull(result);
        verify(objectStoragePort).put(startsWith("places/" + placeId + "/"), eq("image/png"), any(byte[].class));
    }

    @Test
    @DisplayName("9. Idempotência com mesma chave e mesmo SHA-256 devolve registro existente sem re-upload")
    void shouldReplayExistingMediaWhenIdempotencyKeyAndSha256Match() throws IOException {
        byte[] pngBytes = createValidPngBytes();
        String idempotencyKey = "idem-key-123";

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));

        // Obter hash calculado da imagem sanitizada
        ImageSanitizer sanitizer = new ImageSanitizer();
        byte[] sanitizedBytes = sanitizer.sanitize(pngBytes).bytes();
        String expectedHash;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(sanitizedBytes);
            expectedHash = java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        UUID existingMediaId = UUID.randomUUID();
        PlaceMedia existingMedia = new PlaceMedia(
                existingMediaId, placeId, userId, "places/" + placeId + "/" + existingMediaId + "/image.png",
                ReviewMediaType.IMAGE, "image/png", (long) sanitizedBytes.length, 32, 24,
                ReviewMediaStatus.ACTIVE, true, idempotencyKey, expectedHash, Instant.now(), Instant.now()
        );

        when(placeMediaRepository.findByIdempotencyKey(userId, placeId, idempotencyKey))
                .thenReturn(Optional.of(existingMedia));

        PlaceMediaView result = placeMediaService.uploadMedia(
                new UploadPlaceMediaCommand(placeId, userId, pngBytes, "foto.png", idempotencyKey)
        );

        assertEquals(existingMediaId, result.id());
        verifyNoInteractions(objectStoragePort);
        verify(placeMediaRepository, never()).save(any());
    }

    @Test
    @DisplayName("10. Idempotência com mesma chave e payload diferente lança 409 IDEMPOTENCY_CONFLICT")
    void shouldRejectUploadWhenIdempotencyKeyReusedWithDifferentPayload() throws IOException {
        byte[] pngBytes = createValidPngBytes();
        String idempotencyKey = "idem-key-123";

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));

        UUID existingMediaId = UUID.randomUUID();
        PlaceMedia existingMedia = new PlaceMedia(
                existingMediaId, placeId, userId, "places/" + placeId + "/" + existingMediaId + "/image.png",
                ReviewMediaType.IMAGE, "image/png", 1024L, 32, 24,
                ReviewMediaStatus.ACTIVE, true, idempotencyKey, "different_sha256_hash", Instant.now(), Instant.now()
        );

        when(placeMediaRepository.findByIdempotencyKey(userId, placeId, idempotencyKey))
                .thenReturn(Optional.of(existingMedia));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                placeMediaService.uploadMedia(new UploadPlaceMediaCommand(placeId, userId, pngBytes, "foto.png", idempotencyKey))
        );

        assertEquals("IDEMPOTENCY_CONFLICT", ex.getErrorCode());
        verifyNoInteractions(objectStoragePort);
    }

    @Test
    @DisplayName("11. setPrimaryMedia altera capa do local e desmarca a capa anterior")
    void shouldSetPrimaryMediaSuccessfully() {
        UUID oldPrimaryId = UUID.randomUUID();
        UUID newPrimaryId = UUID.randomUUID();

        PlaceMedia oldPrimary = new PlaceMedia(
                oldPrimaryId, placeId, userId, "places/" + placeId + "/" + oldPrimaryId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                ReviewMediaStatus.ACTIVE, true, null, null, Instant.now(), Instant.now()
        );

        PlaceMedia newPrimary = new PlaceMedia(
                newPrimaryId, placeId, userId, "places/" + placeId + "/" + newPrimaryId + "/image.png",
                ReviewMediaType.IMAGE, "image/png", 1024L, 640, 480,
                ReviewMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findById(newPrimaryId)).thenReturn(Optional.of(newPrimary));
        when(placeMediaRepository.findPrimaryByPlaceId(placeId)).thenReturn(Optional.of(oldPrimary));
        when(placeMediaRepository.save(any(PlaceMedia.class))).thenAnswer(inv -> inv.getArgument(0));

        PlaceMediaView result = placeMediaService.setPrimaryMedia(userId, placeId, newPrimaryId);

        assertTrue(result.isPrimary());
        assertEquals(newPrimaryId, result.id());
        assertFalse(oldPrimary.isPrimary());
        verify(placeMediaRepository).save(oldPrimary);
        verify(placeMediaRepository).save(newPrimary);
    }

    @Test
    @DisplayName("12. deleteMedia na foto capa elege deterministamente a foto ativa mais antiga restante")
    void shouldElectNextOldestActivePhotoWhenPrimaryIsDeleted() {
        UUID primaryId = UUID.randomUUID();
        UUID nextOldestId = UUID.randomUUID();

        PlaceMedia primaryMedia = new PlaceMedia(
                primaryId, placeId, userId, "places/" + placeId + "/" + primaryId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                ReviewMediaStatus.ACTIVE, true, null, null, Instant.now(), Instant.now()
        );

        PlaceMedia nextOldestMedia = new PlaceMedia(
                nextOldestId, placeId, userId, "places/" + placeId + "/" + nextOldestId + "/image.png",
                ReviewMediaType.IMAGE, "image/png", 1024L, 640, 480,
                ReviewMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findById(primaryId)).thenReturn(Optional.of(primaryMedia));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        when(placeMediaRepository.findFirstActiveByPlaceId(placeId)).thenReturn(Optional.of(nextOldestMedia));

        placeMediaService.deleteMedia(userId, placeId, primaryId);

        assertEquals(ReviewMediaStatus.REMOVED, primaryMedia.getStatus());
        assertTrue(nextOldestMedia.isPrimary());
        verify(placeMediaRepository).save(primaryMedia);
        verify(placeMediaRepository).save(nextOldestMedia);
    }

    @Test
    @DisplayName("13. deleteMedia por moderador defere automaticamente denúncias pendentes (ACCEPTED)")
    void shouldAcceptPendingReportsWhenModeratorDeletesMedia() {
        UUID mediaId = UUID.randomUUID();
        UUID modUserId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                ReviewMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        User moderator = User.rehydrate(
                modUserId, "mod@rewit.com", "hash", AuthProvider.LOCAL, null,
                AccountStatus.ACTIVE, true, Role.MODERATOR, null, Instant.now(), Instant.now()
        );

        PlaceMediaReportJpaEntity report = new PlaceMediaReportJpaEntity(
                UUID.randomUUID(), mediaId, UUID.randomUUID(), "SPAM", "Foto irrelevante", "PENDING", Instant.now(), Instant.now()
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        when(userRepository.findById(modUserId)).thenReturn(Optional.of(moderator));
        when(placeMediaReportJpaRepository.findByPlaceMediaId(mediaId)).thenReturn(List.of(report));

        placeMediaService.deleteMedia(modUserId, placeId, mediaId);

        assertEquals(ReviewMediaStatus.REMOVED, media.getStatus());
        assertEquals("ACCEPTED", report.getStatus());
        verify(placeMediaReportJpaRepository).save(report);
    }

    @Test
    @DisplayName("14. reportMedia salva denúncia e rejeita denúncia duplicada com 409 CONFLICT")
    void shouldReportMediaAndRejectDuplicateReport() {
        UUID mediaId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                ReviewMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        when(placeRepository.findById(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        when(placeMediaReportJpaRepository.existsByPlaceMediaIdAndReporterUserId(mediaId, reporterId)).thenReturn(false);
        when(placeMediaReportJpaRepository.countByPlaceMediaIdAndStatus(mediaId, "PENDING")).thenReturn(1L);

        placeMediaService.reportMedia(reporterId, placeId, mediaId, "INAPPROPRIATE", "Conteúdo ofensivo");

        verify(placeMediaReportJpaRepository).save(any(PlaceMediaReportJpaEntity.class));

        // Segunda tentativa pelo mesmo usuário
        when(placeMediaReportJpaRepository.existsByPlaceMediaIdAndReporterUserId(mediaId, reporterId)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                placeMediaService.reportMedia(reporterId, placeId, mediaId, "INAPPROPRIATE", "Novamente")
        );

        assertEquals("ALREADY_REPORTED", ex.getErrorCode());
    }

    @Test
    @DisplayName("15. reportMedia atinge limiar de 3 denúncias e coloca a fotografia em UNDER_REVIEW")
    void shouldTransitionMediaToUnderReviewWhenPendingReportsReachThreshold() {
        UUID mediaId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                ReviewMediaStatus.ACTIVE, true, null, null, Instant.now(), Instant.now()
        );

        UUID nextActiveId = UUID.randomUUID();
        PlaceMedia nextActive = new PlaceMedia(
                nextActiveId, placeId, userId, "places/" + placeId + "/" + nextActiveId + "/image.png",
                ReviewMediaType.IMAGE, "image/png", 1024L, 640, 480,
                ReviewMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        when(placeRepository.findById(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        when(placeMediaReportJpaRepository.existsByPlaceMediaIdAndReporterUserId(mediaId, reporterId)).thenReturn(false);
        when(placeMediaReportJpaRepository.countByPlaceMediaIdAndStatus(mediaId, "PENDING")).thenReturn(3L);
        when(placeMediaRepository.findFirstActiveByPlaceId(placeId)).thenReturn(Optional.of(nextActive));

        placeMediaService.reportMedia(reporterId, placeId, mediaId, "INAPPROPRIATE", "Terceira denúncia");

        assertEquals(ReviewMediaStatus.UNDER_REVIEW, media.getStatus());
        assertTrue(nextActive.isPrimary());
        verify(placeMediaRepository).save(media);
        verify(placeMediaRepository).save(nextActive);
    }

    @Test
    @DisplayName("16. Proibição de autodenúncia: autor da foto não pode denunciar própria foto")
    void shouldPreventAuthorFromReportingOwnMedia() {
        UUID mediaId = UUID.randomUUID();
        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                ReviewMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findById(mediaId)).thenReturn(Optional.of(media));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                placeMediaService.reportMedia(userId, placeId, mediaId, "SPAM", "Auto denúncia")
        );

        assertEquals("SELF_REPORT_FORBIDDEN", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("17. decideReport com ACCEPT remove foto, marca relatório ACCEPTED e elege nova capa")
    void shouldAcceptReportAndRemoveMedia() {
        UUID modId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                ReviewMediaStatus.ACTIVE, true, null, null, Instant.now(), Instant.now()
        );

        PlaceMedia nextActive = new PlaceMedia(
                UUID.randomUUID(), placeId, userId, "places/" + placeId + "/next/image.png",
                ReviewMediaType.IMAGE, "image/png", 1024L, 640, 480,
                ReviewMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        when(placeMediaReportJpaRepository.findById(reportId)).thenReturn(Optional.of(
                new com.rewit.infrastructure.persistence.entity.PlaceMediaReportJpaEntity(
                        reportId, mediaId, UUID.randomUUID(), "SPAM", "Propaganda", "PENDING", Instant.now(), Instant.now()
                )
        ));
        when(placeMediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        when(placeMediaRepository.findFirstActiveByPlaceId(placeId)).thenReturn(Optional.of(nextActive));
        when(placeMediaReportJpaRepository.findByPlaceMediaId(mediaId)).thenReturn(List.of());

        var res = placeMediaService.decideReport(modId, reportId, "ACCEPT", "Conteúdo comercial inadequado");

        assertEquals("ACCEPTED", res.status());
        assertEquals(ReviewMediaStatus.REMOVED, media.getStatus());
        assertTrue(nextActive.isPrimary());
        verify(placeMediaRepository).save(media);
        verify(placeMediaRepository).save(nextActive);
    }

    @Test
    @DisplayName("18. decideReport com REJECT restaura foto em UNDER_REVIEW para ACTIVE se contagem < 3")
    void shouldRejectReportAndRestoreMediaFromUnderReview() {
        UUID modId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                ReviewMediaStatus.UNDER_REVIEW, false, null, null, Instant.now(), Instant.now()
        );

        when(placeMediaReportJpaRepository.findById(reportId)).thenReturn(Optional.of(
                new com.rewit.infrastructure.persistence.entity.PlaceMediaReportJpaEntity(
                        reportId, mediaId, UUID.randomUUID(), "SPAM", "Propaganda", "PENDING", Instant.now(), Instant.now()
                )
        ));
        when(placeMediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        when(placeMediaReportJpaRepository.countByPlaceMediaIdAndStatus(mediaId, "PENDING")).thenReturn(1L);
        when(placeMediaRepository.findPrimaryByPlaceId(placeId)).thenReturn(Optional.empty());
        when(placeMediaRepository.findFirstActiveByPlaceId(placeId)).thenReturn(Optional.of(media));

        var res = placeMediaService.decideReport(modId, reportId, "REJECT", "Denúncia infundada");

        assertEquals("REJECTED", res.status());
        assertEquals(ReviewMediaStatus.ACTIVE, media.getStatus());
        assertTrue(media.isPrimary());
        verify(placeMediaRepository).save(media);
    }

    @Test
    @DisplayName("19. restoreMediaByModerator restaura foto se objeto existir no storage e falha se purgado")
    void shouldRestoreMediaWhenStorageExistsAndFailWhenPurged() {
        UUID modId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                ReviewMediaStatus.REMOVED, false, null, null, Instant.now(), Instant.now()
        );

        when(placeMediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        when(objectStoragePort.exists(media.getObjectKey())).thenReturn(true);
        when(placeMediaRepository.findPrimaryByPlaceId(placeId)).thenReturn(Optional.empty());
        when(placeMediaRepository.findFirstActiveByPlaceId(placeId)).thenReturn(Optional.of(media));

        var res = placeMediaService.restoreMediaByModerator(modId, placeId, mediaId, "Restaurando");
        assertEquals("ACTIVE", res.status());
        assertTrue(media.isActive());

        // Quando storage já foi purgado:
        when(objectStoragePort.exists(media.getObjectKey())).thenReturn(false);
        BusinessException ex = assertThrows(BusinessException.class, () ->
                placeMediaService.restoreMediaByModerator(modId, placeId, mediaId, "Restaurando novamente")
        );
        assertEquals("STORAGE_OBJECT_PURGED", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }
}
