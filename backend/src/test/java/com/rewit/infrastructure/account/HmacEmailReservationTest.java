package com.rewit.infrastructure.account;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Reserva de e-mail de conta excluída: HMAC-SHA256 com segredo operacional")
class HmacEmailReservationTest {

    /** Segredo gerado na execução: nenhum valor fixo no código de teste. */
    private static String randomSecret() {
        return UUID.randomUUID() + "" + UUID.randomUUID();
    }

    @Test
    @DisplayName("Determinístico para o mesmo e-mail normalizado; e-mails diferentes, reservas diferentes")
    void deterministicAndUnique() {
        HmacEmailReservation reservation = new HmacEmailReservation(randomSecret());

        String reserved = reservation.reservedEmailFor("  Maria.Silva@Exemplo.com ");
        assertEquals(reserved, reservation.reservedEmailFor("maria.silva@exemplo.com"));
        assertNotEquals(reserved, reservation.reservedEmailFor("maria.silva2@exemplo.com"));
        assertTrue(reserved.matches("^[0-9a-f]{64}@deleted\\.invalid$"), reserved);
    }

    @Test
    @DisplayName("A reserva não contém o e-mail original e depende do segredo")
    void dependsOnSecretAndHidesEmail() {
        String email = "joana.souza@exemplo.com";
        String reserved = new HmacEmailReservation(randomSecret()).reservedEmailFor(email);

        assertFalse(reserved.contains("joana"));
        assertFalse(reserved.contains("souza"));
        assertFalse(reserved.contains("exemplo"));
        assertNotEquals(reserved, new HmacEmailReservation(randomSecret()).reservedEmailFor(email),
                "sem o segredo, o valor não pode ser reproduzido a partir do e-mail");
    }

    @Test
    @DisplayName("Sem segredo: indisponível, sem fallback; segredo curto é rejeitado na inicialização")
    void secretIsRequired() {
        for (String missing : new String[]{null, "", "   "}) {
            HmacEmailReservation reservation = new HmacEmailReservation(missing);
            assertFalse(reservation.isConfigured());
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> reservation.reservedEmailFor("pessoa@exemplo.com"));
            assertTrue(ex.getMessage().contains("ACCOUNT_EMAIL_RESERVATION_SECRET"));
        }

        String shortSecret = "x".repeat(HmacEmailReservation.MIN_SECRET_BYTES - 1);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> new HmacEmailReservation(shortSecret));
        assertFalse(ex.getMessage().contains(shortSecret), "a mensagem não revela o segredo");
        assertTrue(new HmacEmailReservation(randomSecret()).isConfigured());
    }
}
