package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Unidade: Mutações de Resolução de Denúncia em Report (Step 26.1)")
class ReportResolutionDomainTest {

    private Report createPendingReport() {
        return new Report(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ReportReason.SPAM,
                "Conteúdo impróprio de teste",
                ReportStatus.PENDING,
                Instant.parse("2026-03-01T10:00:00Z"),
                Instant.parse("2026-03-01T10:00:00Z")
        );
    }

    @Test
    @DisplayName("resolveAsAccepted deve permitir transição PENDING -> ACCEPTED com atualização de updatedAt")
    void shouldResolvePendingReportAsAccepted() {
        Report report = createPendingReport();
        Instant now = Instant.parse("2026-03-01T12:00:00Z");

        report.resolveAsAccepted(now);

        assertEquals(ReportStatus.ACCEPTED, report.getStatus());
        assertEquals(now, report.getUpdatedAt());
        assertEquals(Instant.parse("2026-03-01T10:00:00Z"), report.getCreatedAt());
    }

    @Test
    @DisplayName("resolveAsRejected deve permitir transição PENDING -> REJECTED com atualização de updatedAt")
    void shouldResolvePendingReportAsRejected() {
        Report report = createPendingReport();
        Instant now = Instant.parse("2026-03-01T12:00:00Z");

        report.resolveAsRejected(now);

        assertEquals(ReportStatus.REJECTED, report.getStatus());
        assertEquals(now, report.getUpdatedAt());
        assertEquals(Instant.parse("2026-03-01T10:00:00Z"), report.getCreatedAt());
    }

    @Test
    @DisplayName("resolveAsAccepted deve rejeitar denúncia já resolvida como ACCEPTED")
    void shouldRejectResolvingAlreadyAcceptedReport() {
        Report report = createPendingReport();
        Instant time1 = Instant.parse("2026-03-01T11:00:00Z");
        report.resolveAsAccepted(time1);

        Instant time2 = Instant.parse("2026-03-01T12:00:00Z");
        BusinessException ex = assertThrows(BusinessException.class, () ->
                report.resolveAsAccepted(time2));

        assertEquals("REPORT_ALREADY_RESOLVED", ex.getErrorCode());
        assertEquals("A denúncia já se encontra resolvida", ex.getMessage());
    }

    @Test
    @DisplayName("resolveAsRejected deve rejeitar denúncia já resolvida como ACCEPTED")
    void shouldRejectRejectingAlreadyAcceptedReport() {
        Report report = createPendingReport();
        Instant time1 = Instant.parse("2026-03-01T11:00:00Z");
        report.resolveAsAccepted(time1);

        Instant time2 = Instant.parse("2026-03-01T12:00:00Z");
        BusinessException ex = assertThrows(BusinessException.class, () ->
                report.resolveAsRejected(time2));

        assertEquals("REPORT_ALREADY_RESOLVED", ex.getErrorCode());
    }

    @Test
    @DisplayName("resolveAsAccepted deve rejeitar denúncia já resolvida como REJECTED")
    void shouldRejectAcceptingAlreadyRejectedReport() {
        Report report = createPendingReport();
        Instant time1 = Instant.parse("2026-03-01T11:00:00Z");
        report.resolveAsRejected(time1);

        Instant time2 = Instant.parse("2026-03-01T12:00:00Z");
        BusinessException ex = assertThrows(BusinessException.class, () ->
                report.resolveAsAccepted(time2));

        assertEquals("REPORT_ALREADY_RESOLVED", ex.getErrorCode());
    }

    @Test
    @DisplayName("resolveAsRejected deve rejeitar denúncia já resolvida como REJECTED")
    void shouldRejectRejectingAlreadyRejectedReport() {
        Report report = createPendingReport();
        Instant time1 = Instant.parse("2026-03-01T11:00:00Z");
        report.resolveAsRejected(time1);

        Instant time2 = Instant.parse("2026-03-01T12:00:00Z");
        BusinessException ex = assertThrows(BusinessException.class, () ->
                report.resolveAsRejected(time2));

        assertEquals("REPORT_ALREADY_RESOLVED", ex.getErrorCode());
    }

    @Test
    @DisplayName("resolveAsAccepted deve rejeitar timestamp nulo com MISSING_UPDATE_TIMESTAMP")
    void shouldRejectNullTimestampOnAccept() {
        Report report = createPendingReport();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                report.resolveAsAccepted(null));

        assertEquals("MISSING_UPDATE_TIMESTAMP", ex.getErrorCode());
    }

    @Test
    @DisplayName("resolveAsRejected deve rejeitar timestamp nulo com MISSING_UPDATE_TIMESTAMP")
    void shouldRejectNullTimestampOnReject() {
        Report report = createPendingReport();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                report.resolveAsRejected(null));

        assertEquals("MISSING_UPDATE_TIMESTAMP", ex.getErrorCode());
    }
}
