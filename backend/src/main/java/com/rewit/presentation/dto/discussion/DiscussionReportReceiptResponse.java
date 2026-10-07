package com.rewit.presentation.dto.discussion;

/**
 * Confirmação genérica da denúncia de uma discussão: a mesma para a primeira denúncia, a repetida e a que coloca
 * o comentário em análise. Não expõe identificador, contagem nem estado do comentário.
 */
public record DiscussionReportReceiptResponse(String status, String message) {

    public static final String RECEIVED = "RECEIVED";
    public static final String MESSAGE = "Denúncia recebida. Obrigado por ajudar a manter a comunidade segura.";

    public static DiscussionReportReceiptResponse received() {
        return new DiscussionReportReceiptResponse(RECEIVED, MESSAGE);
    }
}
