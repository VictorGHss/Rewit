package com.rewit.infrastructure.storage;

import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Utilitário de sanitização profunda e validação estrita de imagens (Step 21.0 / Step 21.1).
 * Garante validação prévia por magic bytes, leitura antecipada de dimensões via ImageReader
 * (prevenção eficaz contra decompression bombs sem alocação do raster completo),
 * limites dimensionais e eliminação integral de metadados EXIF/GPS via reconstrução de pixels.
 */
@Component
public class ImageSanitizer {

    public static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB
    public static final int MAX_DIMENSION = 10000; // 10.000 pixels
    public static final long MAX_PIXEL_COUNT = (long) MAX_DIMENSION * MAX_DIMENSION; // 100 megapixels

    public record SanitizedImage(
            byte[] bytes,
            String mimeType,
            int width,
            int height,
            long sizeBytes
    ) {}

    private record ImageDimensions(int width, int height) {}

    private enum SupportedFormat {
        JPEG("image/jpeg", "jpg"),
        PNG("image/png", "png");

        private final String mimeType;
        private final String extension;

        SupportedFormat(String mimeType, String extension) {
            this.mimeType = mimeType;
            this.extension = extension;
        }

        public String getMimeType() {
            return mimeType;
        }

        public String getExtension() {
            return extension;
        }
    }

    /**
     * Valida os magic bytes, descobre as dimensões de forma rápida e segura antes do decode
     * (proteção contra decompression bombs) e re-encoda os pixels limpos sem qualquer metadado EXIF/GPS.
     *
     * @param rawBytes os bytes crus enviados no upload
     * @return o resultado sanitizado contendo os novos bytes e dimensões
     */
    public SanitizedImage sanitize(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length == 0) {
            throw new BusinessException("O arquivo de mídia não pode ser vazio", HttpStatus.BAD_REQUEST, "EMPTY_MEDIA_FILE");
        }

        if (rawBytes.length > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(
                    "O tamanho do arquivo excede o limite máximo permitido de 10 MB",
                    HttpStatus.CONTENT_TOO_LARGE,
                    "MEDIA_SIZE_EXCEEDED"
            );
        }

        SupportedFormat format = detectFormatByMagicBytes(rawBytes);
        if (format == null) {
            throw new BusinessException(
                    "Formato de imagem não suportado. Apenas JPEG e PNG são permitidos.",
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_MEDIA_TYPE"
            );
        }

        // 1. Obtenção antecipada de dimensões usando ImageReader SEM decodificar raster completo
        ImageDimensions dimensions = readDimensionsFast(rawBytes, format);

        if (dimensions.width() <= 0 || dimensions.width() > MAX_DIMENSION
                || dimensions.height() <= 0 || dimensions.height() > MAX_DIMENSION) {
            throw new BusinessException(
                    "As dimensões da imagem (" + dimensions.width() + "x" + dimensions.height() + ") excedem o limite permitido de " + MAX_DIMENSION + "x" + MAX_DIMENSION + " pixels",
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IMAGE_DIMENSIONS"
            );
        }

        long totalPixels = (long) dimensions.width() * dimensions.height();
        if (totalPixels > MAX_PIXEL_COUNT) {
            throw new BusinessException(
                    "Área total da imagem excede o limite permitido",
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IMAGE_DIMENSIONS"
            );
        }

        // 2. Decodificação segura do raster de pixels somente após aprovação dimensional
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(rawBytes));
        } catch (IOException e) {
            throw new BusinessException("Falha ao decodificar arquivo de imagem", HttpStatus.BAD_REQUEST, "INVALID_IMAGE_FILE");
        }

        if (decoded == null) {
            throw new BusinessException("Arquivo de imagem corrompido ou inválido", HttpStatus.BAD_REQUEST, "INVALID_IMAGE_FILE");
        }

        // 3. Reconstrução / Re-encoding para eliminar metadados EXIF e GPS
        byte[] sanitizedBytes = reencodeWithoutMetadata(decoded, format);

        return new SanitizedImage(
                sanitizedBytes,
                format.getMimeType(),
                dimensions.width(),
                dimensions.height(),
                sanitizedBytes.length
        );
    }

    private SupportedFormat detectFormatByMagicBytes(byte[] bytes) {
        if (bytes.length < 8) {
            return null;
        }

        // JPEG Magic Bytes: FF D8 FF
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return SupportedFormat.JPEG;
        }

        // PNG Magic Bytes: 89 50 4E 47 0D 0A 1A 0A
        if ((bytes[0] & 0xFF) == 0x89 &&
                bytes[1] == 0x50 && // P
                bytes[2] == 0x4E && // N
                bytes[3] == 0x47 && // G
                bytes[4] == 0x0D && // \r
                bytes[5] == 0x0A && // \n
                bytes[6] == 0x1A &&
                bytes[7] == 0x0A) {
            return SupportedFormat.PNG;
        }

        return null;
    }

    /**
     * Lê as dimensões da imagem (width e height) diretamente do cabeçalho binário usando ImageReader,
     * sem alocar buffer de pixels em memória. Protege de forma eficiente contra decompression bombs.
     */
    private ImageDimensions readDimensionsFast(byte[] bytes, SupportedFormat format) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) {
                throw new BusinessException("Fluxo de entrada de imagem inválido", HttpStatus.BAD_REQUEST, "INVALID_IMAGE_FILE");
            }

            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format.getExtension());
            if (!readers.hasNext()) {
                throw new BusinessException("Leitor de imagem não disponível para o formato", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return new ImageDimensions(width, height);
            } finally {
                reader.dispose();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Falha ao inspecionar cabeçalho dimensional da imagem", HttpStatus.BAD_REQUEST, "INVALID_IMAGE_FILE");
        }
    }

    private byte[] reencodeWithoutMetadata(BufferedImage source, SupportedFormat format) {
        int targetType = (format == SupportedFormat.JPEG) ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
        BufferedImage cleanImage = new BufferedImage(source.getWidth(), source.getHeight(), targetType);

        Graphics2D g2d = cleanImage.createGraphics();
        try {
            if (format == SupportedFormat.JPEG) {
                // Preenche fundo branco caso imagem de origem tenha canal alfa
                g2d.setColor(Color.WHITE);
                g2d.fillRect(0, 0, source.getWidth(), source.getHeight());
            }
            g2d.drawImage(source, 0, 0, null);
        } finally {
            g2d.dispose();
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            boolean written = ImageIO.write(cleanImage, format.getExtension(), baos);
            if (!written) {
                throw new BusinessException("Falha na codificação da imagem sanitizada", HttpStatus.INTERNAL_SERVER_ERROR, "IMAGE_ENCODING_FAILED");
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("Erro de I/O ao sanitizar imagem", HttpStatus.INTERNAL_SERVER_ERROR, "IMAGE_SANITIZATION_FAILED");
        }
    }
}
