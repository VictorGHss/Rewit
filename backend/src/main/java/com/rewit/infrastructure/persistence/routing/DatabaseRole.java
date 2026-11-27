package com.rewit.infrastructure.persistence.routing;

/**
 * Identidades PostgreSQL de runtime suportadas pelo roteamento transacional (S3-A13-F2).
 */
public enum DatabaseRole {
    /** Role padrão para fluxos públicos, leituras e rotinas comuns ({@code rewit_app}). */
    APP,
    /** Role dedicada ao ciclo de vida público/automático de mídias de locais ({@code rewit_media}). */
    MEDIA_LIFECYCLE,
    /** Role administrativa restrita aos seis fluxos transacionais de moderação ({@code rewit_mod}). */
    MODERATION
}
