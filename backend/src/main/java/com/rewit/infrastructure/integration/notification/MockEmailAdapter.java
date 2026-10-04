package com.rewit.infrastructure.integration.notification;

import com.rewit.application.port.EmailProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adaptador agnóstico para envio de e-mails transacionais (mock para desenvolvimento local).
 */
@Component
public class MockEmailAdapter implements EmailProvider {

    private static final Logger log = LoggerFactory.getLogger(MockEmailAdapter.class);

    @Override
    public void sendEmail(String toAddress, String subject, String bodyHtml) {
        // Sem destinatário/assunto no log (ADR-012): e-mail não mascarado é proibido em logs
        // (privacy-and-lgpd.md §5), como no MockNotificationAdapter
        log.info("[MOCK EMAIL] E-mail simulado pelo provider mock (destinatário e conteúdo não registrados)");
    }
}
