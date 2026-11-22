package com.rewit.infrastructure.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Payload canônico imutável submetido ao cálculo e à verificação de HMAC-SHA256 pelo
 * {@link ModerationAuditMacService} (S3-A13-F2).
 *
 * <p>Todos os campos relevantes para integridade do evento/decisão são incluídos em ordem fixa e
 * codificação binária com prefixo de comprimento, eliminando ambiguidade de concatenação.
 */
public record ModerationAuditPayload(
        String domain,
        UUID eventId,
        UUID targetId,
        UUID secondaryTargetId,
        UUID referenceId,
        UUID actorId,
        String actorType,
        String action,
        String decision,
        String previousStatus,
        String newStatus,
        Instant occurredAt,
        String reasonCode,
        String justification,
        Integer reportsAffectedCount
) {

    public static final String DOMAIN_REVIEW_AUDIT = "moderation_audit_logs";
    public static final String DOMAIN_DISCUSSION_AUDIT = "discussion_moderation_audit_logs";
    public static final String DOMAIN_BUSINESS_RESPONSE_AUDIT = "business_response_moderation_audit_logs";
    public static final String DOMAIN_PLACE_MEDIA_AUDIT = "place_media_moderation_audit_logs";
    public static final String DOMAIN_PLACE_CLAIM_AUDIT = "place_claim_moderation_audit_logs";
    public static final String DOMAIN_ACCOUNT_AUDIT = "account_moderation_audit_logs";
    public static final String DOMAIN_PLACE_MEDIA_REPORT_DECISION = "place_media_reports";
    public static final String DOMAIN_PLACE_CLAIM_DECISION = "place_claim_requests";

    public ModerationAuditPayload {
        Objects.requireNonNull(domain, "domain must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
    }

    public static Instant canonicalTimestamp(Instant instant) {
        return (instant != null ? instant : Instant.now()).truncatedTo(ChronoUnit.MICROS);
    }

    public static ModerationAuditPayload forReviewModeration(
            UUID eventId, UUID reviewId, UUID moderatorUserId, String action, String decision,
            String reasonCode, String justification, String previousStatus, String newStatus,
            int reportsAffectedCount, Instant createdAt) {
        return new ModerationAuditPayload(
                DOMAIN_REVIEW_AUDIT, eventId, reviewId, null, null,
                moderatorUserId, "USER", action, decision,
                previousStatus, newStatus, createdAt, reasonCode, justification, reportsAffectedCount);
    }

    public static ModerationAuditPayload forDiscussionModeration(
            UUID eventId, UUID discussionId, UUID moderatorUserId, String action, String decision,
            String reasonCode, String justification, String previousStatus, String newStatus,
            int reportsAffectedCount, Instant createdAt) {
        return new ModerationAuditPayload(
                DOMAIN_DISCUSSION_AUDIT, eventId, discussionId, null, null,
                moderatorUserId, "USER", action, decision,
                previousStatus, newStatus, createdAt, reasonCode, justification, reportsAffectedCount);
    }

    public static ModerationAuditPayload forBusinessResponseModeration(
            UUID eventId, UUID responseId, UUID moderatorUserId, String action,
            String reasonCode, String justification, String previousStatus, String newStatus,
            Instant createdAt) {
        return new ModerationAuditPayload(
                DOMAIN_BUSINESS_RESPONSE_AUDIT, eventId, responseId, null, null,
                moderatorUserId, "USER", action, null,
                previousStatus, newStatus, createdAt, reasonCode, justification, null);
    }

    public static ModerationAuditPayload forPlaceMediaAudit(
            UUID eventId, UUID placeMediaId, UUID placeId, UUID reportId,
            UUID actorUserId, String actorType, String action,
            String reason, String notes, Instant createdAt) {
        return new ModerationAuditPayload(
                DOMAIN_PLACE_MEDIA_AUDIT, eventId, placeMediaId, placeId, reportId,
                actorUserId, actorType, action, null,
                null, null, createdAt, reason, notes, null);
    }

    public static ModerationAuditPayload forPlaceMediaReportDecision(
            UUID reportId, UUID placeMediaId, UUID moderatorUserId,
            String status, String reason, String decisionNotes, Instant decidedAt) {
        return new ModerationAuditPayload(
                DOMAIN_PLACE_MEDIA_REPORT_DECISION, reportId, placeMediaId, null, reportId,
                moderatorUserId, "USER", "DECIDE_PLACE_MEDIA_REPORT", status,
                "PENDING", status, decidedAt, reason, decisionNotes, null);
    }

    public static ModerationAuditPayload forPlaceClaimDecision(
            UUID claimId, UUID placeId, UUID businessAccountId, UUID moderatorUserId,
            String status, String decisionReason, Instant decidedAt) {
        return new ModerationAuditPayload(
                DOMAIN_PLACE_CLAIM_DECISION, claimId, placeId, businessAccountId, claimId,
                moderatorUserId, "USER", "DECIDE_PLACE_CLAIM", status,
                "PENDING", status, decidedAt, null, decisionReason, null);
    }

    public static ModerationAuditPayload forPlaceClaimAudit(
            UUID eventId, UUID claimId, UUID placeId, UUID businessAccountId,
            UUID moderatorUserId, String action, String previousStatus, String newStatus,
            String decisionReason, Instant createdAt) {
        return new ModerationAuditPayload(
                DOMAIN_PLACE_CLAIM_AUDIT, eventId, claimId, placeId, businessAccountId,
                moderatorUserId, "USER", action, newStatus,
                previousStatus, newStatus, createdAt, null, decisionReason, null);
    }

    public static ModerationAuditPayload forAccountLifecycleAudit(
            UUID eventId, UUID targetUserId, UUID actorUserId, String action,
            String previousStatus, String newStatus, String reason, Instant createdAt) {
        return new ModerationAuditPayload(
                DOMAIN_ACCOUNT_AUDIT, eventId, targetUserId, null, null,
                actorUserId, "USER", action, null,
                previousStatus, newStatus, createdAt, null, reason, null);
    }
}
