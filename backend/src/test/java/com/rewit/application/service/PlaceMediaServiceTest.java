package com.rewit.application.service;

import com.rewit.application.dto.common.PageResult;
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
import com.rewit.domain.enums.PlaceMediaStatus;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.enums.Role;
import com.rewit.domain.enums.VerificationStatus;
import com.rewit.domain.model.BusinessAccount;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceMedia;
import com.rewit.domain.model.PlaceMediaReport;
import com.rewit.domain.model.User;
import com.rewit.application.port.PlaceMediaModerationAuditLogRepository;
import com.rewit.application.port.PlaceMediaReportRepository;
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
import java.security.MessageDigest;
import java.util.HexFormat;
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
    private PlaceMediaReportRepository placeMediaReportRepository;

    @Mock
    private PlaceMediaModerationAuditLogRepository placeMediaModerationAuditLogRepository;

    private PlaceMediaService placeMediaService;
    private UUID userId;
    private UUID placeId;
    private Place activePlace;
    private UUID moderatorId;
    private User moderatorUser;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        placeId = UUID.randomUUID();
        moderatorId = UUID.randomUUID();
        moderatorUser = User.rehydrate(
                moderatorId, "moderator@rewit.com", "hash", AuthProvider.LOCAL, null,
                AccountStatus.ACTIVE, true, Role.MODERATOR, null, Instant.now(), Instant.now()
        );
        lenient().when(userRepository.findById(moderatorId)).thenReturn(Optional.of(moderatorUser));

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
                placeMediaReportRepository,
                placeMediaModerationAuditLogRepository
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
                PlaceMediaStatus.ACTIVE, true, idempotencyKey, expectedHash, Instant.now(), Instant.now()
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
                PlaceMediaStatus.ACTIVE, true, idempotencyKey, "different_sha256_hash", Instant.now(), Instant.now()
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
    @DisplayName("10.1. Rejeita upload com chave de idempotência superior a 128 caracteres")
    void shouldRejectIdempotencyKeyLongerThan128Chars() throws IOException {
        byte[] pngBytes = createValidPngBytes();
        String oversizedKey = "A".repeat(129);

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                placeMediaService.uploadMedia(new UploadPlaceMediaCommand(placeId, userId, pngBytes, "foto.png", oversizedKey))
        );

        assertEquals("INVALID_IDEMPOTENCY_KEY", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
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
                PlaceMediaStatus.ACTIVE, true, null, null, Instant.now(), Instant.now()
        );

        PlaceMedia newPrimary = new PlaceMedia(
                newPrimaryId, placeId, userId, "places/" + placeId + "/" + newPrimaryId + "/image.png",
                ReviewMediaType.IMAGE, "image/png", 1024L, 640, 480,
                PlaceMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(newPrimaryId)).thenReturn(Optional.of(newPrimary));
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
                PlaceMediaStatus.ACTIVE, true, null, null, Instant.now(), Instant.now()
        );

        PlaceMedia nextOldestMedia = new PlaceMedia(
                nextOldestId, placeId, userId, "places/" + placeId + "/" + nextOldestId + "/image.png",
                ReviewMediaType.IMAGE, "image/png", 1024L, 640, 480,
                PlaceMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(primaryId)).thenReturn(Optional.of(primaryMedia));
        when(placeMediaRepository.findFirstActiveByPlaceId(placeId)).thenReturn(Optional.of(nextOldestMedia));

        placeMediaService.deleteMedia(userId, placeId, primaryId);

        assertEquals(PlaceMediaStatus.REMOVED, primaryMedia.getStatus());
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
                PlaceMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        User moderator = User.rehydrate(
                modUserId, "mod@rewit.com", "hash", AuthProvider.LOCAL, null,
                AccountStatus.ACTIVE, true, Role.MODERATOR, null, Instant.now(), Instant.now()
        );

        PlaceMediaReport report = new PlaceMediaReport(
                UUID.randomUUID(), mediaId, UUID.randomUUID(), "SPAM", "Foto irrelevante"
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));
        when(userRepository.findById(moderatorId)).thenReturn(Optional.of(moderatorUser));
        when(placeMediaReportRepository.findByPlaceMediaIdForUpdate(mediaId)).thenReturn(List.of(report));

        placeMediaService.deleteMedia(moderatorId, placeId, mediaId);

        assertEquals(PlaceMediaStatus.REMOVED, media.getStatus());
        var captor = org.mockito.ArgumentCaptor.forClass(PlaceMediaReport.class);
        verify(placeMediaReportRepository).save(captor.capture());
        assertEquals("AUTO_RESOLVED", captor.getValue().getStatus());
        assertNull(captor.getValue().getDecidedByUserId());
    }

    @Test
    @DisplayName("14. reportMedia salva denúncia e rejeita denúncia duplicada com 409 CONFLICT")
    void shouldReportMediaAndRejectDuplicateReport() {
        UUID mediaId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));
        when(placeMediaReportRepository.existsByPlaceMediaIdAndReporterUserId(mediaId, reporterId)).thenReturn(false);
        when(placeMediaReportRepository.countByPlaceMediaIdAndStatus(mediaId, "PENDING")).thenReturn(1L);

        placeMediaService.reportMedia(reporterId, placeId, mediaId, "INAPPROPRIATE", "Conteúdo ofensivo");

        verify(placeMediaReportRepository).save(any(PlaceMediaReport.class));

        // Segunda tentativa pelo mesmo usuário
        when(placeMediaReportRepository.existsByPlaceMediaIdAndReporterUserId(mediaId, reporterId)).thenReturn(true);

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
                PlaceMediaStatus.ACTIVE, true, null, null, Instant.now(), Instant.now()
        );

        UUID nextActiveId = UUID.randomUUID();
        PlaceMedia nextActive = new PlaceMedia(
                nextActiveId, placeId, userId, "places/" + placeId + "/" + nextActiveId + "/image.png",
                ReviewMediaType.IMAGE, "image/png", 1024L, 640, 480,
                PlaceMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));
        when(placeMediaReportRepository.existsByPlaceMediaIdAndReporterUserId(mediaId, reporterId)).thenReturn(false);
        when(placeMediaReportRepository.countByPlaceMediaIdAndStatus(mediaId, PlaceMediaReport.STATUS_PENDING)).thenReturn(3L);
        when(placeMediaRepository.findFirstActiveByPlaceId(placeId)).thenReturn(Optional.of(nextActive));

        placeMediaService.reportMedia(reporterId, placeId, mediaId, "INAPPROPRIATE", "Terceira denúncia");

        assertEquals(PlaceMediaStatus.UNDER_REVIEW, media.getStatus());
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
                PlaceMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                placeMediaService.reportMedia(userId, placeId, mediaId, "SPAM", "Auto denúncia")
        );

        assertEquals("SELF_REPORT_FORBIDDEN", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("17. decideReport com ACCEPT remove foto, marca relatório ACCEPTED e elege nova capa")
    void shouldAcceptReportAndRemoveMedia() {
        UUID modId = moderatorId;
        UUID reportId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.ACTIVE, true, null, null, Instant.now(), Instant.now()
        );

        PlaceMedia nextActive = new PlaceMedia(
                UUID.randomUUID(), placeId, userId, "places/" + placeId + "/next/image.png",
                ReviewMediaType.IMAGE, "image/png", 1024L, 640, 480,
                PlaceMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        var reportEntity = new PlaceMediaReport(
                reportId, mediaId, UUID.randomUUID(), "SPAM", "Propaganda",
                PlaceMediaReport.STATUS_PENDING, Instant.now(), Instant.now(), null, null, null
        );

        when(placeMediaReportRepository.findById(reportId)).thenReturn(Optional.of(reportEntity));
        when(placeMediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaReportRepository.findByIdForUpdate(reportId)).thenReturn(Optional.of(reportEntity));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));
        when(placeMediaRepository.findFirstActiveByPlaceId(placeId)).thenReturn(Optional.of(nextActive));
        when(placeMediaReportRepository.findByPlaceMediaIdForUpdate(mediaId)).thenReturn(List.of());

        var res = placeMediaService.decideReport(modId, reportId, "ACCEPT", "Conteúdo comercial inadequado");

        assertEquals("ACCEPTED", res.status());
        assertEquals(PlaceMediaStatus.REMOVED, media.getStatus());
        assertTrue(nextActive.isPrimary());
        verify(placeMediaRepository).save(media);
        verify(placeMediaRepository).save(nextActive);
    }

    @Test
    @DisplayName("18a. decideReport com REJECT mantém foto em UNDER_REVIEW se ainda existirem outras denúncias pendentes")
    void shouldRejectReportAndKeepMediaInUnderReviewWhenOtherReportsRemainPending() {
        UUID modId = moderatorId;
        UUID reportId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.UNDER_REVIEW, false, null, null, Instant.now(), Instant.now()
        );

        var reportEntity = new PlaceMediaReport(
                reportId, mediaId, UUID.randomUUID(), "SPAM", "Propaganda",
                PlaceMediaReport.STATUS_PENDING, Instant.now(), Instant.now(), null, null, null
        );

        when(placeMediaReportRepository.findById(reportId)).thenReturn(Optional.of(reportEntity));
        when(placeMediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaReportRepository.findByIdForUpdate(reportId)).thenReturn(Optional.of(reportEntity));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));

        var res = placeMediaService.decideReport(modId, reportId, "REJECT", "Denúncia infundada");

        assertEquals("REJECTED", res.status());
        assertEquals(PlaceMediaStatus.UNDER_REVIEW, media.getStatus());
        assertFalse(media.isActive());
        verify(placeMediaRepository, never()).save(media);
    }

    @Test
    @DisplayName("18b. decideReport com REJECT rejeita a denúncia sem reativação implícita da foto, mantendo UNDER_REVIEW")
    void shouldRejectReportWithoutImplicitReactivationEvenWhenNoOtherPendingReports() {
        UUID modId = moderatorId;
        UUID reportId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.UNDER_REVIEW, false, null, null, Instant.now(), Instant.now()
        );

        var reportEntity = new PlaceMediaReport(
                reportId, mediaId, UUID.randomUUID(), "SPAM", "Propaganda",
                PlaceMediaReport.STATUS_PENDING, Instant.now(), Instant.now(), null, null, null
        );

        when(placeMediaReportRepository.findById(reportId)).thenReturn(Optional.of(reportEntity));
        when(placeMediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaReportRepository.findByIdForUpdate(reportId)).thenReturn(Optional.of(reportEntity));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));

        var res = placeMediaService.decideReport(modId, reportId, "REJECT", "Denúncia infundada");

        assertEquals("REJECTED", res.status());
        assertEquals(PlaceMediaStatus.UNDER_REVIEW, media.getStatus());
        assertFalse(media.isActive());
        verify(placeMediaRepository, never()).save(media);
    }

    @Test
    @DisplayName("19. restoreMediaByModerator restaura foto se objeto existir no storage e falha se purgado")
    void shouldRestoreMediaWhenStorageExistsAndFailWhenPurged() {
        UUID modId = moderatorId;
        UUID mediaId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.REMOVED, false, null, null, Instant.now(), Instant.now()
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));
        when(placeMediaRepository.countActiveByPlaceId(placeId)).thenReturn(2L);
        when(objectStoragePort.exists(media.getObjectKey())).thenReturn(true);
        when(placeMediaRepository.findPrimaryByPlaceId(placeId)).thenReturn(Optional.empty());
        when(placeMediaRepository.findFirstActiveByPlaceId(placeId)).thenReturn(Optional.of(media));

        var res = placeMediaService.restoreMediaByModerator(modId, placeId, mediaId, "Restaurando");
        assertEquals("ACTIVE", res.status());
        assertTrue(media.isActive());

        // Quando storage já foi purgado:
        UUID purgedMediaId = UUID.randomUUID();
        PlaceMedia purgedMedia = new PlaceMedia(
                purgedMediaId, placeId, userId, "places/" + placeId + "/" + purgedMediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.REMOVED, false, null, null, Instant.now(), Instant.now()
        );
        when(placeMediaRepository.findByIdForUpdate(purgedMediaId)).thenReturn(Optional.of(purgedMedia));
        when(objectStoragePort.exists(purgedMedia.getObjectKey())).thenReturn(false);
        BusinessException ex = assertThrows(BusinessException.class, () ->
                placeMediaService.restoreMediaByModerator(modId, placeId, purgedMediaId, "Restaurando purgada")
        );
        assertEquals("STORAGE_OBJECT_PURGED", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("20. restoreMediaByModerator falha com 409 quando local já atingiu o limite de 5 fotos ativas")
    void shouldFailRestoreWhenActiveMediaLimitReached() {
        UUID modId = moderatorId;
        UUID mediaId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.REMOVED, false, null, null, Instant.now(), Instant.now()
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));
        when(objectStoragePort.exists(media.getObjectKey())).thenReturn(true);
        when(placeMediaRepository.countActiveByPlaceId(placeId)).thenReturn(5L);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                placeMediaService.restoreMediaByModerator(modId, placeId, mediaId, "Tentativa de exceder limite")
        );

        assertEquals("PLACE_MEDIA_LIMIT_EXCEEDED", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertFalse(media.isActive());
    }

    @Test
    @DisplayName("21. downloadMediaForModerator permite download privado de fotos em UNDER_REVIEW e REMOVED")
    void shouldAllowModeratorToDownloadUnderReviewOrRemovedMedia() {
        UUID modId = moderatorId;
        UUID mediaId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.UNDER_REVIEW, false, null, null, Instant.now(), Instant.now()
        );

        byte[] fakeData = new byte[]{1, 2, 3, 4};
        when(placeMediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        when(objectStoragePort.get(media.getObjectKey())).thenReturn(fakeData);

        var result = placeMediaService.downloadMediaForModerator(modId, placeId, mediaId);

        assertNotNull(result);
        assertArrayEquals(fakeData, result.bytes());
        assertEquals("image/jpeg", result.mimeType());
        assertFalse(result.publiclyCacheable());
    }

    @Test
    @DisplayName("22. restoreMediaByModerator rejeita restauração de foto removida pelo próprio autor com CANNOT_RESTORE_AUTHOR_REMOVED_MEDIA")
    void shouldRejectRestorationWhenMediaRemovedByAuthor() {
        UUID modId = moderatorId;
        UUID mediaId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );
        media.markRemovedByAuthor();

        when(userRepository.findById(modId)).thenReturn(Optional.of(moderatorUser));
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                placeMediaService.restoreMediaByModerator(modId, placeId, mediaId, "Tentativa de restaurar")
        );

        assertEquals("CANNOT_RESTORE_AUTHOR_REMOVED_MEDIA", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertTrue(media.isRemoved());
    }

    @Test
    @DisplayName("23. deleteMedia registra AUTHOR_REMOVE na auditoria quando excluída pelo próprio autor")
    void shouldRecordAuthorRemoveAuditLogWhenAuthorDeletesMedia() {
        UUID mediaId = UUID.randomUUID();
        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.ACTIVE, true, null, null, Instant.now(), Instant.now()
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));
        when(placeMediaRepository.findFirstActiveByPlaceId(placeId)).thenReturn(Optional.empty());

        placeMediaService.deleteMedia(userId, placeId, mediaId);

        assertTrue(media.isRemoved());
        assertEquals(com.rewit.domain.enums.RemovalSource.AUTHOR, media.getRemovalSource());

        org.mockito.ArgumentCaptor<com.rewit.domain.model.PlaceMediaModerationAuditLog> captor =
                org.mockito.ArgumentCaptor.forClass(com.rewit.domain.model.PlaceMediaModerationAuditLog.class);
        org.mockito.Mockito.verify(placeMediaModerationAuditLogRepository).save(captor.capture());
        assertEquals(com.rewit.domain.enums.PlaceMediaModerationAction.AUTHOR_REMOVE, captor.getValue().getAction());
        assertEquals(mediaId, captor.getValue().getPlaceMediaId());
        assertEquals(userId, captor.getValue().getActorUserId());
    }

    @Test
    @DisplayName("24. decideReport registra o motivo real da denúncia e o reportId na auditoria")
    void shouldRecordReasonAndReportIdInAuditLogOnDecideReport() {
        UUID modId = moderatorId;
        UUID mediaId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.UNDER_REVIEW, false, null, null, Instant.now(), Instant.now()
        );
        PlaceMediaReport report = new PlaceMediaReport(
                reportId, mediaId, UUID.randomUUID(), "SPAM_OU_PROPAGANDA", "Foto de anúncio sem relação"
        );

        when(userRepository.findById(modId)).thenReturn(Optional.of(moderatorUser));
        when(placeMediaReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(placeMediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));
        when(placeMediaReportRepository.findByIdForUpdate(reportId)).thenReturn(Optional.of(report));
        when(placeMediaReportRepository.findByPlaceMediaIdForUpdate(mediaId)).thenReturn(List.of(report));

        placeMediaService.decideReport(modId, reportId, "ACCEPT", "Foto confirmada como imprópria");

        org.mockito.ArgumentCaptor<com.rewit.domain.model.PlaceMediaModerationAuditLog> captor =
                org.mockito.ArgumentCaptor.forClass(com.rewit.domain.model.PlaceMediaModerationAuditLog.class);
        org.mockito.Mockito.verify(placeMediaModerationAuditLogRepository).save(captor.capture());
        assertEquals(com.rewit.domain.enums.PlaceMediaModerationAction.ACCEPT_REPORT, captor.getValue().getAction());
        assertEquals("SPAM_OU_PROPAGANDA", captor.getValue().getReason());
        assertEquals(reportId, captor.getValue().getReportId());
    }

    @Test
    @DisplayName("25. Falha na gravação de auditoria propaga exceção sem silenciar (atômica e obrigatória)")
    void shouldPropagateExceptionWhenAuditLogSaveFails() {
        UUID modId = moderatorId;
        UUID mediaId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        when(userRepository.findById(modId)).thenReturn(Optional.of(moderatorUser));
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));
        when(placeMediaReportRepository.findByPlaceMediaIdForUpdate(mediaId)).thenReturn(List.of());

        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("Violação de integridade na auditoria"))
                .when(placeMediaModerationAuditLogRepository).save(any());

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                placeMediaService.removeMediaByModerator(modId, placeId, mediaId, "MOTIVO", "Notas")
        );
    }

    @Test
    @DisplayName("26. listUnderReviewQueue lista mídias sob revisão com contagem de denúncias")
    void shouldListUnderReviewQueueWithReportsAggregation() {
        UUID modId = moderatorId;
        UUID mediaId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.UNDER_REVIEW, false, null, null, Instant.now(), Instant.now()
        );

        when(userRepository.findById(modId)).thenReturn(Optional.of(moderatorUser));
        when(placeMediaRepository.findByStatus(eq(PlaceMediaStatus.UNDER_REVIEW), eq(0), eq(20)))
                .thenReturn(PageResult.of(List.of(media), 0, 20, 1));
        when(placeMediaReportRepository.countByPlaceMediaIdAndStatus(eq(mediaId), eq("PENDING"))).thenReturn(3L);
        when(placeMediaReportRepository.findByPlaceMediaId(eq(mediaId))).thenReturn(List.of(
                new PlaceMediaReport(UUID.randomUUID(), mediaId, UUID.randomUUID(), "SPAM", "Detail 1"),
                new PlaceMediaReport(UUID.randomUUID(), mediaId, UUID.randomUUID(), "SPAM", "Detail 2"),
                new PlaceMediaReport(UUID.randomUUID(), mediaId, UUID.randomUUID(), "SPAM", "Detail 3")
        ));

        PageResult<com.rewit.application.dto.media.PlaceMediaModerationDtos.PlaceMediaUnderReviewView> queue =
                placeMediaService.listUnderReviewQueue(modId, 0, 20);

        assertNotNull(queue);
        assertEquals(1, queue.content().size());
        assertEquals(mediaId, queue.content().get(0).media().getId());
        assertEquals(3L, queue.content().get(0).pendingReportsCount());
        assertEquals(3, queue.content().get(0).reports().size());
    }

    @Test
    @DisplayName("27. decideReport com ACCEPT e múltiplas denúncias: grava auditoria para principal e irmãs com IDs e motivos corretos")
    void shouldAuditAllReportsWhenAcceptingInBatch() {
        UUID modId = moderatorId;
        UUID report1Id = UUID.randomUUID();
        UUID report2Id = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.UNDER_REVIEW, false, null, null, Instant.now(), Instant.now()
        );

        var report1 = new PlaceMediaReport(
                report1Id, mediaId, UUID.randomUUID(), "SPAM_1", "Propaganda 1",
                PlaceMediaReport.STATUS_PENDING, Instant.now(), Instant.now(), null, null, null
        );
        var report2 = new PlaceMediaReport(
                report2Id, mediaId, UUID.randomUUID(), "OFENSIVO_2", "Conteúdo ofensivo 2",
                PlaceMediaReport.STATUS_PENDING, Instant.now(), Instant.now(), null, null, null
        );

        when(userRepository.findById(modId)).thenReturn(Optional.of(moderatorUser));
        when(placeMediaReportRepository.findById(report1Id)).thenReturn(Optional.of(report1));
        when(placeMediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));
        when(placeMediaReportRepository.findByIdForUpdate(report1Id)).thenReturn(Optional.of(report1));
        when(placeMediaReportRepository.findByPlaceMediaIdForUpdate(mediaId)).thenReturn(List.of(report1, report2));

        placeMediaService.decideReport(modId, report1Id, "ACCEPT", "Foto confirmada imprópria");

        org.mockito.ArgumentCaptor<com.rewit.domain.model.PlaceMediaModerationAuditLog> captor =
                org.mockito.ArgumentCaptor.forClass(com.rewit.domain.model.PlaceMediaModerationAuditLog.class);
        org.mockito.Mockito.verify(placeMediaModerationAuditLogRepository, org.mockito.Mockito.times(2)).save(captor.capture());

        List<com.rewit.domain.model.PlaceMediaModerationAuditLog> logs = captor.getAllValues();
        assertEquals(2, logs.size());

        var log1 = logs.stream().filter(l -> report1Id.equals(l.getReportId())).findFirst().orElseThrow();
        assertEquals(com.rewit.domain.enums.PlaceMediaModerationAction.ACCEPT_REPORT, log1.getAction());
        assertEquals(com.rewit.domain.model.PlaceMediaModerationAuditLog.ActorType.USER, log1.getActorType());
        assertEquals(modId, log1.getActorUserId());
        assertEquals("SPAM_1", log1.getReason());
        assertEquals("Foto confirmada imprópria", log1.getNotes());

        var log2 = logs.stream().filter(l -> report2Id.equals(l.getReportId())).findFirst().orElseThrow();
        assertEquals(com.rewit.domain.enums.PlaceMediaModerationAction.AUTO_RESOLVE_REPORT, log2.getAction());
        assertEquals(com.rewit.domain.model.PlaceMediaModerationAuditLog.ActorType.SYSTEM, log2.getActorType());
        assertNull(log2.getActorUserId());
        assertEquals("OFENSIVO_2", log2.getReason());
        assertTrue(log2.getNotes().contains("Resolução automática em lote"));
    }

    @Test
    @DisplayName("28. reportMedia: ao atingir 3 denúncias, transiciona para UNDER_REVIEW e grava auditoria AUTO_QUARANTINE")
    void shouldRecordAutoQuarantineAuditLogWhenThresholdReached() {
        UUID reporter = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.ACTIVE, true, null, null, Instant.now(), Instant.now()
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));
        when(placeMediaReportRepository.existsByPlaceMediaIdAndReporterUserId(mediaId, reporter)).thenReturn(false);
        when(placeMediaReportRepository.countByPlaceMediaIdAndStatus(mediaId, PlaceMediaReport.STATUS_PENDING)).thenReturn(3L);

        placeMediaService.reportMedia(reporter, placeId, mediaId, "SPAM", "Terceira denúncia");

        assertEquals(PlaceMediaStatus.UNDER_REVIEW, media.getStatus());
        assertFalse(media.isPrimary());

        org.mockito.ArgumentCaptor<com.rewit.domain.model.PlaceMediaModerationAuditLog> captor =
                org.mockito.ArgumentCaptor.forClass(com.rewit.domain.model.PlaceMediaModerationAuditLog.class);
        org.mockito.Mockito.verify(placeMediaModerationAuditLogRepository).save(captor.capture());
        assertEquals(com.rewit.domain.enums.PlaceMediaModerationAction.AUTO_QUARANTINE, captor.getValue().getAction());
        assertEquals(mediaId, captor.getValue().getPlaceMediaId());
        assertEquals(com.rewit.domain.model.PlaceMediaModerationAuditLog.ActorType.SYSTEM, captor.getValue().getActorType());
        assertNull(captor.getValue().getActorUserId());
    }

    @Test
    @DisplayName("29. deleteMedia por responsável do local: classifica como PLACE_MANAGER / BUSINESS_OWNER e audita")
    void shouldHandleDeletionByPlaceManager() {
        UUID managerId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();

        // Local criado por managerId
        Place placeCreatedByManager = new Place(
                placeId, "Local do Gerente", "local-gerente", "RESTAURANTE", "Desc", "End",
                "100", "Bairro", "Curitiba", "PR", "BR", -25.4, -49.2, 50,
                "USER", false, null, managerId, "ACTIVE"
        );

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        PlaceMediaReport pendingReport = new PlaceMediaReport(
                reportId, mediaId, UUID.randomUUID(), "CONTEUDO_INADEQUADO", "Detalhes",
                PlaceMediaReport.STATUS_PENDING, Instant.now(), Instant.now(), null, null, null
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(placeCreatedByManager));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));
        when(placeMediaReportRepository.findByPlaceMediaIdForUpdate(mediaId)).thenReturn(List.of(pendingReport));

        placeMediaService.deleteMedia(managerId, placeId, mediaId);

        assertEquals(PlaceMediaStatus.REMOVED, media.getStatus());
        assertTrue(media.getRemovalSource() == com.rewit.domain.enums.RemovalSource.BUSINESS_OWNER
                || media.getRemovalSource() == com.rewit.domain.enums.RemovalSource.PLACE_MANAGER);

        org.mockito.ArgumentCaptor<com.rewit.domain.model.PlaceMediaModerationAuditLog> captor =
                org.mockito.ArgumentCaptor.forClass(com.rewit.domain.model.PlaceMediaModerationAuditLog.class);
        org.mockito.Mockito.verify(placeMediaModerationAuditLogRepository, org.mockito.Mockito.times(2)).save(captor.capture());

        var removalLog = captor.getAllValues().get(0);
        assertTrue(removalLog.getAction() == com.rewit.domain.enums.PlaceMediaModerationAction.BUSINESS_OWNER_REMOVE
                || removalLog.getAction() == com.rewit.domain.enums.PlaceMediaModerationAction.PLACE_MANAGER_REMOVE);
        assertEquals(managerId, removalLog.getActorUserId());

        var reportResolutionLog = captor.getAllValues().get(1);
        assertEquals(com.rewit.domain.enums.PlaceMediaModerationAction.AUTO_RESOLVE_REPORT, reportResolutionLog.getAction());
        assertEquals(com.rewit.domain.model.PlaceMediaModerationAuditLog.ActorType.SYSTEM, reportResolutionLog.getActorType());
        assertNull(reportResolutionLog.getActorUserId());
        assertEquals("CONTEUDO_INADEQUADO", reportResolutionLog.getReason());
        assertEquals(reportId, reportResolutionLog.getReportId());
    }

    @Test
    @DisplayName("30. restoreMediaByModerator: não permite restaurar foto removida por responsável do local ou legada")
    void shouldRejectRestorationOfPlaceManagerOrLegacyRemovedMedia() {
        UUID modId = moderatorId;
        UUID mediaId = UUID.randomUUID();

        PlaceMedia mediaManager = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.REMOVED, false, null, null, Instant.now(), Instant.now(),
                null, Instant.now(), com.rewit.domain.enums.RemovalSource.BUSINESS_OWNER
        );

        when(userRepository.findById(modId)).thenReturn(Optional.of(moderatorUser));
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(mediaManager));

        var exManager = assertThrows(BusinessException.class, () ->
                placeMediaService.restoreMediaByModerator(modId, placeId, mediaId, "Tentativa de restaurar foto de gerente")
        );
        assertEquals("CANNOT_RESTORE_PLACE_MANAGER_REMOVED_MEDIA", exManager.getErrorCode());

        PlaceMedia mediaLegacy = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.REMOVED, false, null, null, Instant.now(), Instant.now(),
                null, Instant.now(), com.rewit.domain.enums.RemovalSource.LEGACY
        );
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(mediaLegacy));

        var exLegacy = assertThrows(BusinessException.class, () ->
                placeMediaService.restoreMediaByModerator(modId, placeId, mediaId, "Tentativa de restaurar foto legada")
        );
        assertEquals("CANNOT_RESTORE_LEGACY_REMOVED_MEDIA", exLegacy.getErrorCode());
    }

    @Test
    @DisplayName("31. uploadMedia: replay idempotente não consome cota do rate limiter")
    void shouldNotConsumeRateLimitOnIdempotentReplay() throws Exception {
        UUID mediaId = UUID.randomUUID();
        byte[] pngBytes = createValidPngBytes();
        byte[] sanitizedBytes = new ImageSanitizer().sanitize(pngBytes).bytes();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sanitizedBytes));

        PlaceMedia existingMedia = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.png",
                ReviewMediaType.IMAGE, "image/png", (long) pngBytes.length, 32, 24,
                PlaceMediaStatus.ACTIVE, true, "idem-key-123", sha, Instant.now(), Instant.now()
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdempotencyKey(userId, placeId, "idem-key-123"))
                .thenReturn(Optional.of(existingMedia));

        var cmd = new UploadPlaceMediaCommand(placeId, userId, pngBytes, "foto.png", "idem-key-123");
        var view = placeMediaService.uploadMedia(cmd);

        assertNotNull(view);
        assertEquals(mediaId, view.id());
        // Rate limiter NUNCA deve ter sido invocado para o replay idempotente
        verify(rateLimiter, never()).acquireOrThrow(any(), any());
    }

    @Test
    @DisplayName("32. restoreMediaByModerator: rejeita restauração se fotografia já estiver ACTIVE (HTTP 409 MEDIA_ALREADY_ACTIVE)")
    void shouldRejectRestorationWhenMediaAlreadyActive() {
        UUID modId = moderatorId;
        UUID mediaId = UUID.randomUUID();

        PlaceMedia activeMedia = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        when(userRepository.findById(modId)).thenReturn(Optional.of(moderatorUser));
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(activeMedia));

        var ex = assertThrows(BusinessException.class, () ->
                placeMediaService.restoreMediaByModerator(modId, placeId, mediaId, "Tentativa de restaurar foto ativa")
        );
        assertEquals("MEDIA_ALREADY_ACTIVE", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("33. restoreMediaByModerator: rejeita restauração se fotografia não estiver REMOVED (HTTP 409 MEDIA_NOT_REMOVED)")
    void shouldRejectRestorationWhenMediaNotRemoved() {
        UUID modId = moderatorId;
        UUID mediaId = UUID.randomUUID();

        PlaceMedia reviewMedia = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.UNDER_REVIEW, false, null, null, Instant.now(), Instant.now()
        );

        when(userRepository.findById(modId)).thenReturn(Optional.of(moderatorUser));
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(reviewMedia));

        var ex = assertThrows(BusinessException.class, () ->
                placeMediaService.restoreMediaByModerator(modId, placeId, mediaId, "Tentativa de restaurar foto em revisão")
        );
        assertEquals("MEDIA_NOT_REMOVED", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("34. deleteMedia por representante comercial verificado: classifica explicitamente como BUSINESS_OWNER e audita")
    void shouldDistinguishBusinessOwnerRemovalFromPlaceManager() {
        UUID businessAdminUserId = UUID.randomUUID();
        UUID businessId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        Place claimedPlace = new Place(
                placeId, "Local Claimed", "local-claimed", "RESTAURANTE", "Desc", "End",
                "100", "Bairro", "Curitiba", "PR", "BR", -25.4, -49.2, 50,
                "USER", true, businessId, UUID.randomUUID(), "ACTIVE"
        );

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        com.rewit.domain.model.BusinessAccount account = org.mockito.Mockito.mock(com.rewit.domain.model.BusinessAccount.class);
        when(account.isAdministeredBy(businessAdminUserId)).thenReturn(true);
        when(account.getVerificationStatus()).thenReturn(com.rewit.domain.enums.VerificationStatus.APPROVED);

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(claimedPlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));
        when(businessAccountRepository.findById(businessId)).thenReturn(Optional.of(account));
        when(placeMediaReportRepository.findByPlaceMediaIdForUpdate(mediaId)).thenReturn(List.of());

        placeMediaService.deleteMedia(businessAdminUserId, placeId, mediaId);

        assertEquals(PlaceMediaStatus.REMOVED, media.getStatus());
        assertEquals(com.rewit.domain.enums.RemovalSource.BUSINESS_OWNER, media.getRemovalSource());

        org.mockito.ArgumentCaptor<com.rewit.domain.model.PlaceMediaModerationAuditLog> captor =
                org.mockito.ArgumentCaptor.forClass(com.rewit.domain.model.PlaceMediaModerationAuditLog.class);
        verify(placeMediaModerationAuditLogRepository).save(captor.capture());
        assertEquals(com.rewit.domain.enums.PlaceMediaModerationAction.BUSINESS_OWNER_REMOVE, captor.getValue().getAction());
        assertEquals(businessAdminUserId, captor.getValue().getActorUserId());
        assertEquals(com.rewit.domain.model.PlaceMediaModerationAuditLog.ActorType.USER, captor.getValue().getActorType());
    }

    @Test
    @DisplayName("35. releaseReviewByModerator: libera com sucesso mídia UNDER_REVIEW com denúncias rejeitadas e grava auditoria RELEASE_REVIEW (R-1)")
    void shouldReleaseReviewSuccessfullyWhenReportsRejected() {
        UUID modId = moderatorId;
        UUID mediaId = UUID.randomUUID();

        PlaceMedia reviewMedia = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.UNDER_REVIEW, false, null, null, Instant.now(), Instant.now()
        );

        PlaceMediaReport report = new PlaceMediaReport(UUID.randomUUID(), mediaId, UUID.randomUUID(), "SPAM", "Spam");
        report.reject(modId, "Denúncia improcedente");

        when(userRepository.findById(modId)).thenReturn(Optional.of(moderatorUser));
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(reviewMedia));
        when(placeMediaReportRepository.findByPlaceMediaIdForUpdate(mediaId)).thenReturn(List.of(report));
        when(objectStoragePort.exists(reviewMedia.getObjectKey())).thenReturn(true);
        when(placeMediaRepository.countActiveByPlaceId(placeId)).thenReturn(1L);

        var result = placeMediaService.releaseReviewByModerator(modId, placeId, mediaId, "Liberando após análise");

        assertNotNull(result);
        assertEquals(PlaceMediaStatus.ACTIVE.name(), result.status());
        assertEquals(PlaceMediaStatus.ACTIVE, reviewMedia.getStatus());

        org.mockito.ArgumentCaptor<com.rewit.domain.model.PlaceMediaModerationAuditLog> captor =
                org.mockito.ArgumentCaptor.forClass(com.rewit.domain.model.PlaceMediaModerationAuditLog.class);
        verify(placeMediaModerationAuditLogRepository).save(captor.capture());
        assertEquals(com.rewit.domain.enums.PlaceMediaModerationAction.RELEASE_REVIEW, captor.getValue().getAction());
        assertEquals(modId, captor.getValue().getActorUserId());
    }

    @Test
    @DisplayName("36. releaseReviewByModerator: rejeita se fotografia já estiver ACTIVE (HTTP 409 MEDIA_ALREADY_ACTIVE)")
    void shouldRejectReleaseReviewWhenMediaAlreadyActive() {
        UUID modId = moderatorId;
        UUID mediaId = UUID.randomUUID();

        PlaceMedia activeMedia = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        when(userRepository.findById(modId)).thenReturn(Optional.of(moderatorUser));
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(activeMedia));

        var ex = assertThrows(BusinessException.class, () ->
                placeMediaService.releaseReviewByModerator(modId, placeId, mediaId, "Liberando foto ativa")
        );
        assertEquals("MEDIA_ALREADY_ACTIVE", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("37. releaseReviewByModerator: rejeita se fotografia estiver REMOVED (HTTP 409 CANNOT_RELEASE_REMOVED_MEDIA)")
    void shouldRejectReleaseReviewWhenMediaRemoved() {
        UUID modId = moderatorId;
        UUID mediaId = UUID.randomUUID();

        PlaceMedia removedMedia = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.REMOVED, false, null, null, Instant.now(), Instant.now()
        );

        when(userRepository.findById(modId)).thenReturn(Optional.of(moderatorUser));
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(removedMedia));

        var ex = assertThrows(BusinessException.class, () ->
                placeMediaService.releaseReviewByModerator(modId, placeId, mediaId, "Liberando foto removida")
        );
        assertEquals("CANNOT_RELEASE_REMOVED_MEDIA", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("38. releaseReviewByModerator: rejeita se ainda existirem denúncias pendentes (HTTP 409 REPORTS_STILL_PENDING)")
    void shouldRejectReleaseReviewWhenReportsPending() {
        UUID modId = moderatorId;
        UUID mediaId = UUID.randomUUID();

        PlaceMedia reviewMedia = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.UNDER_REVIEW, false, null, null, Instant.now(), Instant.now()
        );

        PlaceMediaReport pendingReport = new PlaceMediaReport(UUID.randomUUID(), mediaId, UUID.randomUUID(), "SPAM", "Spam");

        when(userRepository.findById(modId)).thenReturn(Optional.of(moderatorUser));
        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(reviewMedia));
        when(placeMediaReportRepository.findByPlaceMediaIdForUpdate(mediaId)).thenReturn(List.of(pendingReport));

        var ex = assertThrows(BusinessException.class, () ->
                placeMediaService.releaseReviewByModerator(modId, placeId, mediaId, "Liberando com pendências")
        );
        assertEquals("REPORTS_STILL_PENDING", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("39. AU-1: deleteMedia marca denúncias pendentes como AUTO_RESOLVED com decided_by nulo")
    void shouldAutoResolvePendingReportsWithNullDecidedByOnDeleteMedia() {
        UUID mediaId = UUID.randomUUID();

        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, userId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        PlaceMediaReport pendingReport = new PlaceMediaReport(UUID.randomUUID(), mediaId, UUID.randomUUID(), "INAPPROPRIATE", "Conteúdo ofensivo");

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));
        when(placeMediaReportRepository.findByPlaceMediaIdForUpdate(mediaId)).thenReturn(List.of(pendingReport));

        placeMediaService.deleteMedia(userId, placeId, mediaId);

        assertTrue(pendingReport.isAutoResolved());
        assertNull(pendingReport.getDecidedByUserId());
        assertNotNull(pendingReport.getDecidedAt());
        assertTrue(pendingReport.getDecisionNotes().contains("Resolução automática"));

        org.mockito.ArgumentCaptor<com.rewit.domain.model.PlaceMediaModerationAuditLog> captor =
                org.mockito.ArgumentCaptor.forClass(com.rewit.domain.model.PlaceMediaModerationAuditLog.class);
        verify(placeMediaModerationAuditLogRepository, atLeastOnce()).save(captor.capture());

        List<com.rewit.domain.model.PlaceMediaModerationAuditLog> logs = captor.getAllValues();
        assertEquals(2, logs.size(), "Logs capturados: " + logs.stream().map(l -> l.getAction() + ":" + l.getActorUserId()).toList());
        boolean hasAutoResolveAudit = logs.stream()
                .anyMatch(a -> a.getAction() == com.rewit.domain.enums.PlaceMediaModerationAction.AUTO_RESOLVE_REPORT
                        && a.getActorUserId() == null);
        assertTrue(hasAutoResolveAudit, "Deveria registrar auditoria AUTO_RESOLVE_REPORT com actor nulo para resolução automática do sistema");
    }

    @Test
    @DisplayName("40. RS-1: Moderador que também criou o local é classificado como MODERATOR")
    void shouldClassifyAsModeratorWhenUserIsBothCreatorAndModerator() {
        UUID modCreatorId = UUID.randomUUID();
        User modUser = User.rehydrate(
                modCreatorId, "mod@test.com", "hash", AuthProvider.LOCAL, null,
                AccountStatus.ACTIVE, true, Role.MODERATOR, null, Instant.now(), Instant.now()
        );
        when(userRepository.findById(modCreatorId)).thenReturn(Optional.of(modUser));

        Place createdPlace = new Place(
                placeId, "Local Criado", "local-criado", "RESTAURANTE", "Desc", "End",
                "100", "Bairro", "Curitiba", "PR", "BR", -25.4, -49.2, 50,
                "USER", true, null, modCreatorId, "ACTIVE"
        );
        UUID otherAuthorId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, otherAuthorId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(createdPlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));
        when(placeMediaReportRepository.findByPlaceMediaIdForUpdate(mediaId)).thenReturn(List.of());

        placeMediaService.deleteMedia(modCreatorId, placeId, mediaId);

        assertEquals(PlaceMediaStatus.REMOVED, media.getStatus());
        assertEquals(com.rewit.domain.enums.RemovalSource.MODERATOR, media.getRemovalSource());
    }

    @Test
    @DisplayName("41. RS-1: Moderador com vínculo comercial é classificado como MODERATOR")
    void shouldClassifyAsModeratorWhenUserIsBothBusinessOwnerAndModerator() {
        UUID modBusinessId = UUID.randomUUID();
        User modUser = User.rehydrate(
                modBusinessId, "modbiz@test.com", "hash", AuthProvider.LOCAL, null,
                AccountStatus.ACTIVE, true, Role.MODERATOR, null, Instant.now(), Instant.now()
        );
        when(userRepository.findById(modBusinessId)).thenReturn(Optional.of(modUser));

        UUID businessId = UUID.randomUUID();
        Place claimedPlace = new Place(
                placeId, "Local Claimed", "local-claimed", "RESTAURANTE", "Desc", "End",
                "100", "Bairro", "Curitiba", "PR", "BR", -25.4, -49.2, 50,
                "USER", true, businessId, UUID.randomUUID(), "ACTIVE"
        );
        UUID otherAuthorId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, otherAuthorId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        com.rewit.domain.model.BusinessAccount account = org.mockito.Mockito.mock(com.rewit.domain.model.BusinessAccount.class);
        when(account.isAdministeredBy(modBusinessId)).thenReturn(true);
        when(account.getVerificationStatus()).thenReturn(com.rewit.domain.enums.VerificationStatus.APPROVED);

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(claimedPlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));
        when(businessAccountRepository.findById(businessId)).thenReturn(Optional.of(account));
        when(placeMediaReportRepository.findByPlaceMediaIdForUpdate(mediaId)).thenReturn(List.of());

        placeMediaService.deleteMedia(modBusinessId, placeId, mediaId);

        assertEquals(PlaceMediaStatus.REMOVED, media.getStatus());
        assertEquals(com.rewit.domain.enums.RemovalSource.MODERATOR, media.getRemovalSource());
    }

    @Test
    @DisplayName("42. RS-1: Criador do local não moderador é classificado como PLACE_MANAGER")
    void shouldClassifyAsPlaceManagerWhenUserIsCreatorAndNotModerator() {
        UUID creatorId = UUID.randomUUID();
        User normalUser = User.rehydrate(
                creatorId, "normal@test.com", "hash", AuthProvider.LOCAL, null,
                AccountStatus.ACTIVE, true, Role.USER, null, Instant.now(), Instant.now()
        );
        when(userRepository.findById(creatorId)).thenReturn(Optional.of(normalUser));

        Place createdPlace = new Place(
                placeId, "Local Criado", "local-criado", "RESTAURANTE", "Desc", "End",
                "100", "Bairro", "Curitiba", "PR", "BR", -25.4, -49.2, 50,
                "USER", true, null, creatorId, "ACTIVE"
        );
        UUID otherAuthorId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, otherAuthorId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(createdPlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));
        when(placeMediaReportRepository.findByPlaceMediaIdForUpdate(mediaId)).thenReturn(List.of());

        placeMediaService.deleteMedia(creatorId, placeId, mediaId);

        assertEquals(PlaceMediaStatus.REMOVED, media.getStatus());
        assertEquals(com.rewit.domain.enums.RemovalSource.PLACE_MANAGER, media.getRemovalSource());
    }

    @Test
    @DisplayName("43. RS-1: Usuário sem autorização recebe FORBIDDEN")
    void shouldThrowForbiddenWhenUserHasNoRole() {
        UUID strangerId = UUID.randomUUID();
        User normalUser = User.rehydrate(
                strangerId, "stranger@test.com", "hash", AuthProvider.LOCAL, null,
                AccountStatus.ACTIVE, true, Role.USER, null, Instant.now(), Instant.now()
        );
        when(userRepository.findById(strangerId)).thenReturn(Optional.of(normalUser));

        UUID otherAuthorId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        PlaceMedia media = new PlaceMedia(
                mediaId, placeId, otherAuthorId, "places/" + placeId + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE, "image/jpeg", 2048L, 800, 600,
                PlaceMediaStatus.ACTIVE, false, null, null, Instant.now(), Instant.now()
        );

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findByIdForUpdate(mediaId)).thenReturn(Optional.of(media));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                placeMediaService.deleteMedia(strangerId, placeId, mediaId)
        );
        assertEquals("FORBIDDEN", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }
}
