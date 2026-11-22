package com.rewit.infrastructure.security;

/**
 * Resultado da verificação criptográfica de autenticidade de origem e integridade de payload
 * de um registro de auditoria ou decisão administrativa (S3-A13-F2).
 *
 * <p>Nota de segurança: HMAC-SHA256 com segredo compartilhado da aplicação comprova autenticidade
 * de origem da aplicação e integridade do payload, não constituindo não repúdio individual estrito
 * nem detectando por si só exclusão ou omissão histórica de eventos.
 */
public enum AuditVerificationStatus {
    /** MAC presente no formato canônico {@code aud1$<keyId>$<hex>} e validado em tempo constante. */
    VERIFIED,
    /** Registro histórico anterior à adoção da V45 ({@code authenticity_mac IS NULL}); nunca apresentado como autenticado. */
    HISTORICAL_UNAUTHENTICATED,
    /** MAC ausente quando exigido, malformado, com keyId desconhecido ou divergente do payload persistido. */
    INVALID
}
