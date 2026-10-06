package com.rewit.application.service;

import com.rewit.application.dto.media.MediaDtos.MediaDownloadResult;
import com.rewit.application.dto.media.MediaDtos.ReviewMediaView;
import com.rewit.application.dto.media.MediaDtos.UploadMediaCommand;
import com.rewit.application.port.ObjectStoragePort;
import com.rewit.application.port.RateLimiter;
import com.rewit.application.port.ReviewMediaRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewMedia;
import com.rewit.infrastructure.storage.ImageSanitizer;
import com.rewit.infrastructure.storage.ImageSanitizer.SanitizedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Serviço de aplicação para gerenciamento de anexos de mídia em avaliações (Step 21.0 / 21.1).
 * Orquestra regras de negócio, autorização por herança da Review, limites de tamanho e quantidade,
 * sanitização antecipada de dimensões (anti-decompression bomb), remoção de metadados EXIF/GPS
 * e garantia de consistência entre PostgreSQL e Object Storage.
 */
@Service
public class ReviewMediaService {

    private static final Logger log = LoggerFactory.getLogger(ReviewMediaService.class);

    private final ReviewRepository reviewRepository;
    private final AccountStatusPolicy accountStatusPolicy;
    private final ReviewMediaRepository reviewMediaRepository;
    private final ObjectStoragePort objectStoragePort;
    private final ReviewVisibilityPolicy reviewVisibilityPolicy;
    private final ImageSanitizer imageSanitizer;
    private final RateLimiter rateLimiter;

    public ReviewMediaService(ReviewRepository reviewRepository,
                              AccountStatusPolicy accountStatusPolicy,
                              ReviewMediaRepository reviewMediaRepository,
                              ObjectStoragePort objectStoragePort,
                              ReviewVisibilityPolicy reviewVisibilityPolicy,
                              ImageSanitizer imageSanitizer,
                              RateLimiter rateLimiter) {
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "ReviewRepository must not be null");
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
        this.reviewMediaRepository = Objects.requireNonNull(reviewMediaRepository, "ReviewMediaRepository must not be null");
        this.objectStoragePort = Objects.requireNonNull(objectStoragePort, "ObjectStoragePort must not be null");
        this.reviewVisibilityPolicy = Objects.requireNonNull(reviewVisibilityPolicy, "ReviewVisibilityPolicy must not be null");
        this.imageSanitizer = Objects.requireNonNull(imageSanitizer, "ImageSanitizer must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "RateLimiter must not be null");
    }

    /**
     * Realiza o upload atômico e sanitizado de uma imagem para uma avaliação existente.
     * Utiliza lock pessimista na Review para prevenir race conditions no limite de 5 imagens.
     * Em caso de falha na persistência de banco após o upload no storage, dispara compensação.
     *
     * @param cmd dados de comando contendo reviewId, usuário autenticado e bytes do arquivo
     * @return visão representacional pública da mídia persistida
     */
    @Transactional
    public ReviewMediaView uploadMedia(UploadMediaCommand cmd) {
        Objects.requireNonNull(cmd, "UploadMediaCommand cannot be null");

        if (cmd.authenticatedUserId() == null) {
            throw new BusinessException("Autenticação obrigatória para upload de mídia", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (cmd.reviewId() == null) {
            throw new BusinessException("A avaliação é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_REVIEW_ID");
        }
        if (cmd.fileBytes() == null || cmd.fileBytes().length == 0) {
            throw new BusinessException("O arquivo de mídia não pode ser vazio", HttpStatus.BAD_REQUEST, "EMPTY_MEDIA_FILE");
        }

        accountStatusPolicy.requireOperational(cmd.authenticatedUserId());

        // 1. Rate Limiting por usuário
        rateLimiter.acquireOrThrow(RateLimitedAction.MEDIA_UPLOAD, RateLimitSubject.ofUser(cmd.authenticatedUserId()));

        // 2. Lock pessimista na Review e validação de existência e status
        Review review = reviewRepository.findByIdForUpdate(cmd.reviewId())
                .orElseThrow(() -> new BusinessException("Avaliação não encontrada", HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));

        // 3. Validação de visibilidade e acesso baseada na Review
        reviewVisibilityPolicy.validateCanAccess(review, cmd.authenticatedUserId());

        // 4. Verificação transacionalmente segura do limite máximo de 5 mídias ativas
        long activeCount = reviewMediaRepository.countActiveByReviewId(review.getId());
        if (activeCount >= ReviewMedia.MAX_MEDIA_PER_REVIEW) {
            throw new BusinessException(
                    "Limite máximo de " + ReviewMedia.MAX_MEDIA_PER_REVIEW + " mídias ativas por avaliação já foi atingido",
                    HttpStatus.BAD_REQUEST,
                    "MAX_MEDIA_LIMIT_REACHED"
            );
        }

        // 5. Validação de magic bytes, inspeção antecipada de dimensões (anti-decompression bomb)
        // e remoção integral de EXIF / GPS via re-encoding de pixels puros
        SanitizedImage sanitized = imageSanitizer.sanitize(cmd.fileBytes());

        // 6. Geração de identificadores internos imprevisíveis (não expostos externamente)
        UUID mediaId = UUID.randomUUID();
        String extension = "image/png".equalsIgnoreCase(sanitized.mimeType()) ? "png" : "jpg";
        String objectKey = "reviews/" + review.getId() + "/" + mediaId + "/image." + extension;

        // 7. Envio do objeto sanitizado para o Object Storage
        objectStoragePort.put(objectKey, sanitized.mimeType(), sanitized.bytes());

        // 8. Inserção atômica de metadata no PostgreSQL com compensação explícita em caso de falha
        ReviewMedia media = new ReviewMedia(
                mediaId,
                review.getId(),
                cmd.authenticatedUserId(),
                objectKey,
                ReviewMediaType.IMAGE,
                sanitized.mimeType(),
                sanitized.sizeBytes(),
                sanitized.width(),
                sanitized.height()
        );

        ReviewMedia saved;
        try {
            saved = reviewMediaRepository.save(media);
        } catch (Exception ex) {
            log.error("Falha ao salvar metadata de mídia no banco de dados. Executando compensação no storage para key '{}'", objectKey, ex);
            try {
                objectStoragePort.delete(objectKey);
            } catch (Exception compensationEx) {
                log.error("Erro durante compensação ao deletar objeto '{}' após falha no banco (erro={})", objectKey, compensationEx.getClass().getSimpleName());
            }
            throw ex;
        }

        return ReviewMediaView.fromDomain(saved);
    }

    /**
     * Lista todas as mídias ativas de uma avaliação, respeitando a política de visibilidade da Review.
     *
     * @param reviewId identificador da avaliação
     * @param requesterUserId identificador do usuário solicitante (opcional se público)
     * @return lista de mídias ativas ordenadas por data de criação crescente
     */
    @Transactional(readOnly = true)
    public List<ReviewMediaView> getMediaByReviewId(UUID reviewId, UUID requesterUserId) {
        if (reviewId == null) {
            throw new BusinessException("A avaliação é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_REVIEW_ID");
        }

        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException("Avaliação não encontrada", HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));

        reviewVisibilityPolicy.validateCanAccess(review, requesterUserId);

        return reviewMediaRepository.findActiveByReviewId(reviewId)
                .stream()
                .map(entity -> ReviewMediaView.fromDomain(entity))
                .toList();
    }

    /**
     * Recupera o arquivo de mídia sanitizado para streaming/download seguro.
     * Valida relação estrita Review <-> Media para prevenção de IDOR.
     * O acesso ao Object Storage ocorre estritamente após todas as verificações de autorização.
     *
     * @param reviewId identificador da avaliação na URL
     * @param mediaId identificador da mídia na URL
     * @param requesterUserId usuário autenticado solicitante
     * @return bytes da imagem e respectivo MIME type
     */
    @Transactional(readOnly = true)
    public MediaDownloadResult downloadMedia(UUID reviewId, UUID mediaId, UUID requesterUserId) {
        if (reviewId == null || mediaId == null) {
            throw new BusinessException("Identificadores inválidos", HttpStatus.BAD_REQUEST, "INVALID_IDENTIFIERS");
        }

        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException("Avaliação não encontrada", HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));

        reviewVisibilityPolicy.validateCanAccess(review, requesterUserId);

        ReviewMedia media = reviewMediaRepository.findById(mediaId)
                .orElseThrow(() -> new BusinessException("Mídia não encontrada", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND"));

        // Prevenção de IDOR: a mídia deve pertencer obrigatoriamente à Review informada no path
        if (!media.getReviewId().equals(reviewId) || !media.isActive()) {
            throw new BusinessException("Mídia não encontrada para a avaliação informada", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND");
        }

        // Acesso ao storage somente após autorização completa
        byte[] data = objectStoragePort.get(media.getObjectKey());
        return new MediaDownloadResult(data, media.getMimeType());
    }

    /**
     * Exclui (soft delete) uma mídia anexada a uma avaliação.
     * Exclusão permitida estritamente ao autor da Review.
     * Ordem de consistência:
     * 1. Soft delete no PostgreSQL primeiro (garante que endpoints públicos bloqueiam imediatamente);
     * 2. Remoção física do objeto no Object Storage;
     * 3. Em caso de falha de storage, o registro no PostgreSQL permanece como REMOVED,
     *    garantindo que o arquivo nunca permaneça acessível pela API pública nem crie metadata ACTIVE apontando para objeto inexistente.
     *
     * @param reviewId identificador da avaliação
     * @param mediaId identificador da mídia
     * @param authenticatedUserId usuário autenticado
     */
    @Transactional
    public void deleteMedia(UUID reviewId, UUID mediaId, UUID authenticatedUserId) {
        if (authenticatedUserId == null) {
            throw new BusinessException("Autenticação obrigatória para exclusão", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (reviewId == null || mediaId == null) {
            throw new BusinessException("Identificadores inválidos", HttpStatus.BAD_REQUEST, "INVALID_IDENTIFIERS");
        }

        accountStatusPolicy.requireOperational(authenticatedUserId);

        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException("Avaliação não encontrada", HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));

        ReviewMedia media = reviewMediaRepository.findById(mediaId)
                .orElseThrow(() -> new BusinessException("Mídia não encontrada", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND"));

        // Validação estrita de relação Review <-> Media
        if (!media.getReviewId().equals(reviewId)) {
            throw new BusinessException("Mídia não encontrada para a avaliação informada", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND");
        }

        // Autorização estrita: somente o autor da Review pode excluir a mídia
        if (!review.getUserId().equals(authenticatedUserId)) {
            throw new BusinessException("Apenas o autor da avaliação tem permissão para excluir esta mídia", HttpStatus.FORBIDDEN, "FORBIDDEN");
        }

        // Idempotência: se já foi removida, retorna sucesso
        if (!media.isActive()) {
            return;
        }

        // 1. Soft delete prioritário no PostgreSQL
        media.markRemoved();
        reviewMediaRepository.save(media);

        // 2. Remoção física do objeto no storage
        try {
            objectStoragePort.delete(media.getObjectKey());
        } catch (Exception ex) {
            log.warn("Falha ao remover arquivo físico do storage '{}' durante exclusão (erro={})", media.getObjectKey(), ex.getClass().getSimpleName());
            // Mantém registro como REMOVED no banco: a mídia está logicamente eliminada e inacessível
        }
    }
}
