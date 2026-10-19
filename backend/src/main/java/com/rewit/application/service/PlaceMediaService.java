package com.rewit.application.service;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.media.MediaDtos.MediaDownloadResult;
import com.rewit.application.dto.media.MediaDtos.PlaceMediaView;
import com.rewit.application.dto.media.MediaDtos.UploadPlaceMediaCommand;
import com.rewit.application.dto.media.PlaceMediaModerationDtos.PlaceMediaModerationResult;
import com.rewit.application.dto.media.PlaceMediaModerationDtos.PlaceMediaReportView;
import com.rewit.application.port.BusinessAccountRepository;
import com.rewit.application.port.ObjectStoragePort;
import com.rewit.application.port.PlaceMediaModerationAuditLogRepository;
import com.rewit.application.port.PlaceMediaReportRepository;
import com.rewit.application.port.PlaceMediaRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateLimiter;
import com.rewit.application.port.UserRepository;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.PlaceMediaModerationAction;
import com.rewit.domain.enums.PlaceMediaStatus;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.enums.Role;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceMedia;
import com.rewit.domain.model.PlaceMediaModerationAuditLog;
import com.rewit.domain.model.PlaceMediaReport;
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
    private final PlaceMediaReportRepository placeMediaReportRepository;
    private final PlaceMediaModerationAuditLogRepository placeMediaModerationAuditLogRepository;
    private final ModeratorRolePolicy moderatorRolePolicy;

    public PlaceMediaService(PlaceRepository placeRepository,
                             PlaceMediaRepository placeMediaRepository,
                             AccountStatusPolicy accountStatusPolicy,
                             ObjectStoragePort objectStoragePort,
                             ImageSanitizer imageSanitizer,
                             RateLimiter rateLimiter) {
        this(placeRepository, placeMediaRepository, accountStatusPolicy, objectStoragePort, imageSanitizer, rateLimiter,
                null, null, null, null, null);
    }

    public PlaceMediaService(PlaceRepository placeRepository,
                             PlaceMediaRepository placeMediaRepository,
                             AccountStatusPolicy accountStatusPolicy,
                             ObjectStoragePort objectStoragePort,
                             ImageSanitizer imageSanitizer,
                             RateLimiter rateLimiter,
                             BusinessAccountRepository businessAccountRepository,
                             UserRepository userRepository,
                             PlaceMediaReportRepository placeMediaReportRepository) {
        this(placeRepository, placeMediaRepository, accountStatusPolicy, objectStoragePort, imageSanitizer, rateLimiter,
                businessAccountRepository, userRepository, placeMediaReportRepository, null, null);
    }

    public PlaceMediaService(PlaceRepository placeRepository,
                             PlaceMediaRepository placeMediaRepository,
                             AccountStatusPolicy accountStatusPolicy,
                             ObjectStoragePort objectStoragePort,
                             ImageSanitizer imageSanitizer,
                             RateLimiter rateLimiter,
                             BusinessAccountRepository businessAccountRepository,
                             UserRepository userRepository,
                             PlaceMediaReportRepository placeMediaReportRepository,
                             PlaceMediaModerationAuditLogRepository placeMediaModerationAuditLogRepository) {
        this(placeRepository, placeMediaRepository, accountStatusPolicy, objectStoragePort, imageSanitizer, rateLimiter,
                businessAccountRepository, userRepository, placeMediaReportRepository, placeMediaModerationAuditLogRepository, null);
    }

    @Autowired
    public PlaceMediaService(PlaceRepository placeRepository,
                             PlaceMediaRepository placeMediaRepository,
                             AccountStatusPolicy accountStatusPolicy,
                             ObjectStoragePort objectStoragePort,
                             ImageSanitizer imageSanitizer,
                             RateLimiter rateLimiter,
                             @Autowired(required = false) BusinessAccountRepository businessAccountRepository,
                             @Autowired(required = false) UserRepository userRepository,
                             @Autowired(required = false) PlaceMediaReportRepository placeMediaReportRepository,
                             @Autowired(required = false) PlaceMediaModerationAuditLogRepository placeMediaModerationAuditLogRepository,
                             @Autowired(required = false) ModeratorRolePolicy moderatorRolePolicy) {
        this.placeRepository = Objects.requireNonNull(placeRepository, "PlaceRepository must not be null");
        this.placeMediaRepository = Objects.requireNonNull(placeMediaRepository, "PlaceMediaRepository must not be null");
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
        this.objectStoragePort = Objects.requireNonNull(objectStoragePort, "ObjectStoragePort must not be null");
        this.imageSanitizer = Objects.requireNonNull(imageSanitizer, "ImageSanitizer must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "RateLimiter must not be null");
        this.businessAccountRepository = businessAccountRepository;
        this.userRepository = userRepository;
        this.placeMediaReportRepository = placeMediaReportRepository;
        this.placeMediaModerationAuditLogRepository = placeMediaModerationAuditLogRepository;
        this.moderatorRolePolicy = moderatorRolePolicy != null
                ? moderatorRolePolicy
                : (userRepository != null ? new ModeratorRolePolicy(accountStatusPolicy, userRepository) : null);
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
        String idempotencyKey = null;
        if (cmd.idempotencyKey() != null && !cmd.idempotencyKey().isBlank()) {
            idempotencyKey = cmd.idempotencyKey().trim();
            if (idempotencyKey.length() > 128) {
                throw new BusinessException("Chave de idempotência excede o limite de 128 caracteres", HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
            }
            Optional<PlaceMedia> existing = placeMediaRepository.findByIdempotencyKey(
                    cmd.authenticatedUserId(), place.getId(), idempotencyKey);
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
                PlaceMediaStatus.ACTIVE,
                isPrimary,
                idempotencyKey,
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

        PlaceMedia media = placeMediaRepository.findByIdForUpdate(mediaId)
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

        PlaceMedia media = placeMediaRepository.findByIdForUpdate(mediaId)
                .orElseThrow(() -> new BusinessException("Fotografia não encontrada", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND"));

        if (!media.getPlaceId().equals(placeId)) {
            throw new BusinessException("Fotografia não pertence ao local informado", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND");
        }

        if (!media.isActive() && !media.isUnderReview()) {
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
        boolean isModerator = userRepository != null && userRepository.findById(actorUserId)
                .map(u -> u.getRole() == Role.MODERATOR || u.getRole() == Role.ADMIN)
                .orElse(false);
        if (isModerator && placeMediaReportRepository != null) {
            List<PlaceMediaReport> reports = placeMediaReportRepository.findByPlaceMediaIdForUpdate(mediaId);
            for (PlaceMediaReport r : reports) {
                if (r.isPending()) {
                    r.accept(actorUserId, "Fotografia excluída pelo moderador");
                    placeMediaReportRepository.save(r);
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

        // Locks na ordem estrita: Place -> PlaceMedia
        placeRepository.findByIdForUpdate(placeId)
                .filter(p -> CatalogService.PUBLIC_CATALOG_STATUS.equals(p.getStatus()))
                .orElseThrow(() -> new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));

        PlaceMedia media = placeMediaRepository.findByIdForUpdate(mediaId)
                .orElseThrow(() -> new BusinessException("Fotografia não encontrada", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND"));

        if (!media.getPlaceId().equals(placeId) || (!media.isActive() && !media.isUnderReview())) {
            throw new BusinessException("Fotografia não encontrada para o local informado", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND");
        }

        if (media.getUserId().equals(actorUserId)) {
            throw new BusinessException("O autor não pode denunciar a própria fotografia", HttpStatus.BAD_REQUEST, "SELF_REPORT_FORBIDDEN");
        }

        if (placeMediaReportRepository == null) {
            throw new BusinessException("Repositório de denúncias indisponível", HttpStatus.INTERNAL_SERVER_ERROR, "UNAVAILABLE");
        }

        if (placeMediaReportRepository.existsByPlaceMediaIdAndReporterUserId(mediaId, actorUserId)) {
            throw new BusinessException("Você já denunciou esta fotografia", HttpStatus.CONFLICT, "ALREADY_REPORTED");
        }

        PlaceMediaReport report = new PlaceMediaReport(
                UUID.randomUUID(),
                mediaId,
                actorUserId,
                reason.trim(),
                detail != null ? detail.trim() : null
        );
        placeMediaReportRepository.save(report);

        // Limiar determinístico de 3 denúncias de usuários distintos para quarentena preventiva
        long pendingReports = placeMediaReportRepository.countByPlaceMediaIdAndStatus(mediaId, PlaceMediaReport.STATUS_PENDING);
        if (pendingReports >= 3 && media.isActive()) {
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
    public PageResult<PlaceMediaReportView> listReports(UUID moderatorUserId, String status, int page, int size) {
        requireModerator(moderatorUserId);

        if (placeMediaReportRepository == null) {
            return PageResult.of(List.of(), 0, size, 0);
        }
        int safePage = Math.max(page, 0);
        int safeSize = (size <= 0 || size > 100) ? 20 : size;

        PageResult<PlaceMediaReport> reportsPage = placeMediaReportRepository.findByStatus(status, safePage, safeSize);

        List<PlaceMediaReportView> dtoList = reportsPage.content().stream()
                .map(report -> {
                    PlaceMedia media = placeMediaRepository.findById(report.getPlaceMediaId()).orElse(null);
                    return PlaceMediaReportView.of(report, media);
                })
                .toList();

        return PageResult.of(dtoList, reportsPage.pageNumber(), reportsPage.pageSize(), reportsPage.totalElements());
    }

    @Transactional(readOnly = true)
    public PlaceMediaReportView getReport(UUID moderatorUserId, UUID reportId) {
        if (reportId == null) {
            throw new BusinessException("Identificador de denúncia inválido", HttpStatus.BAD_REQUEST, "INVALID_REPORT_ID");
        }
        requireModerator(moderatorUserId);

        if (placeMediaReportRepository == null) {
            throw new BusinessException("Repositório de denúncias indisponível", HttpStatus.INTERNAL_SERVER_ERROR, "UNAVAILABLE");
        }
        PlaceMediaReport report = placeMediaReportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException("Denúncia não encontrada", HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND"));

        PlaceMedia media = placeMediaRepository.findById(report.getPlaceMediaId()).orElse(null);
        return PlaceMediaReportView.of(report, media);
    }

    @Transactional
    public PlaceMediaReportView decideReport(UUID moderatorUserId, UUID reportId, String decision, String notes) {
        if (reportId == null) {
            throw new BusinessException("Identificador de denúncia inválido", HttpStatus.BAD_REQUEST, "INVALID_REPORT_ID");
        }
        if (decision == null || (!decision.equalsIgnoreCase("ACCEPT") && !decision.equalsIgnoreCase("REJECT"))) {
            throw new BusinessException("Decisão inválida. Utilize ACCEPT ou REJECT", HttpStatus.BAD_REQUEST, "INVALID_DECISION");
        }
        requireModerator(moderatorUserId);

        if (placeMediaReportRepository == null) {
            throw new BusinessException("Repositório de denúncias indisponível", HttpStatus.INTERNAL_SERVER_ERROR, "UNAVAILABLE");
        }

        // Leitura sem lock para descobrir as chaves de relacionamento
        PlaceMediaReport initialReport = placeMediaReportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException("Denúncia não encontrada", HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND"));

        if (!initialReport.isPending()) {
            throw new BusinessException("A denúncia já foi decidida anteriormente", HttpStatus.CONFLICT, "REPORT_ALREADY_DECIDED");
        }

        PlaceMedia initialMedia = placeMediaRepository.findById(initialReport.getPlaceMediaId())
                .orElseThrow(() -> new BusinessException("Fotografia não encontrada", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND"));

        // Adquire locks pessimistas rigorosamente na ordem hierárquica global: Place -> PlaceMedia -> PlaceMediaReport
        placeRepository.findByIdForUpdate(initialMedia.getPlaceId())
                .orElseThrow(() -> new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));

        PlaceMedia lockedMedia = placeMediaRepository.findByIdForUpdate(initialMedia.getId())
                .orElseThrow(() -> new BusinessException("Fotografia não encontrada", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND"));

        PlaceMediaReport lockedReport = placeMediaReportRepository.findByIdForUpdate(reportId)
                .orElseThrow(() -> new BusinessException("Denúncia não encontrada", HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND"));

        // Revalidação sob lock pessimista para mitigar lost updates e decisões concorrentes
        if (!lockedReport.isPending()) {
            throw new BusinessException("A denúncia já foi decidida anteriormente", HttpStatus.CONFLICT, "REPORT_ALREADY_DECIDED");
        }

        if (decision.equalsIgnoreCase("ACCEPT")) {
            lockedReport.accept(moderatorUserId, notes);
            placeMediaReportRepository.save(lockedReport);

            if (lockedMedia.isActive() || lockedMedia.isUnderReview()) {
                boolean wasPrimary = lockedMedia.isPrimary();
                lockedMedia.markRemoved();
                placeMediaRepository.save(lockedMedia);

                if (wasPrimary) {
                    placeMediaRepository.findFirstActiveByPlaceId(lockedMedia.getPlaceId()).ifPresent(next -> {
                        next.setPrimary(true);
                        placeMediaRepository.save(next);
                    });
                }
            }

            // Aceita também sob lock outras denúncias pendentes para a mesma fotografia
            List<PlaceMediaReport> siblings = placeMediaReportRepository.findByPlaceMediaIdForUpdate(lockedMedia.getId());
            for (PlaceMediaReport sib : siblings) {
                if (sib.isPending() && !sib.getId().equals(lockedReport.getId())) {
                    sib.accept(moderatorUserId, notes != null ? notes : "Deferida em conjunto");
                    placeMediaReportRepository.save(sib);
                }
            }

            saveAuditLog(lockedMedia.getId(), lockedMedia.getPlaceId(), moderatorUserId,
                    PlaceMediaModerationAction.ACCEPT_REPORT, "REPORT", notes);
        } else {
            // Decisão REJECT: marca a denúncia individual como rejeitada e registra auditoria.
            // A-2: Rejeição de denúncia individual NUNCA reativa a fotografia automaticamente.
            lockedReport.reject(moderatorUserId, notes);
            placeMediaReportRepository.save(lockedReport);

            saveAuditLog(lockedMedia.getId(), lockedMedia.getPlaceId(), moderatorUserId,
                    PlaceMediaModerationAction.REJECT_REPORT, "REPORT", notes);
        }

        return PlaceMediaReportView.of(lockedReport, lockedMedia);
    }

    @Transactional
    public PlaceMediaModerationResult removeMediaByModerator(UUID moderatorUserId, UUID placeId, UUID mediaId, String reason, String notes) {
        if (placeId == null || mediaId == null) {
            throw new BusinessException("Identificadores inválidos", HttpStatus.BAD_REQUEST, "INVALID_IDENTIFIERS");
        }
        requireModerator(moderatorUserId);

        // Locks na ordem hierárquica global: Place -> PlaceMedia
        placeRepository.findByIdForUpdate(placeId)
                .orElseThrow(() -> new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));

        PlaceMedia lockedMedia = placeMediaRepository.findByIdForUpdate(mediaId)
                .orElseThrow(() -> new BusinessException("Fotografia não encontrada", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND"));

        if (!lockedMedia.getPlaceId().equals(placeId)) {
            throw new BusinessException("Fotografia não pertence ao local informado", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND");
        }

        if (!lockedMedia.isRemoved()) {
            boolean wasPrimary = lockedMedia.isPrimary();
            lockedMedia.markRemoved();
            placeMediaRepository.save(lockedMedia);

            if (wasPrimary) {
                placeMediaRepository.findFirstActiveByPlaceId(placeId).ifPresent(next -> {
                    next.setPrimary(true);
                    placeMediaRepository.save(next);
                });
            }

            if (placeMediaReportRepository != null) {
                List<PlaceMediaReport> reports = placeMediaReportRepository.findByPlaceMediaIdForUpdate(mediaId);
                for (PlaceMediaReport r : reports) {
                    if (r.isPending()) {
                        r.accept(moderatorUserId, notes != null ? notes : "Remoção administrativa direta");
                        placeMediaReportRepository.save(r);
                    }
                }
            }

            saveAuditLog(mediaId, placeId, moderatorUserId, PlaceMediaModerationAction.REMOVE, reason, notes);
        }

        return new PlaceMediaModerationResult(
                mediaId,
                placeId,
                lockedMedia.getStatus().name(),
                lockedMedia.isPrimary(),
                "Fotografia removida administrativamente"
        );
    }

    @Transactional
    public PlaceMediaModerationResult restoreMediaByModerator(UUID moderatorUserId, UUID placeId, UUID mediaId, String notes) {
        if (placeId == null || mediaId == null) {
            throw new BusinessException("Identificadores inválidos", HttpStatus.BAD_REQUEST, "INVALID_IDENTIFIERS");
        }
        requireModerator(moderatorUserId);

        // Locks na ordem hierárquica global: Place -> PlaceMedia
        placeRepository.findByIdForUpdate(placeId)
                .orElseThrow(() -> new BusinessException("Local não encontrado", HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND"));

        PlaceMedia lockedMedia = placeMediaRepository.findByIdForUpdate(mediaId)
                .orElseThrow(() -> new BusinessException("Fotografia não encontrada", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND"));

        if (!lockedMedia.getPlaceId().equals(placeId)) {
            throw new BusinessException("Fotografia não pertence ao local informado", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND");
        }

        if (lockedMedia.isPurged() || !objectStoragePort.exists(lockedMedia.getObjectKey())) {
            throw new BusinessException("Não é possível restaurar a fotografia pois o arquivo já foi excluído do storage", HttpStatus.CONFLICT, "STORAGE_OBJECT_PURGED");
        }

        // A-2: Validar atomicamente sob lock que o local não excede o limite máximo de fotografias ativas
        long activeCount = placeMediaRepository.countActiveByPlaceId(placeId);
        if (activeCount >= PlaceMedia.MAX_MEDIA_PER_PLACE) {
            throw new BusinessException("Limite máximo de " + PlaceMedia.MAX_MEDIA_PER_PLACE + " fotografias ativas por local já foi atingido",
                    HttpStatus.CONFLICT, "PLACE_MEDIA_LIMIT_EXCEEDED");
        }

        lockedMedia.markActive();

        Optional<PlaceMedia> currentPrimary = placeMediaRepository.findPrimaryByPlaceId(placeId);
        if (currentPrimary.isEmpty()) {
            Optional<PlaceMedia> firstActive = placeMediaRepository.findFirstActiveByPlaceId(placeId);
            if (firstActive.isEmpty() || firstActive.get().getId().equals(lockedMedia.getId())) {
                lockedMedia.setPrimary(true);
            }
        }
        placeMediaRepository.save(lockedMedia);

        saveAuditLog(mediaId, placeId, moderatorUserId, PlaceMediaModerationAction.RESTORE, null, notes);

        return new PlaceMediaModerationResult(
                mediaId,
                placeId,
                lockedMedia.getStatus().name(),
                lockedMedia.isPrimary(),
                "Fotografia restaurada com sucesso"
        );
    }

    public MediaDownloadResult downloadMediaForModerator(UUID moderatorUserId, UUID placeId, UUID mediaId) {
        if (placeId == null || mediaId == null) {
            throw new BusinessException("Identificadores inválidos", HttpStatus.BAD_REQUEST, "INVALID_IDENTIFIERS");
        }
        requireModerator(moderatorUserId);

        PlaceMedia media = placeMediaRepository.findById(mediaId)
                .orElseThrow(() -> new BusinessException("Fotografia não encontrada", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND"));

        if (!media.getPlaceId().equals(placeId)) {
            throw new BusinessException("Fotografia não pertence ao local informado", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND");
        }

        if (media.isPurged()) {
            throw new BusinessException("A fotografia foi purgada permanentemente", HttpStatus.NOT_FOUND, "MEDIA_PURGED");
        }

        byte[] data = objectStoragePort.get(media.getObjectKey());
        return new MediaDownloadResult(data, media.getMimeType(), false);
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

    private void requireModerator(UUID actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (moderatorRolePolicy != null) {
            moderatorRolePolicy.requireCurrentModerator(actorUserId);
        } else {
            accountStatusPolicy.requireOperational(actorUserId);
            if (userRepository != null) {
                boolean isMod = userRepository.findById(actorUserId)
                        .map(u -> u.getRole() == Role.MODERATOR || u.getRole() == Role.ADMIN)
                        .orElse(false);
                if (!isMod) {
                    throw new BusinessException("Acesso restrito a moderadores e administradores", HttpStatus.FORBIDDEN, "FORBIDDEN");
                }
            }
        }
    }

    private void saveAuditLog(UUID mediaId, UUID placeId, UUID moderatorUserId,
                              PlaceMediaModerationAction action, String reason, String notes) {
        if (placeMediaModerationAuditLogRepository != null) {
            try {
                placeMediaModerationAuditLogRepository.save(new PlaceMediaModerationAuditLog(
                        UUID.randomUUID(),
                        mediaId,
                        placeId,
                        moderatorUserId,
                        action,
                        reason,
                        notes,
                        Instant.now()
                ));
            } catch (RuntimeException e) {
                log.warn("Falha ao registrar auditoria de moderação de mídia (mediaId={}, erro={})",
                        mediaId, e.getClass().getSimpleName());
            }
        }
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
