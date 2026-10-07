package com.rewit.domain.enums;

/**
 * Ações administrativas sobre uma discussão em análise (C3): remover ({@code UNDER_REVIEW -> REMOVED}) ou
 * restaurar ({@code UNDER_REVIEW -> ACTIVE}).
 */
public enum DiscussionModerationAction {
    REMOVE_DISCUSSION,
    RESTORE_DISCUSSION;

    /** Decisão sobre as denúncias pendentes: procedentes na remoção, improcedentes na restauração. */
    public ModerationDecision decision() {
        return this == REMOVE_DISCUSSION ? ModerationDecision.ACCEPTED : ModerationDecision.REJECTED;
    }
}
