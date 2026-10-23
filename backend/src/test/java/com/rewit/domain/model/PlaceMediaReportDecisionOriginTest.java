package com.rewit.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("LEG-2: proveniência das decisões de denúncias de fotografias")
class PlaceMediaReportDecisionOriginTest {

    private static PlaceMediaReport report(String status, UUID decidedBy, boolean legacy) {
        Instant now = Instant.now();
        return new PlaceMediaReport(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SPAM", null, status,
                now, now, decidedBy, now, null, legacy);
    }

    @Test
    @DisplayName("Pendente não tem proveniência nem decisor")
    void pendingHasNoOrigin() {
        PlaceMediaReport pending = new PlaceMediaReport(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SPAM", null);
        assertNull(pending.getDecisionOrigin());
        assertNull(pending.getVerifiedDeciderUserId());
        assertFalse(pending.isLegacyDecision());
    }

    @Test
    @DisplayName("Decisão humana validada expõe o moderador")
    void validatedHumanDecision() {
        UUID moderator = UUID.randomUUID();
        PlaceMediaReport accepted = report(PlaceMediaReport.STATUS_ACCEPTED, moderator, false);
        assertEquals(PlaceMediaReport.DECISION_ORIGIN_MODERATOR, accepted.getDecisionOrigin());
        assertEquals(moderator, accepted.getVerifiedDeciderUserId());
    }

    @Test
    @DisplayName("Decisão legada nunca é apresentada como decisão de moderador, mesmo com decisor registrado")
    void legacyDecisionHidesUnverifiedDecider() {
        UUID recorded = UUID.randomUUID();
        PlaceMediaReport legacy = report(PlaceMediaReport.STATUS_REJECTED, recorded, true);
        assertEquals(PlaceMediaReport.DECISION_ORIGIN_LEGACY, legacy.getDecisionOrigin());
        assertNull(legacy.getVerifiedDeciderUserId());
        assertEquals(recorded, legacy.getDecidedByUserId(), "O valor histórico registrado é preservado no domínio");
    }

    @Test
    @DisplayName("Resolução automática pertence ao sistema")
    void autoResolvedBelongsToSystem() {
        PlaceMediaReport pending = new PlaceMediaReport(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SPAM", null);
        pending.autoResolve("Mídia removida pelo autor");
        assertEquals(PlaceMediaReport.DECISION_ORIGIN_SYSTEM, pending.getDecisionOrigin());
        assertNull(pending.getVerifiedDeciderUserId());
    }
}
