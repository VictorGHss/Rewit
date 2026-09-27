package com.rewit.domain.enums;

/**
 * Métodos suportados para verificação presencial de Check-in em locais físicos (Seção 19).
 */
public enum VerificationMethod {
    GPS,
    QR_CODE,
    NFC,
    BEACON
}
