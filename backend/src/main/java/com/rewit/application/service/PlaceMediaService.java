package com.rewit.application.service;

import com.rewit.application.dto.media.MediaDtos.MediaDownloadResult;
import com.rewit.application.dto.media.MediaDtos.PlaceMediaView;
import com.rewit.application.dto.media.MediaDtos.UploadPlaceMediaCommand;
import com.rewit.application.port.ObjectStoragePort;
import com.rewit.application.port.PlaceMediaRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateLimiter;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceMedia;
import com.rewit.infrastructure.storage.ImageSanitizer;
import com.rewit.infrastructure.storage.ImageSanitizer.SanitizedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Serviço de aplicação para gerenciamento de fotografias vinculadas diretamente a locais (Place).
 * Aplica sanitização de imagem (magic bytes JPEG/PNG, anti-decompression bomb, remoção de EXIF/GPS),
 * rate limiting por usuário, lock pessimista no local e compensação transacional no Object Storage.
 */
@Service
public class PlaceMediaService {

    private static final Logger log = LoggerFactory.getLogger(PlaceMediaService.class);

    private final PlaceRepository placeRepository;
    private final PlaceMediaRepository placeMediaRepository;
    private final AccountStatusPolicy accountStatusPolicy;
    private final ObjectStoragePort objectStoragePort;
    private final ImageSanitizer imageSanitizer;
    private final RateLimiter rateLimiter;

    public PlaceMediaService(PlaceRepository placeRepository,
                             PlaceMediaRepository placeMediaRepository,
                             AccountStatusPolicy accountStatusPolicy,
                             ObjectStoragePort objectStoragePort,
                             ImageSanitizer imageSanitizer,
                             RateLimiter rateLimiter) {
        this.placeRepository = Objects.requireNonNull(placeRepository, "PlaceRepository must not be null");
        this.placeMediaRepository = Objects.requireNonNull(placeMediaRepository, "PlaceMediaRepository must not be null");
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
        this.objectStoragePort = Objects.requireNonNull(objectStoragePort, "ObjectStoragePort must not be null");
        this.imageSanitizer = Objects.requireNonNull(imageSanitizer, "ImageSanitizer must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "RateLimiter must not be null");
    }

    @Transactional
    public PlaceMediaView uploadMedia(UploadPlaceMediaCommand cmd) {
        Objects.requireNonNull(cmd, "UploadPlaceMediaCommand cannot be null");

        if (cmd.authenticatedUserId() == null) {
            throw new BusinessException("Autenticação obrigatória para upload de fotografia do local", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (cmd.placeId() == null) {
            throw new BusinessException("O local é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_PLACE_ID");
        }
        if (cmd.fileBytes() == null || cmd.fileBytes().length == 0) {
            throw new BusinessException("O arquivo de mídia não pode ser vazio", HttpStatus.BAD_REQUEST, "EMPTY_MEDIA_FILE");
        }

        accountStatusPolicy.requireOperational(cmd.authenticatedUserId());

        rateLimiter.acquireOrThrow(RateLimitedAction.MEDIA_UPLOAD, RateLimitSubject.ofUser(cmd.authenticatedUserId()));

        Place place = placeRepository.findByIdForUpdate(cmd.placeId())
                .filter(p -> CatalogService.PUBLIC_CATALOG_STATUS.equals(p.getStatus()))
                .orElseThrow(() -> new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));

        long activeCount = placeMediaRepository.countActiveByPlaceId(place.getId());
        if (activeCount >= PlaceMedia.MAX_MEDIA_PER_PLACE) {
            throw new BusinessException(
                    "Limite máximo de " + PlaceMedia.MAX_MEDIA_PER_PLACE + " fotografias ativas por local já foi atingido",
                    HttpStatus.BAD_REQUEST,
                    "MAX_MEDIA_LIMIT_REACHED"
            );
        }

        SanitizedImage sanitized = imageSanitizer.sanitize(cmd.fileBytes());

        UUID mediaId = UUID.randomUUID();
        String objectKey = PlaceMedia.buildObjectKey(place.getId(), mediaId, sanitized.mimeType());

        objectStoragePort.put(objectKey, sanitized.mimeType(), sanitized.bytes());

        PlaceMedia media = new PlaceMedia(
                mediaId,
                place.getId(),
                cmd.authenticatedUserId(),
                objectKey,
                ReviewMediaType.IMAGE,
                sanitized.mimeType(),
                sanitized.sizeBytes(),
                sanitized.width(),
                sanitized.height()
        );

        boolean cleanupOnRollback = registerRollbackCleanup(objectKey);
        PlaceMedia saved;
        try {
            saved = placeMediaRepository.save(media);
        } catch (RuntimeException ex) {
            if (!cleanupOnRollback) {
                deleteUploadedObject(objectKey);
            }
            throw ex;
        }

        return PlaceMediaView.fromDomain(saved);
    }

    @Transactional(readOnly = true)
    public List<PlaceMediaView> getMediaByPlaceId(UUID placeId) {
        if (placeId == null) {
            throw new BusinessException("Identificador de local inválido", HttpStatus.BAD_REQUEST, "INVALID_PLACE_ID");
        }

        placeRepository.findById(placeId)
                .filter(p -> CatalogService.PUBLIC_CATALOG_STATUS.equals(p.getStatus()))
                .orElseThrow(() -> new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));

        return placeMediaRepository.findActiveByPlaceId(placeId)
                .stream()
                .map(PlaceMediaView::fromDomain)
                .toList();
    }

    @Transactional(readOnly = true)
    public String findPrimaryPhotoUrl(UUID placeId) {
        if (placeId == null) {
            return null;
        }
        List<PlaceMedia> activeMedia = placeMediaRepository.findActiveByPlaceId(placeId);
        if (activeMedia.isEmpty()) {
            return null;
        }
        return PlaceMediaView.fromDomain(activeMedia.get(0)).url();
    }

    @Transactional(readOnly = true)
    public MediaDownloadResult downloadMedia(UUID placeId, UUID mediaId) {
        if (placeId == null || mediaId == null) {
            throw new BusinessException("Identificadores inválidos", HttpStatus.BAD_REQUEST, "INVALID_IDENTIFIERS");
        }

        placeRepository.findById(placeId)
                .filter(p -> CatalogService.PUBLIC_CATALOG_STATUS.equals(p.getStatus()))
                .orElseThrow(() -> new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));

        PlaceMedia media = placeMediaRepository.findById(mediaId)
                .orElseThrow(() -> new BusinessException("Fotografia não encontrada", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND"));

        if (!media.getPlaceId().equals(placeId) || !media.isActive()) {
            throw new BusinessException("Fotografia não encontrada para o local informado", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND");
        }

        byte[] data = objectStoragePort.get(media.getObjectKey());
        return new MediaDownloadResult(data, media.getMimeType(), true);
    }

    private boolean registerRollbackCleanup(String objectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    deleteUploadedObject(objectKey);
                }
            }
        });
        return true;
    }

    private void deleteUploadedObject(String objectKey) {
        log.warn("Upload de fotografia de local sem metadata confirmada; removendo o objeto enviado (key '{}')", objectKey);
        try {
            objectStoragePort.delete(objectKey);
        } catch (RuntimeException compensationEx) {
            log.error("Compensação do upload de fotografia de local falhou para key '{}' (erro={})",
                    objectKey, compensationEx.getClass().getSimpleName());
        }
    }
}

