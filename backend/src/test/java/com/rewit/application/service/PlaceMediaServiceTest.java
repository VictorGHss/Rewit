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
        assertEquals("ACCEPTED", captor.getValue().getStatus());
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
        when(placeMediaReportRepository.countByPlaceMediaIdAndStatus(mediaId, PlaceMediaReport.STATUS_PENDING)).thenReturn(1L);

        var res = placeMediaService.decideReport(modId, reportId, "REJECT", "Denúncia infundada");

        assertEquals("REJECTED", res.status());
        assertEquals(PlaceMediaStatus.UNDER_REVIEW, media.getStatus());
        assertFalse(media.isActive());
        verify(placeMediaRepository, never()).save(media);
    }

    @Test
    @DisplayName("18b. decideReport com REJECT restabelece automaticamente a foto para ACTIVE quando a última denúncia é rejeitada")
    void shouldRestoreMediaToActiveWhenLastReportIsRejected() {
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
        when(placeMediaReportRepository.countByPlaceMediaIdAndStatus(mediaId, PlaceMediaReport.STATUS_PENDING)).thenReturn(0L);
        when(placeMediaRepository.countActiveByPlaceId(placeId)).thenReturn(1L);
        when(placeMediaRepository.findPrimaryByPlaceId(placeId)).thenReturn(Optional.empty());

        var res = placeMediaService.decideReport(modId, reportId, "REJECT", "Denúncia infundada");

        assertEquals("REJECTED", res.status());
        assertEquals(PlaceMediaStatus.ACTIVE, media.getStatus());
        assertTrue(media.isActive());
        assertTrue(media.isPrimary());
        verify(placeMediaRepository).save(media);
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
        when(objectStoragePort.exists(media.getObjectKey())).thenReturn(false);
        BusinessException ex = assertThrows(BusinessException.class, () ->
                placeMediaService.restoreMediaByModerator(modId, placeId, mediaId, "Restaurando novamente")
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
}
