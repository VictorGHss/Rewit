package com.rewit.domain.enums;

/**
 * Papéis de autoridade e governança de usuários na plataforma Rewit (Step 26.1).
 */
public enum Role {
    USER,
    MODERATOR,
    ADMIN;

    public String getAuthority() {
        return "ROLE_" + this.name();
    }
}
