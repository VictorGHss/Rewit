package com.rewit.application.service;

import com.rewit.application.dto.media.MediaDtos.MediaDownloadResult;
import com.rewit.application.dto.media.MediaDtos.PlaceMediaView;
import com.rewit.application.dto.media.MediaDtos.UploadPlaceMediaCommand;
import com.rewit.application.port.ObjectStoragePort;
import com.rewit.application.port.PlaceMediaRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateLimiter;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceMedia;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: PlaceMediaService")
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
                "ACTIVE"
        );
        placeMediaService = new PlaceMediaService(
                placeRepository,
                placeMediaRepository,
                accountStatusPolicy,
                objectStoragePort,
                new ImageSanitizer(),
                rateLimiter
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

        when(placeRepository.findByIdForUpdate(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.countActiveByPlaceId(placeId)).thenReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                placeMediaService.uploadMedia(new UploadPlaceMediaCommand(placeId, userId, fakeBytes, "exploit.jpg"))
        );

        assertEquals("UNSUPPORTED_MEDIA_TYPE", ex.getErrorCode());
        verifyNoInteractions(objectStoragePort);
        verify(placeMediaRepository, never()).save(any());
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

        when(placeRepository.findById(placeId)).thenReturn(Optional.of(activePlace));
        when(placeMediaRepository.findById(mediaId)).thenReturn(Optional.of(mediaOfOtherPlace));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                placeMediaService.downloadMedia(placeId, mediaId)
        );

        assertEquals("MEDIA_NOT_FOUND", ex.getErrorCode());
        verifyNoInteractions(objectStoragePort);
    }

    @Test
    @DisplayName("5. findPrimaryPhotoUrl retorna a URL pública da primeira fotografia ativa do local")
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

        when(placeMediaRepository.findActiveByPlaceId(placeId)).thenReturn(List.of(media));

        String photoUrl = placeMediaService.findPrimaryPhotoUrl(placeId);

        assertEquals("/api/v1/places/" + placeId + "/media/" + mediaId, photoUrl);
    }
}

