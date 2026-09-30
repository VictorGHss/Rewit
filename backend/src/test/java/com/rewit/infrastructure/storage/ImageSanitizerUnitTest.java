package com.rewit.infrastructure.storage;

import com.rewit.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários: ImageSanitizer e Proteção contra Decompression Bombs (Step 21.1)")
class ImageSanitizerUnitTest {

    private ImageSanitizer sanitizer;
    private byte[] validJpeg;
    private byte[] validPng;

    @BeforeEach
    void setUp() throws IOException {
        sanitizer = new ImageSanitizer();

        BufferedImage imgJpeg = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baosJpeg = new ByteArrayOutputStream();
        ImageIO.write(imgJpeg, "jpg", baosJpeg);
        validJpeg = baosJpeg.toByteArray();

        BufferedImage imgPng = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream baosPng = new ByteArrayOutputStream();
        ImageIO.write(imgPng, "png", baosPng);
        validPng = baosPng.toByteArray();
    }

    @Test
    @DisplayName("Deve sanitizar JPEG válido retornando dimensões e MIME type corretos")
    void testSanitizeValidJpeg() {
        ImageSanitizer.SanitizedImage result = sanitizer.sanitize(validJpeg);
        assertNotNull(result);
        assertEquals("image/jpeg", result.mimeType());
        assertEquals(64, result.width());
        assertEquals(64, result.height());
        assertTrue(result.sizeBytes() > 0);
    }

    @Test
    @DisplayName("Deve sanitizar PNG válido retornando dimensões e MIME type corretos")
    void testSanitizeValidPng() {
        ImageSanitizer.SanitizedImage result = sanitizer.sanitize(validPng);
        assertNotNull(result);
        assertEquals("image/png", result.mimeType());
        assertEquals(64, result.width());
        assertEquals(64, result.height());
        assertTrue(result.sizeBytes() > 0);
    }

    @Test
    @DisplayName("Deve rejeitar arquivo de mídia vazio com 400 Bad Request")
    void testRejectEmptyFile() {
        BusinessException ex = assertThrows(BusinessException.class, () -> sanitizer.sanitize(new byte[0]));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("EMPTY_MEDIA_FILE", ex.getErrorCode());
    }

    @Test
    @DisplayName("Deve rejeitar arquivo superior a 10 MB com 413 Content Too Large")
    void testRejectFileExceeding10MB() {
        byte[] large = new byte[(10 * 1024 * 1024) + 1];
        BusinessException ex = assertThrows(BusinessException.class, () -> sanitizer.sanitize(large));
        assertEquals(HttpStatus.CONTENT_TOO_LARGE, ex.getStatus());
        assertEquals("MEDIA_SIZE_EXCEEDED", ex.getErrorCode());
    }

    @Test
    @DisplayName("Deve rejeitar arquivos arbitrários (HTML/SVG/Executáveis) com 415 Unsupported Media Type")
    void testRejectUnsupportedMediaTypes() {
        byte[] html = "<html><body>Not an image</body></html>".getBytes();
        BusinessException exHtml = assertThrows(BusinessException.class, () -> sanitizer.sanitize(html));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, exHtml.getStatus());

        byte[] svg = "<svg viewBox='0 0 100 100'></svg>".getBytes();
        BusinessException exSvg = assertThrows(BusinessException.class, () -> sanitizer.sanitize(svg));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, exSvg.getStatus());

        byte[] exe = new byte[]{'M', 'Z', 1, 2, 3, 4, 5, 6};
        BusinessException exExe = assertThrows(BusinessException.class, () -> sanitizer.sanitize(exe));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, exExe.getStatus());
    }

    @Test
    @DisplayName("Deve rejeitar arquivo corrompido que possua magic bytes mas falhe na leitura de dimensões")
    void testRejectCorruptedImageHeader() {
        byte[] corruptJpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0};
        BusinessException ex = assertThrows(BusinessException.class, () -> sanitizer.sanitize(corruptJpeg));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_IMAGE_FILE", ex.getErrorCode());
    }
}
