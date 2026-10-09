package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.PlaceClaimStatus;
import com.rewit.domain.enums.VerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Domínio da reivindicação de locais (C9): conta comercial e solicitação")
class PlaceClaimDomainTest {

    private final Instant now = Instant.parse("2026-10-10T12:00:00Z");

    @Test
    @DisplayName("Conta nova: PENDING, FREE, razão social com espaços colapsados e documento na forma canônica")
    void openAccount() {
        BusinessAccount account = BusinessAccount.open(UUID.randomUUID(), "  Padaria   Pão\tQuente  LTDA ",
                " 12.345.678/0001-9a ", now);

        assertEquals(VerificationStatus.PENDING, account.getVerificationStatus());
        assertEquals("FREE", account.getPlanTier());
        assertEquals("Padaria Pão Quente LTDA", account.getCorporateName());
        assertEquals("123456780001 9A".replace(" ", ""), account.getTaxId());
        assertEquals(now, account.getCreatedAt());
        assertEquals(now, account.getUpdatedAt());
    }

    @Test
    @DisplayName("Razão social e documento fora dos limites são recusados com 400")
    void invalidAccountData() {
        UUID user = UUID.randomUUID();
        assertEquals("INVALID_CORPORATE_NAME",
                assertThrows(BusinessException.class, () -> BusinessAccount.open(user, " a ", "12345678", now)).getErrorCode());
        assertEquals("INVALID_CORPORATE_NAME", assertThrows(BusinessException.class,
                () -> BusinessAccount.open(user, "x".repeat(256), "12345678", now)).getErrorCode());
        assertEquals("INVALID_TAX_ID",
                assertThrows(BusinessException.class, () -> BusinessAccount.open(user, "Empresa", "1.234-56", now)).getErrorCode());
        assertEquals("INVALID_TAX_ID", assertThrows(BusinessException.class,
                () -> BusinessAccount.open(user, "Empresa", "9".repeat(33), now)).getErrorCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> BusinessAccount.open(user, "Empresa", null, now)).getStatus().value());
    }

    @Test
    @DisplayName("Verificação: a primeira aprovação muda a conta; já aprovada não muda nada")
    void approveVerificationOnce() {
        BusinessAccount account = BusinessAccount.open(UUID.randomUUID(), "Empresa", "12345678", now);

        assertTrue(account.approveVerification(now.plusSeconds(10)));
        assertEquals(VerificationStatus.APPROVED, account.getVerificationStatus());
        assertEquals(now.plusSeconds(10), account.getUpdatedAt());

        assertFalse(account.approveVerification(now.plusSeconds(20)));
        assertEquals(now.plusSeconds(10), account.getUpdatedAt(), "sem alteração desnecessária");
    }

    @Test
    @DisplayName("Evidência: aparada e entre 20 e 1000 caracteres (contados como o banco conta)")
    void evidenceBounds() {
        UUID account = UUID.randomUUID();
        UUID place = UUID.randomUUID();
        assertEquals("INVALID_EVIDENCE_DESCRIPTION", assertThrows(BusinessException.class,
                () -> new PlaceClaimRequest(account, place, "   " + "x".repeat(19) + "   ", now)).getErrorCode());
        assertEquals("INVALID_EVIDENCE_DESCRIPTION", assertThrows(BusinessException.class,
                () -> new PlaceClaimRequest(account, place, "x".repeat(1001), now)).getErrorCode());
        // 1000 emojis: 2000 unidades UTF-16, mas 1000 caracteres para o banco (char_length)
        PlaceClaimRequest emoji = new PlaceClaimRequest(account, place, "😀".repeat(1000), now);
        assertEquals(PlaceClaimStatus.PENDING, emoji.getStatus());

        PlaceClaimRequest claim = new PlaceClaimRequest(account, place, "  " + "e".repeat(20) + "  ", now);
        assertEquals("e".repeat(20), claim.getEvidenceDescription());
        assertNull(claim.getDecidedAt());
        assertNull(claim.getDecidedByUserId());
        assertNull(claim.getDecisionReason());
    }

    @Test
    @DisplayName("Decisão única: aprovar registra quem, quando e por quê; decidir de novo é 409")
    void decideOnce() {
        PlaceClaimRequest claim = new PlaceClaimRequest(UUID.randomUUID(), UUID.randomUUID(), "e".repeat(30), now);
        UUID moderator = UUID.randomUUID();

        claim.approve(moderator, "  Documentação conferida e válida  ", now.plusSeconds(5));
        assertEquals(PlaceClaimStatus.APPROVED, claim.getStatus());
        assertEquals(moderator, claim.getDecidedByUserId());
        assertEquals(now.plusSeconds(5), claim.getDecidedAt());
        assertEquals("Documentação conferida e válida", claim.getDecisionReason());

        BusinessException again = assertThrows(BusinessException.class,
                () -> claim.reject(moderator, "Tentativa de nova decisão", now.plusSeconds(9)));
        assertEquals("PLACE_CLAIM_ALREADY_DECIDED", again.getErrorCode());
        assertEquals(409, again.getStatus().value());
        assertEquals(PlaceClaimStatus.APPROVED, claim.getStatus());
    }

    @Test
    @DisplayName("Justificativa da decisão: 15 a 1000 caracteres")
    void decisionReasonBounds() {
        PlaceClaimRequest claim = new PlaceClaimRequest(UUID.randomUUID(), UUID.randomUUID(), "e".repeat(30), now);
        assertEquals("INVALID_DECISION_REASON", assertThrows(BusinessException.class,
                () -> claim.reject(UUID.randomUUID(), "curta demais", now)).getErrorCode());
        assertTrue(claim.isPending(), "decisão inválida não altera a solicitação");
    }
}
