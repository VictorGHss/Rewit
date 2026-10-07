package com.rewit.application.port;

/**
 * Valor reservado que substitui o e-mail de uma conta excluída no purge (C2.3). O endereço sai do banco e o e-mail
 * continua indisponível para novos cadastros (decisão do MVP), porque o registro também procura esse valor.
 */
public interface EmailReservation {

    /**
     * @return o valor reservado do e-mail (normalizado como em {@code User.normalizeEmail}), no domínio
     *         {@code deleted.invalid}; o mesmo e-mail produz sempre o mesmo valor
     * @throws IllegalStateException se o segredo não estiver configurado (sem fallback)
     */
    String reservedEmailFor(String email);

    /** Há segredo configurado: o valor reservado pode ser calculado. */
    boolean isConfigured();
}
