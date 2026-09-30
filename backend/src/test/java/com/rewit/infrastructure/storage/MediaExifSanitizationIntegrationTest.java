package com.rewit.infrastructure.storage;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.GpsDirectory;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Reais de Sanitização de EXIF e GPS (Step 21.0 - Requisito 8 e 25)")
class MediaExifSanitizationIntegrationTest {

    private final ImageSanitizer imageSanitizer = new ImageSanitizer();

    @Test
    @DisplayName("Upload com EXIF GPS conhecido deve ter coordenadas totalmente eliminadas do arquivo final")
    void testExifGpsIsCompletelyStrippedFromSanitizedImage() throws Exception {
        // 1. Cria imagem JPEG base
        BufferedImage baseImage = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baseOs = new ByteArrayOutputStream();
        ImageIO.write(baseImage, "jpg", baseOs);
        byte[] baseJpegBytes = baseOs.toByteArray();

        // 2. Injeta metadados EXIF com coordenadas GPS reais (São Paulo: -23.55052, -46.633308)
        double originalLongitude = -46.633308;
        double originalLatitude = -23.55052;

        TiffOutputSet outputSet = new TiffOutputSet();
        outputSet.setGpsInDegrees(originalLongitude, originalLatitude);

        ByteArrayOutputStream jpegWithExifOs = new ByteArrayOutputStream();
        new ExifRewriter().updateExifMetadataLossless(baseJpegBytes, jpegWithExifOs, outputSet);
        byte[] jpegWithGpsBytes = jpegWithExifOs.toByteArray();

        // 3. Prova que a imagem antes da sanitização possui metadados GPS reais detectáveis
        Metadata metaBefore = ImageMetadataReader.readMetadata(new ByteArrayInputStream(jpegWithGpsBytes));
        GpsDirectory gpsDirBefore = metaBefore.getFirstDirectoryOfType(GpsDirectory.class);
        assertNotNull(gpsDirBefore, "Imagem de entrada deve conter diretório GPS");
        assertNotNull(gpsDirBefore.getGeoLocation(), "Imagem de entrada deve conter coordenadas GPS");
        assertEquals(originalLatitude, gpsDirBefore.getGeoLocation().getLatitude(), 0.001);
        assertEquals(originalLongitude, gpsDirBefore.getGeoLocation().getLongitude(), 0.001);

        // 4. Executa sanitização rigorosa via ImageSanitizer
        ImageSanitizer.SanitizedImage sanitized = imageSanitizer.sanitize(jpegWithGpsBytes);
        assertNotNull(sanitized);
        byte[] sanitizedBytes = sanitized.bytes();

        // 5. Inspeciona o arquivo sanitizado final armazenado
        Metadata metaAfter = ImageMetadataReader.readMetadata(new ByteArrayInputStream(sanitizedBytes));
        GpsDirectory gpsDirAfter = metaAfter.getFirstDirectoryOfType(GpsDirectory.class);

        // 6. Confirma que GPS foi 100% removido e nenhuma coordenada permanece
        if (gpsDirAfter != null) {
            assertNull(gpsDirAfter.getGeoLocation(), "Coordenadas GPS não podem existir após sanitização");
        }

        // 7. Confirma que a imagem continua perfeitamente decodificável e válida
        BufferedImage decodedAfter = ImageIO.read(new ByteArrayInputStream(sanitizedBytes));
        assertNotNull(decodedAfter, "Imagem sanitizada deve ser decodificável");
        assertEquals(200, decodedAfter.getWidth());
        assertEquals(200, decodedAfter.getHeight());
    }
}
