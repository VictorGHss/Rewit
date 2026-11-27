package com.rewit.infrastructure.security;

import com.rewit.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("S3-A13-F2: autenticidade criptográfica HMAC-SHA256 de auditoria (ModerationAuditMacService)")
class ModerationAuditMacServiceUnitTest {

    private static final String SECRET_V1 = Base64.getEncoder().encodeToString(seqBytes((byte) 0x11, 32));
    private static final String SECRET_V2 = Base64.getEncoder().encodeToString(seqBytes((byte) 0x22, 32));
    private static final String JWT_SECRET = Base64.getEncoder().encodeToString(seqBytes((byte) 0x33, 32));
    private static final String PEPPER_SECRET = Base64.getEncoder().encodeToString(seqBytes((byte) 0x44, 32));
    private static final String REFRESH_SECRET = Base64.getEncoder().encodeToString(seqBytes((byte) 0x55, 32));

    private static byte[] seqBytes(byte seed, int len) {
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) (seed + i * 7);
        }
        return out;
    }

    private static ModerationAuditPayload samplePayload() {
        return new ModerationAuditPayload(
                ModerationAuditPayload.DOMAIN_REVIEW_AUDIT,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                "USER",
                "REMOVE_REVIEW",
                "ACCEPTED",
                "UNDER_REVIEW",
                "REMOVED",
                Instant.parse("2026-05-01T12:34:56.123456Z"),
                "SPAM",
                "Conteúdo comprovadamente fraudulento após análise.",
                3
        );
    }

    @Test
    @DisplayName("Calcula e verifica MAC canônico determinístico no formato aud1$<keyId>$<hex>")
    void computesAndVerifiesCanonicalMacDeterministically() {
        ModerationAuditMacService service = new ModerationAuditMacService(SECRET_V1, "v1", "", "");
        ModerationAuditPayload payload = samplePayload();

        String mac1 = service.computeMac(payload);
        String mac2 = service.computeMac(payload);

        assertEquals(mac1, mac2);
        assertTrue(mac1.matches("^aud1\\$v1\\$[0-9a-f]{64}$"), "MAC fora do formato canônico: " + mac1);
        assertEquals(AuditVerificationStatus.VERIFIED, service.verify(payload, mac1));
    }

    @Test
    @DisplayName("Serialização binária com prefixo de comprimento é imune a injeção de delimitador e distingue null de vazio")
    void canonicalSerializationPreventsDelimiterAmbiguity() {
        ModerationAuditMacService service = new ModerationAuditMacService(SECRET_V1, "v1", "", "");
        ModerationAuditPayload base = samplePayload();

        ModerationAuditPayload p1 = new ModerationAuditPayload(
                base.domain(), base.eventId(), base.targetId(), base.secondaryTargetId(), base.referenceId(),
                base.actorId(), base.actorType(), base.action(), base.decision(), base.previousStatus(),
                base.newStatus(), base.occurredAt(), "A|B", "C", base.reportsAffectedCount());
        ModerationAuditPayload p2 = new ModerationAuditPayload(
                base.domain(), base.eventId(), base.targetId(), base.secondaryTargetId(), base.referenceId(),
                base.actorId(), base.actorType(), base.action(), base.decision(), base.previousStatus(),
                base.newStatus(), base.occurredAt(), "A", "B|C", base.reportsAffectedCount());
        ModerationAuditPayload pNull = new ModerationAuditPayload(
                base.domain(), base.eventId(), base.targetId(), base.secondaryTargetId(), base.referenceId(),
                base.actorId(), base.actorType(), base.action(), base.decision(), base.previousStatus(),
                base.newStatus(), base.occurredAt(), null, "", base.reportsAffectedCount());
        ModerationAuditPayload pEmpty = new ModerationAuditPayload(
                base.domain(), base.eventId(), base.targetId(), base.secondaryTargetId(), base.referenceId(),
                base.actorId(), base.actorType(), base.action(), base.decision(), base.previousStatus(),
                base.newStatus(), base.occurredAt(), "", null, base.reportsAffectedCount());

        assertFalse(Arrays.equals(ModerationAuditMacService.serializeCanonical(p1), ModerationAuditMacService.serializeCanonical(p2)));
        assertFalse(Arrays.equals(ModerationAuditMacService.serializeCanonical(pNull), ModerationAuditMacService.serializeCanonical(pEmpty)));
        assertNotEquals(service.computeMac(p1), service.computeMac(p2));
        assertNotEquals(service.computeMac(pNull), service.computeMac(pEmpty));
    }

    @Test
    @DisplayName("Truncagem canônica para microssegundos garante estabilidade de round-trip PostgreSQL TIMESTAMPTZ")
    void microsecondTruncationIsDeterministic() {
        ModerationAuditMacService service = new ModerationAuditMacService(SECRET_V1, "v1", "", "");
        ModerationAuditPayload base = samplePayload();

        ModerationAuditPayload withSubMicros = new ModerationAuditPayload(
                base.domain(), base.eventId(), base.targetId(), base.secondaryTargetId(), base.referenceId(),
                base.actorId(), base.actorType(), base.action(), base.decision(), base.previousStatus(),
                base.newStatus(), Instant.parse("2026-05-01T12:34:56.123456789Z"),
                base.reasonCode(), base.justification(), base.reportsAffectedCount());
        ModerationAuditPayload nextMicro = new ModerationAuditPayload(
                base.domain(), base.eventId(), base.targetId(), base.secondaryTargetId(), base.referenceId(),
                base.actorId(), base.actorType(), base.action(), base.decision(), base.previousStatus(),
                base.newStatus(), Instant.parse("2026-05-01T12:34:56.123457Z"),
                base.reasonCode(), base.justification(), base.reportsAffectedCount());

        assertEquals(service.computeMac(base), service.computeMac(withSubMicros));
        assertNotEquals(service.computeMac(base), service.computeMac(nextMicro));
    }

    @Test
    @DisplayName("MAC nulo representa registro histórico pré-V45 (HISTORICAL_UNAUTHENTICATED), nunca VERIFIED")
    void nullMacIsHistoricalUnauthenticated() {
        ModerationAuditMacService service = new ModerationAuditMacService(SECRET_V1, "v1", "", "");
        assertEquals(AuditVerificationStatus.HISTORICAL_UNAUTHENTICATED, service.verify(samplePayload(), null));
    }

    @Test
    @DisplayName("MACs malformados ou adulterados retornam INVALID")
    void malformedOrTamperedMacsAreInvalid() {
        ModerationAuditMacService service = new ModerationAuditMacService(SECRET_V1, "v1", "", "");
        ModerationAuditPayload payload = samplePayload();
        String validMac = service.computeMac(payload);

        // Alterar 1 caractere no digest hexadecimal
        char lastChar = validMac.charAt(validMac.length() - 1);
        String flippedMac = validMac.substring(0, validMac.length() - 1) + (lastChar == '0' ? '1' : '0');

        assertEquals(AuditVerificationStatus.INVALID, service.verify(payload, flippedMac));
        assertEquals(AuditVerificationStatus.INVALID, service.verify(payload, ""));
        assertEquals(AuditVerificationStatus.INVALID, service.verify(payload, "   "));
        assertEquals(AuditVerificationStatus.INVALID, service.verify(payload, "aud2$v1$" + "a".repeat(64)));
        assertEquals(AuditVerificationStatus.INVALID, service.verify(payload, "aud1$v1$" + "A".repeat(64)));
        assertEquals(AuditVerificationStatus.INVALID, service.verify(payload, "aud1$v1$" + "a".repeat(63)));
        assertEquals(AuditVerificationStatus.INVALID, service.verify(payload, "aud1$bad$id$" + "a".repeat(64)));
        assertEquals(AuditVerificationStatus.INVALID, service.verify(payload, "aud1$unknown$" + validMac.substring("aud1$v1$".length())));
    }

    @Test
    @DisplayName("Adulteração em qualquer um dos 15 campos do payload invalida o MAC")
    void tamperingAnyFieldInvalidatesMac() {
        ModerationAuditMacService service = new ModerationAuditMacService(SECRET_V1, "v1", "", "");
        ModerationAuditPayload p = samplePayload();
        String mac = service.computeMac(p);

        ModerationAuditPayload[] mutations = new ModerationAuditPayload[] {
                new ModerationAuditPayload(ModerationAuditPayload.DOMAIN_DISCUSSION_AUDIT, p.eventId(), p.targetId(), p.secondaryTargetId(), p.referenceId(), p.actorId(), p.actorType(), p.action(), p.decision(), p.previousStatus(), p.newStatus(), p.occurredAt(), p.reasonCode(), p.justification(), p.reportsAffectedCount()),
                new ModerationAuditPayload(p.domain(), UUID.randomUUID(), p.targetId(), p.secondaryTargetId(), p.referenceId(), p.actorId(), p.actorType(), p.action(), p.decision(), p.previousStatus(), p.newStatus(), p.occurredAt(), p.reasonCode(), p.justification(), p.reportsAffectedCount()),
                new ModerationAuditPayload(p.domain(), p.eventId(), UUID.randomUUID(), p.secondaryTargetId(), p.referenceId(), p.actorId(), p.actorType(), p.action(), p.decision(), p.previousStatus(), p.newStatus(), p.occurredAt(), p.reasonCode(), p.justification(), p.reportsAffectedCount()),
                new ModerationAuditPayload(p.domain(), p.eventId(), p.targetId(), UUID.randomUUID(), p.referenceId(), p.actorId(), p.actorType(), p.action(), p.decision(), p.previousStatus(), p.newStatus(), p.occurredAt(), p.reasonCode(), p.justification(), p.reportsAffectedCount()),
                new ModerationAuditPayload(p.domain(), p.eventId(), p.targetId(), p.secondaryTargetId(), UUID.randomUUID(), p.actorId(), p.actorType(), p.action(), p.decision(), p.previousStatus(), p.newStatus(), p.occurredAt(), p.reasonCode(), p.justification(), p.reportsAffectedCount()),
                new ModerationAuditPayload(p.domain(), p.eventId(), p.targetId(), p.secondaryTargetId(), p.referenceId(), UUID.randomUUID(), p.actorType(), p.action(), p.decision(), p.previousStatus(), p.newStatus(), p.occurredAt(), p.reasonCode(), p.justification(), p.reportsAffectedCount()),
                new ModerationAuditPayload(p.domain(), p.eventId(), p.targetId(), p.secondaryTargetId(), p.referenceId(), p.actorId(), "SYSTEM", p.action(), p.decision(), p.previousStatus(), p.newStatus(), p.occurredAt(), p.reasonCode(), p.justification(), p.reportsAffectedCount()),
                new ModerationAuditPayload(p.domain(), p.eventId(), p.targetId(), p.secondaryTargetId(), p.referenceId(), p.actorId(), p.actorType(), "RESTORE_REVIEW", p.decision(), p.previousStatus(), p.newStatus(), p.occurredAt(), p.reasonCode(), p.justification(), p.reportsAffectedCount()),
                new ModerationAuditPayload(p.domain(), p.eventId(), p.targetId(), p.secondaryTargetId(), p.referenceId(), p.actorId(), p.actorType(), p.action(), "REJECTED", p.previousStatus(), p.newStatus(), p.occurredAt(), p.reasonCode(), p.justification(), p.reportsAffectedCount()),
                new ModerationAuditPayload(p.domain(), p.eventId(), p.targetId(), p.secondaryTargetId(), p.referenceId(), p.actorId(), p.actorType(), p.action(), p.decision(), "ACTIVE", p.newStatus(), p.occurredAt(), p.reasonCode(), p.justification(), p.reportsAffectedCount()),
                new ModerationAuditPayload(p.domain(), p.eventId(), p.targetId(), p.secondaryTargetId(), p.referenceId(), p.actorId(), p.actorType(), p.action(), p.decision(), p.previousStatus(), "ACTIVE", p.occurredAt(), p.reasonCode(), p.justification(), p.reportsAffectedCount()),
                new ModerationAuditPayload(p.domain(), p.eventId(), p.targetId(), p.secondaryTargetId(), p.referenceId(), p.actorId(), p.actorType(), p.action(), p.decision(), p.previousStatus(), p.newStatus(), p.occurredAt().plusSeconds(1), p.reasonCode(), p.justification(), p.reportsAffectedCount()),
                new ModerationAuditPayload(p.domain(), p.eventId(), p.targetId(), p.secondaryTargetId(), p.referenceId(), p.actorId(), p.actorType(), p.action(), p.decision(), p.previousStatus(), p.newStatus(), p.occurredAt(), "ABUSE", p.justification(), p.reportsAffectedCount()),
                new ModerationAuditPayload(p.domain(), p.eventId(), p.targetId(), p.secondaryTargetId(), p.referenceId(), p.actorId(), p.actorType(), p.action(), p.decision(), p.previousStatus(), p.newStatus(), p.occurredAt(), p.reasonCode(), p.justification() + ".", p.reportsAffectedCount()),
                new ModerationAuditPayload(p.domain(), p.eventId(), p.targetId(), p.secondaryTargetId(), p.referenceId(), p.actorId(), p.actorType(), p.action(), p.decision(), p.previousStatus(), p.newStatus(), p.occurredAt(), p.reasonCode(), p.justification(), 4)
        };

        for (int i = 0; i < mutations.length; i++) {
            assertEquals(AuditVerificationStatus.INVALID, service.verify(mutations[i], mac),
                    "Mutação #" + i + " não invalidou o MAC");
        }
    }

    @Test
    @DisplayName("Rotação de chave: assina exclusivamente com chave ativa e verifica ativa + anterior; rejeita aposentada")
    void supportsControlledKeyRotation() {
        ModerationAuditPayload payload = samplePayload();
        ModerationAuditMacService beforeRotation = new ModerationAuditMacService(SECRET_V1, "v1", "", "");
        String macV1 = beforeRotation.computeMac(payload);

        ModerationAuditMacService duringRotation = new ModerationAuditMacService(SECRET_V2, "v2", SECRET_V1, "v1");
        String macV2 = duringRotation.computeMac(payload);

        assertTrue(macV2.startsWith("aud1$v2$"));
        assertNotEquals(macV1, macV2);
        assertEquals(AuditVerificationStatus.VERIFIED, duringRotation.verify(payload, macV2));
        assertEquals(AuditVerificationStatus.VERIFIED, duringRotation.verify(payload, macV1));

        // Trocar keyId no prefixo sem recomputar falha
        String forgedKeyId = "aud1$v2$" + macV1.substring("aud1$v1$".length());
        assertEquals(AuditVerificationStatus.INVALID, duringRotation.verify(payload, forgedKeyId));

        // Após aposentar v1, MAC v1 passa a ser inválido
        ModerationAuditMacService afterRetirement = new ModerationAuditMacService(SECRET_V2, "v2", "", "");
        assertEquals(AuditVerificationStatus.VERIFIED, afterRetirement.verify(payload, macV2));
        assertEquals(AuditVerificationStatus.INVALID, afterRetirement.verify(payload, macV1));
    }

    @Test
    @DisplayName("Fail-closed para segredos fracos, keyId inválido ou configuração de rotação inconsistente")
    void failsClosedOnInvalidModerationAuditConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new ModerationAuditMacService("", "v1", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new ModerationAuditMacService("short", "v1", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new ModerationAuditMacService("change-me-moderation-audit-secret-value-1234567890", "v1", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new ModerationAuditMacService(SECRET_V1, "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new ModerationAuditMacService(SECRET_V1, "v1$bad", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new ModerationAuditMacService(SECRET_V1, "a".repeat(33), "", ""));
        // Rotação parcial (só segredo ou só keyId)
        assertThrows(IllegalArgumentException.class, () -> new ModerationAuditMacService(SECRET_V2, "v2", SECRET_V1, ""));
        assertThrows(IllegalArgumentException.class, () -> new ModerationAuditMacService(SECRET_V2, "v2", "", "v1"));
        // keyId ou material binário duplicado
        assertThrows(IllegalArgumentException.class, () -> new ModerationAuditMacService(SECRET_V2, "v1", SECRET_V1, "v1"));
        assertThrows(IllegalArgumentException.class, () -> new ModerationAuditMacService(SECRET_V1, "v2", SECRET_V1, "v1"));
    }

    @Test
    @DisplayName("SecurityConfig bloqueia reutilização de MODERATION_AUDIT_SECRET com outros segredos ou senhas de banco")
    void securityConfigRejectsSecretAndDatabasePasswordReuse() {
        // Configuração válida com todos os segredos distintos
        new SecurityConfig(JWT_SECRET, PEPPER_SECRET, "", REFRESH_SECRET, "",
                SECRET_V1, SECRET_V2, "app_db_pass_strong_1", "media_db_pass_strong_3", "mod_db_pass_strong_2",
                "rewit-api", "rewit-clients");

        // Reuso com JWT_SECRET
        assertThrows(IllegalArgumentException.class, () -> new SecurityConfig(
                JWT_SECRET, PEPPER_SECRET, "", REFRESH_SECRET, "",
                JWT_SECRET, "", "app_db_pass_1", "media_db_pass_3", "mod_db_pass_2", "rewit-api", "rewit-clients"));

        // Reuso com PASSWORD_PEPPER_SECRET
        assertThrows(IllegalArgumentException.class, () -> new SecurityConfig(
                JWT_SECRET, PEPPER_SECRET, "", REFRESH_SECRET, "",
                PEPPER_SECRET, "", "app_db_pass_1", "media_db_pass_3", "mod_db_pass_2", "rewit-api", "rewit-clients"));

        // Reuso com REFRESH_TOKEN_SECRET
        assertThrows(IllegalArgumentException.class, () -> new SecurityConfig(
                JWT_SECRET, PEPPER_SECRET, "", REFRESH_SECRET, "",
                REFRESH_SECRET, "", "app_db_pass_1", "media_db_pass_3", "mod_db_pass_2", "rewit-api", "rewit-clients"));

        // Reuso com APP_DB_PASSWORD
        assertThrows(IllegalArgumentException.class, () -> new SecurityConfig(
                JWT_SECRET, PEPPER_SECRET, "", REFRESH_SECRET, "",
                SECRET_V1, "", SECRET_V1, "media_db_pass_3", "mod_db_pass_2", "rewit-api", "rewit-clients"));

        // Reuso com MEDIA_DB_PASSWORD
        assertThrows(IllegalArgumentException.class, () -> new SecurityConfig(
                JWT_SECRET, PEPPER_SECRET, "", REFRESH_SECRET, "",
                SECRET_V1, "", "app_db_pass_1", SECRET_V1, "mod_db_pass_2", "rewit-api", "rewit-clients"));

        // Reuso com MOD_DB_PASSWORD
        assertThrows(IllegalArgumentException.class, () -> new SecurityConfig(
                JWT_SECRET, PEPPER_SECRET, "", REFRESH_SECRET, "",
                SECRET_V1, "", "app_db_pass_1", "media_db_pass_3", SECRET_V1, "rewit-api", "rewit-clients"));

        // Reuso de MODERATION_AUDIT_SECRET_PREVIOUS com MEDIA_DB_PASSWORD
        assertThrows(IllegalArgumentException.class, () -> new SecurityConfig(
                JWT_SECRET, PEPPER_SECRET, "", REFRESH_SECRET, "",
                SECRET_V1, SECRET_V2, "app_db_pass_1", SECRET_V2, "mod_db_pass_2", "rewit-api", "rewit-clients"));

        // Reuso de MODERATION_AUDIT_SECRET_PREVIOUS com MOD_DB_PASSWORD
        assertThrows(IllegalArgumentException.class, () -> new SecurityConfig(
                JWT_SECRET, PEPPER_SECRET, "", REFRESH_SECRET, "",
                SECRET_V1, SECRET_V2, "app_db_pass_1", "media_db_pass_3", SECRET_V2, "rewit-api", "rewit-clients"));
    }
}
