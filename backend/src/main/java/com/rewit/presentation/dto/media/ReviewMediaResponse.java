package com.rewit.presentation.dto.media;

import com.rewit.application.dto.media.MediaDtos.ReviewMediaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Resposta pública para operações de mídia de avaliações (Step 21.0).
 * Protege chaves internas do storage (object_key), nomes de arquivos e identificadores de autor,
 * garantindo total conformidade com os requisitos de privacidade e anonimato de Reviews.
 */
@Schema(description = "Representação pública de anexo de mídia em avaliação")
public record ReviewMediaResponse(
        @Schema(description = "Identificador único da mídia", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID id,

        @Schema(description = "Identificador da avaliação associada", example = "6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        UUID reviewId,

        @Schema(description = "URL pública para recuperação da imagem sanitizada", example = "/api/v1/reviews/6ba7b810-9dad-11d1-80b4-00c04fd430c8/media/550e8400-e29b-41d4-a716-446655440000")
        String url,

        @Schema(description = "Tipo de mídia", example = "IMAGE")
        String mediaType,

        @Schema(description = "Tipo MIME sanitizado", example = "image/jpeg")
        String mimeType,

        @Schema(description = "Tamanho do arquivo em bytes", example = "245123")
        long sizeBytes,

        @Schema(description = "Largura da imagem em pixels", example = "1920")
        Integer width,

        @Schema(description = "Altura da imagem em pixels", example = "1080")
        Integer height,

        @Schema(description = "Status do anexo", example = "ACTIVE")
        String status,

        @Schema(description = "Momento da criação", example = "2026-09-30T12:00:00Z")
        Instant createdAt
) {
    public static ReviewMediaResponse fromView(ReviewMediaView view) {
        return new ReviewMediaResponse(
                view.id(),
                view.reviewId(),
                view.url(),
                view.mediaType(),
                view.mimeType(),
                view.sizeBytes(),
                view.width(),
                view.height(),
                view.status(),
                view.createdAt()
        );
    }
}
