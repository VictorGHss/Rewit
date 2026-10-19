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
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.enums.Role;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceMedia;
import com.rewit.infrastructure.persistence.entity.PlaceMediaReportJpaEntity;
import com.rewit.infrastructure.persistence.repository.PlaceMediaReportJpaRepository;
import com.rewit.infrastructure.storage.ImageSanitizer;
import com.rewit.infrastructure.storage.ImageSanitizer.SanitizedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Serviço de aplicação para gerenciamento de fotografias vinculadas diretamente a locais (Place).
 * Aplica sanitização de imagem (magic bytes JPEG/PNG, anti-decompression bomb, remoção de EXIF/GPS),
 * rate limiting por usuário, autorização de envio e moderação, regra determinística de capa,
 * idempotência de upload e compensação transacional no Object Storage.
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
    private final BusinessAccountRepository businessAccountRepository;
    private final UserRepository userRepository;
    private final PlaceMediaReportJpaRepository placeMediaReportJpaRepository;

    public PlaceMediaService(PlaceRepository placeRepository,
                             PlaceMediaRepository placeMediaRepository,
                             AccountStatusPolicy accountStatusPolicy,
                             ObjectStoragePort objectStoragePort,
                             ImageSanitizer imageSanitizer,
                             RateLimiter rateLimiter) {
        this(placeRepository, placeMediaRepository, accountStatusPolicy, objectStoragePort, imageSanitizer, rateLimiter, null, null, null);
    }

    @Autowired
    public PlaceMediaService(PlaceRepository placeRepository,
                             PlaceMediaRepository placeMediaRepository,
                             AccountStatusPolicy accountStatusPolicy,
                             ObjectStoragePort objectStoragePort,
                             ImageSanitizer imageSanitizer,
                             RateLimiter rateLimiter,
                             BusinessAccountRepository businessAccountRepository,
                             UserRepository userRepository,
                             PlaceMediaReportJpaRepository placeMediaReportJpaRepository) {
        this.placeRepository = Objects.requireNonNull(placeRepository, "PlaceRepository must not be null");
        this.placeMediaRepository = Objects.requireNonNull(placeMediaRepository, "PlaceMediaRepository must not be null");
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
        this.objectStoragePort = Objects.requireNonNull(objectStoragePort, "ObjectStoragePort must not be null");
        this.imageSanitizer = Objects.requireNonNull(imageSanitizer, "ImageSanitizer must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "RateLimiter must not be null");
        this.businessAccountRepository = businessAccountRepository;
        this.userRepository = userRepository;
        this.placeMediaReportJpaRepository = placeMediaReportJpaRepository;
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

        // Sanitização CPU-bound executada antes de abrir transações de longa duração com lock (A-08)
        SanitizedImage sanitized = imageSanitizer.sanitize(cmd.fileBytes());
        String contentSha256 = computeSha256(sanitized.bytes());

        rateLimiter.acquireOrThrow(RateLimitedAction.MEDIA_UPLOAD, RateLimitSubject.ofUser(cmd.authenticatedUserId()));

        Place place = placeRepository.findByIdForUpdate(cmd.placeId())
                .filter(p -> CatalogService.PUBLIC_CATALOG_STATUS.equals(p.getStatus()))
                .orElseThrow(() -> new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));

        requireAuthorizedToUpload(cmd.authenticatedUserId(), place);

        // Idempotência baseada em chave de idempotência e hash SHA-256 (A-07)
        if (cmd.idempotencyKey() != null && !cmd.idempotencyKey().isBlank()) {
            Optional<PlaceMedia> existing = placeMediaRepository.findByIdempotencyKey(
                    cmd.authenticatedUserId(), place.getId(), cmd.idempotencyKey().trim());
            if (existing.isPresent()) {
                PlaceMedia existingMedia = existing.get();
                if (contentSha256.equalsIgnoreCase(existingMedia.getContentSha256())) {
                    return PlaceMediaView.fromDomain(existingMedia);
                }
                throw new BusinessException("Idempotency key já utilizada com payload diferente", HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            }
        }

        long activeCount = placeMediaRepository.countActiveByPlaceId(place.getId());
        if (activeCount >= PlaceMedia.MAX_MEDIA_PER_PLACE) {
            throw new BusinessException(
                    "Limite máximo de " + PlaceMedia.MAX_MEDIA_PER_PLACE + " fotografias ativas por local já foi atingido",
                    HttpStatus.BAD_REQUEST,
                    "MAX_MEDIA_LIMIT_REACHED"
            );
        }

        boolean hasActivePrimary = placeMediaRepository.findPrimaryByPlaceId(place.getId()).isPresent();
        boolean isPrimary = !hasActivePrimary; // A primeira fotografia ativa torna-se capa determinística

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
                sanitized.height(),
                ReviewMediaStatus.ACTIVE,
                isPrimary,
                cmd.idempotencyKey(),
                contentSha256,
                Instant.now(),
                Instant.now()
        );

        boolean cleanupOnRollback = registerRollbackCleanup(objectKey);
        PlaceMedia saved;
        try {
            saved = placeMediaRepository.save(media);
        } catch (DataIntegrityViolationException dive) {
            if (!cleanupOnRollback) {
                deleteUploadedObject(objectKey);
            }
            if (cmd.idempotencyKey() != null && !cmd.idempotencyKey().isBlank()) {
                Optional<PlaceMedia> existing = placeMediaRepository.findByIdempotencyKey(
                        cmd.authenticatedUserId(), place.getId(), cmd.idempotencyKey().trim());
                if (existing.isPresent()) {
                    PlaceMedia existingMedia = existing.get();
                    if (contentSha256.equalsIgnoreCase(existingMedia.getContentSha256())) {
                        return PlaceMediaView.fromDomain(existingMedia);
                    }
                    throw new BusinessException("Idempotency key já utilizada com payload diferente", HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
                }
            }
            throw dive;
        } catch (RuntimeException ex) {
            if (!cleanupOnRollback) {
                deleteUploadedObject(objectKey);
            }
            throw ex;
        }

        return PlaceMediaView.fromDomain(saved);
    }

    @Transactional
    public PlaceMediaView setPrimaryMedia(UUID actorUserId, UUID placeId, UUID mediaId) {
        if (actorUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (placeId == null || mediaId == null) {
            throw new BusinessException("Identificadores inválidos", HttpStatus.BAD_REQUEST, "INVALID_IDENTIFIERS");
        }

        accountStatusPolicy.requireOperational(actorUserId);

        Place place = placeRepository.findByIdForUpdate(placeId)
                .filter(p -> CatalogService.PUBLIC_CATALOG_STATUS.equals(p.getStatus()))
                .orElseThrow(() -> new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));

        requireAuthorizedToUpload(actorUserId, place);

        PlaceMedia media = placeMediaRepository.findById(mediaId)
                .orElseThrow(() -> new BusinessException("Fotografia não encontrada", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND"));

        if (!media.getPlaceId().equals(placeId) || !media.isActive()) {
            throw new BusinessException("Fotografia não encontrada para o local informado", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND");
        }

        if (media.isPrimary()) {
            return PlaceMediaView.fromDomain(media);
        }

        placeMediaRepository.findPrimaryByPlaceId(placeId).ifPresent(oldPrimary -> {
            oldPrimary.setPrimary(false);
            placeMediaRepository.save(oldPrimary);
        });

        media.setPrimary(true);
        PlaceMedia saved = placeMediaRepository.save(media);
        return PlaceMediaView.fromDomain(saved);
    }

    @Transactional
    public void deleteMedia(UUID actorUserId, UUID placeId, UUID mediaId) {
        if (actorUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (placeId == null || mediaId == null) {
            throw new BusinessException("Identificadores inválidos", HttpStatus.BAD_REQUEST, "INVALID_IDENTIFIERS");
        }

        accountStatusPolicy.requireOperational(actorUserId);

        Place place = placeRepository.findByIdForUpdate(placeId)
                .filter(p -> CatalogService.PUBLIC_CATALOG_STATUS.equals(p.getStatus()))
                .orElseThrow(() -> new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));

        PlaceMedia media = placeMediaRepository.findById(mediaId)
                .orElseThrow(() -> new BusinessException("Fotografia não encontrada", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND"));

        if (!media.getPlaceId().equals(placeId)) {
            throw new BusinessException("Fotografia não pertence ao local informado", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND");
        }

        if (!media.isActive()) {
            return;
        }

        boolean isAuthor = media.getUserId().equals(actorUserId);
        boolean isPlaceManager = isAuthorizedPlaceManager(actorUserId, place);
        if (!isAuthor && !isPlaceManager) {
            throw new BusinessException("Acesso restrito ao autor da fotografia, ao responsável pelo local ou a moderadores", HttpStatus.FORBIDDEN, "FORBIDDEN");
        }

        boolean wasPrimary = media.isPrimary();
        media.markRemoved();
        placeMediaRepository.save(media);

        // Se a capa foi removida, elege deterministamente a foto ativa mais antiga como nova capa
        if (wasPrimary) {
            placeMediaRepository.findFirstActiveByPlaceId(placeId).ifPresent(next -> {
                next.setPrimary(true);
                placeMediaRepository.save(next);
            });
        }

        // Se a exclusão partiu de moderador/admin, marca denúncias pendentes como deferidas (ACCEPTED)
        boolean isModerator = userRepository.findById(actorUserId)
                .map(u -> u.getRole() == Role.MODERATOR || u.getRole() == Role.ADMIN)
                .orElse(false);
        if (isModerator) {
            List<PlaceMediaReportJpaEntity> reports = placeMediaReportJpaRepository.findByPlaceMediaId(mediaId);
            for (PlaceMediaReportJpaEntity r : reports) {
                if ("PENDING".equals(r.getStatus())) {
                    r.setStatus("ACCEPTED");
                    r.setUpdatedAt(Instant.now());
                    placeMediaReportJpaRepository.save(r);
                }
            }
        }
    }

    @Transactional
    public void reportMedia(UUID actorUserId, UUID placeId, UUID mediaId, String reason, String detail) {
        if (actorUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (placeId == null || mediaId == null) {
            throw new BusinessException("Identificadores inválidos", HttpStatus.BAD_REQUEST, "INVALID_IDENTIFIERS");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("O motivo da denúncia é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_REPORT_REASON");
        }

        accountStatusPolicy.requireOperational(actorUserId);

        rateLimiter.acquireOrThrow(RateLimitedAction.REPORT_CREATION, RateLimitSubject.ofUser(actorUserId));

        placeRepository.findById(placeId)
                .filter(p -> CatalogService.PUBLIC_CATALOG_STATUS.equals(p.getStatus()))
                .orElseThrow(() -> new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));

        PlaceMedia media = placeMediaRepository.findById(mediaId)
                .orElseThrow(() -> new BusinessException("Fotografia não encontrada", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND"));

        if (!media.getPlaceId().equals(placeId) || !media.isActive()) {
            throw new BusinessException("Fotografia não encontrada para o local informado", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND");
        }

        if (placeMediaReportJpaRepository.existsByPlaceMediaIdAndReporterUserId(mediaId, actorUserId)) {
            throw new BusinessException("Você já denunciou esta fotografia", HttpStatus.CONFLICT, "ALREADY_REPORTED");
        }

        PlaceMediaReportJpaEntity report = new PlaceMediaReportJpaEntity(
                null,
                mediaId,
                actorUserId,
                reason.trim(),
                detail != null ? detail.trim() : null,
                "PENDING",
                Instant.now(),
                Instant.now()
        );
        placeMediaReportJpaRepository.save(report);

        // Limiar determinístico de 3 denúncias de usuários distintos para quarentena preventiva
        long pendingReports = placeMediaReportJpaRepository.countByPlaceMediaIdAndStatus(mediaId, "PENDING");
        if (pendingReports >= 3) {
            boolean wasPrimary = media.isPrimary();
            media.markUnderReview();
            placeMediaRepository.save(media);
            if (wasPrimary) {
                placeMediaRepository.findFirstActiveByPlaceId(placeId).ifPresent(next -> {
                    next.setPrimary(true);
                    placeMediaRepository.save(next);
                });
            }
        }
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
        Optional<PlaceMedia> primary = placeMediaRepository.findPrimaryByPlaceId(placeId);
        if (primary.isPresent() && primary.get().isActive()) {
            return PlaceMediaView.fromDomain(primary.get()).url();
        }
        Optional<PlaceMedia> firstActive = placeMediaRepository.findFirstActiveByPlaceId(placeId);
        return firstActive.map(media -> PlaceMediaView.fromDomain(media).url()).orElse(null);
    }

    public MediaDownloadResult downloadMedia(UUID placeId, UUID mediaId) {
        if (placeId == null || mediaId == null) {
            throw new BusinessException("Identificadores inválidos", HttpStatus.BAD_REQUEST, "INVALID_IDENTIFIERS");
        }

        PlaceMedia media = placeMediaRepository.findById(mediaId)
                .orElseThrow(() -> new BusinessException("Fotografia não encontrada", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND"));

        if (!media.getPlaceId().equals(placeId) || !media.isActive()) {
            throw new BusinessException("Fotografia não encontrada para o local informado", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND");
        }

        // I/O externo de download executado fora de transação de banco de dados (A-08)
        byte[] data = objectStoragePort.get(media.getObjectKey());
        return new MediaDownloadResult(data, media.getMimeType(), true);
    }

    private void requireAuthorizedToUpload(UUID actorUserId, Place place) {
        if (place.getCreatedByUserId() != null && place.getCreatedByUserId().equals(actorUserId)) {
            return;
        }
        if (place.getClaimedByBusinessId() != null && businessAccountRepository != null) {
            boolean isBusinessRep = businessAccountRepository.findById(place.getClaimedByBusinessId())
                    .filter(acc -> acc.isAdministeredBy(actorUserId) && BusinessRepresentationPolicy.isApproved(acc))
                    .isPresent();
            if (isBusinessRep) {
                return;
            }
        }
        if (userRepository != null) {
            boolean isModerator = userRepository.findById(actorUserId)
                    .map(u -> u.getRole() == Role.MODERATOR || u.getRole() == Role.ADMIN)
                    .orElse(false);
            if (isModerator) {
                return;
            }
        }
        throw new BusinessException(
                "Acesso restrito ao criador do local, ao representante do estabelecimento ou a moderadores",
                HttpStatus.FORBIDDEN,
                "FORBIDDEN"
        );
    }

    private boolean isAuthorizedPlaceManager(UUID actorUserId, Place place) {
        if (place.getCreatedByUserId() != null && place.getCreatedByUserId().equals(actorUserId)) {
            return true;
        }
        if (place.getClaimedByBusinessId() != null && businessAccountRepository != null) {
            boolean isBusinessRep = businessAccountRepository.findById(place.getClaimedByBusinessId())
                    .filter(acc -> acc.isAdministeredBy(actorUserId) && BusinessRepresentationPolicy.isApproved(acc))
                    .isPresent();
            if (isBusinessRep) {
                return true;
            }
        }
        if (userRepository != null) {
            return userRepository.findById(actorUserId)
                    .map(u -> u.getRole() == Role.MODERATOR || u.getRole() == Role.ADMIN)
                    .orElse(false);
        }
        return false;
    }

    private String computeSha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algoritmo SHA-256 não disponível", e);
        }
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
