package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ModerationAction;
import com.rewit.domain.enums.ModerationDecision;
import com.rewit.domain.enums.ReviewStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Unidade: Entidade de Domínio ModerationAuditLog (Step 26.1)")
class ModerationAuditLogTest {

    @Test
    @DisplayName("Criar ModerationAuditLog válido com todos os campos e garantir imutabilidade")
    void shouldCreateValidModerationAuditLog() {
        UUID id = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Instant now = Instant.parse("2026-03-01T12:00:00Z");

        ModerationAuditLog log = new ModerationAuditLog(
                id,
                reviewId,
                moderatorId,
                ModerationAction.REMOVE_REVIEW,
                ModerationDecision.ACCEPTED,
                "SPAM",
                "Conteúdo classificado como propaganda indevida",
                ReviewStatus.UNDER_REVIEW,
                ReviewStatus.REMOVED,
                3,
                now
        );

        assertEquals(id, log.getId());
        assertEquals(reviewId, log.getReviewId());
        assertEquals(moderatorId, log.getModeratorUserId());
        assertEquals(ModerationAction.REMOVE_REVIEW, log.getAction());
        assertEquals(ModerationDecision.ACCEPTED, log.getDecision());
        assertEquals("SPAM", log.getReasonCode());
        assertEquals("Conteúdo classificado como propaganda indevida", log.getJustification());
        assertEquals(ReviewStatus.UNDER_REVIEW, log.getPreviousReviewStatus());
        assertEquals(ReviewStatus.REMOVED, log.getNewReviewStatus());
        assertEquals(3, log.getReportsAffectedCount());
        assertEquals(now, log.getCreatedAt());
    }

    @Test
    @DisplayName("Criar ModerationAuditLog sem ID e sem createdAt deve gerar automaticamente")
    void shouldGenerateIdAndCreatedAtWhenNotProvided() {
        UUID reviewId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();

        ModerationAuditLog log = new ModerationAuditLog(
                null,
                reviewId,
                moderatorId,
                ModerationAction.RESTORE_REVIEW,
                ModerationDecision.REJECTED,
                "FALSE_REPORT",
                "Denúncia improcedente, avaliação de acordo com as regras",
                ReviewStatus.UNDER_REVIEW,
                ReviewStatus.ACTIVE,
                1,
                null
        );

        assertNotNull(log.getId());
        assertNotNull(log.getCreatedAt());
        assertEquals(ReviewStatus.ACTIVE, log.getNewReviewStatus());
    }

    @Test
    @DisplayName("Rejeitar reviewId nulo com MISSING_REVIEW_ID")
    void shouldRejectNullReviewId() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                new ModerationAuditLog(
                        null, null, UUID.randomUUID(),
                        ModerationAction.REMOVE_REVIEW, ModerationDecision.ACCEPTED,
                        "SPAM", "Justificativa válida de teste",
                        ReviewStatus.ACTIVE, ReviewStatus.REMOVED, 0, null
                ));

        assertEquals("MISSING_REVIEW_ID", ex.getErrorCode());
    }

    @Test
    @DisplayName("Rejeitar moderatorUserId nulo com MISSING_MODERATOR_ID")
    void shouldRejectNullModeratorId() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                new ModerationAuditLog(
                        null, UUID.randomUUID(), null,
                        ModerationAction.REMOVE_REVIEW, ModerationDecision.ACCEPTED,
                        "SPAM", "Justificativa válida de teste",
                        ReviewStatus.ACTIVE, ReviewStatus.REMOVED, 0, null
                ));

        assertEquals("MISSING_MODERATOR_ID", ex.getErrorCode());
    }

    @Test
    @DisplayName("Rejeitar action nula com MISSING_MODERATION_ACTION")
    void shouldRejectNullAction() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                new ModerationAuditLog(
                        null, UUID.randomUUID(), UUID.randomUUID(),
                        null, ModerationDecision.ACCEPTED,
                        "SPAM", "Justificativa válida de teste",
                        ReviewStatus.ACTIVE, ReviewStatus.REMOVED, 0, null
                ));

        assertEquals("MISSING_MODERATION_ACTION", ex.getErrorCode());
    }

    @Test
    @DisplayName("Rejeitar decision nula com MISSING_MODERATION_DECISION")
    void shouldRejectNullDecision() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                new ModerationAuditLog(
                        null, UUID.randomUUID(), UUID.randomUUID(),
                        ModerationAction.REMOVE_REVIEW, null,
                        "SPAM", "Justificativa válida de teste",
                        ReviewStatus.ACTIVE, ReviewStatus.REMOVED, 0, null
                ));

        assertEquals("MISSING_MODERATION_DECISION", ex.getErrorCode());
    }

    @Test
    @DisplayName("Rejeitar justification nula ou em branco")
    void shouldRejectBlankJustification() {
        BusinessException ex1 = assertThrows(BusinessException.class, () ->
                new ModerationAuditLog(
                        null, UUID.randomUUID(), UUID.randomUUID(),
                        ModerationAction.REMOVE_REVIEW, ModerationDecision.ACCEPTED,
                        "SPAM", null,
                        ReviewStatus.ACTIVE, ReviewStatus.REMOVED, 0, null
                ));
        assertEquals("MISSING_JUSTIFICATION", ex1.getErrorCode());

        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                new ModerationAuditLog(
                        null, UUID.randomUUID(), UUID.randomUUID(),
                        ModerationAction.REMOVE_REVIEW, ModerationDecision.ACCEPTED,
                        "SPAM", "    ",
                        ReviewStatus.ACTIVE, ReviewStatus.REMOVED, 0, null
                ));
        assertEquals("BLANK_JUSTIFICATION", ex2.getErrorCode());
    }

    @Test
    @DisplayName("Rejeitar reportsAffectedCount negativo com INVALID_REPORTS_COUNT")
    void shouldRejectNegativeReportsAffectedCount() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                new ModerationAuditLog(
                        null, UUID.randomUUID(), UUID.randomUUID(),
                        ModerationAction.REMOVE_REVIEW, ModerationDecision.ACCEPTED,
                        "SPAM", "Justificativa válida de teste",
                        ReviewStatus.ACTIVE, ReviewStatus.REMOVED, -1, null
                ));

        assertEquals("INVALID_REPORTS_COUNT", ex.getErrorCode());
    }
}
