package com.rewit.domain.enums;

/**
 * Estados do ciclo de vida de verificação presencial do Check-in (ADR-005 / Seção 19).
 */
public enum CheckInStatus {
    PENDING,    // Check-in registrado, aguardando validação de presença
    VERIFIED,   // Presença validada com sucesso
    REJECTED    // Verificação rejeitada (ex: fora do raio de tolerância do local)
}
