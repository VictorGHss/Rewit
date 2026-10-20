package com.rewit.domain.enums;

/**
 * Ações administrativas registradas na trilha de auditoria de fotografias de locais.
 */
public enum PlaceMediaModerationAction {
    REMOVE,
    RESTORE,
    ACCEPT_REPORT,
    REJECT_REPORT,
    AUTHOR_REMOVE,
    BUSINESS_OWNER_REMOVE,
    PLACE_MANAGER_REMOVE,
    AUTO_QUARANTINE
}
