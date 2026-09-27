package com.rewit.application.port;

/**
 * Porta de aplicação para envio de e-mails transacionais.
 */
public interface EmailProvider {

    void sendEmail(String toAddress, String subject, String bodyHtml);
}
