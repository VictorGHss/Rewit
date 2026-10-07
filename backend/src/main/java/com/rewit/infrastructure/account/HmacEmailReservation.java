package com.rewit.infrastructure.account;

import com.rewit.application.port.EmailReservation;
import com.rewit.domain.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Reserva do e-mail de conta excluída por HMAC-SHA256 com segredo operacional
 * ({@code rewit.account.email-reservation-secret}, variável {@code ACCOUNT_EMAIL_RESERVATION_SECRET}). Sem o segredo,
 * quem tiver acesso ao banco não consegue confirmar um e-mail candidato contra os valores reservados.
 *
 * <p>O segredo precisa ser estável e igual em todas as instâncias: trocá-lo invalida as reservas já gravadas (o
 * registro deixa de reconhecê-las). Não há fallback: sem segredo o cálculo falha e o purge não executa. O segredo
 * nunca aparece em mensagens nem em logs.
 */
@Component
public class HmacEmailReservation implements EmailReservation {

    static final int MIN_SECRET_BYTES = 32;
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public HmacEmailReservation(@Value("${rewit.account.email-reservation-secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            this.key = null;
            return;
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("rewit.account.email-reservation-secret precisa ter no mínimo "
                    + MIN_SECRET_BYTES + " bytes");
        }
        this.key = new SecretKeySpec(bytes, ALGORITHM);
    }

    @Override
    public boolean isConfigured() {
        return key != null;
    }

    @Override
    public String reservedEmailFor(String email) {
        if (key == null) {
            throw new IllegalStateException("Reserva de e-mail indisponível: rewit.account.email-reservation-secret "
                    + "(ACCOUNT_EMAIL_RESERVATION_SECRET) não configurado");
        }
        String normalized = User.normalizeEmail(email);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest) + "@" + User.RESERVED_EMAIL_DOMAIN;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponível", e);
        }
    }
}
