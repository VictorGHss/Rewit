package com.rewit.application.ratelimit;

/**
 * Ações protegidas por rate limiting. Cada ação tem a sua política (limite e janela) configurada em
 * {@code rewit.rate-limit.*} e a mensagem devolvida ao cliente quando o limite é atingido.
 *
 * <p>O nome da ação é o único rótulo de métrica do rate limiting: o conjunto é fechado e de baixa cardinalidade.
 */
public enum RateLimitedAction {

    LOGIN("Muitas tentativas de login. Tente novamente mais tarde."),
    REFRESH("Muitas renovações de sessão. Tente novamente mais tarde."),
    REGISTRATION("Muitas tentativas de cadastro. Tente novamente mais tarde."),
    REPORT_CREATION("Limite de denúncias excedido. Tente novamente mais tarde."),
    DISCUSSION_CREATION("Limite de criação de comentários excedido. Tente novamente mais tarde."),
    MEDIA_UPLOAD("Limite de upload de mídia excedido. Tente novamente mais tarde.");

    private final String exceededMessage;

    RateLimitedAction(String exceededMessage) {
        this.exceededMessage = exceededMessage;
    }

    public String exceededMessage() {
        return exceededMessage;
    }
}
