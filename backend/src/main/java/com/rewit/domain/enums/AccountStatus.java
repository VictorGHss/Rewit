package com.rewit.domain.enums;

import java.time.Instant;

/**
 * Estado do ciclo de vida da conta (C2). É a fonte de verdade; {@code users.is_active} e {@code users.deleted_at}
 * são derivados dele e mantidos coerentes pelo schema ({@code chk_users_status_consistency}, V21).
 * <ul>
 *   <li>{@code ACTIVE}: conta operacional; o único estado que opera;</li>
 *   <li>{@code DEACTIVATED}: desativada pelo próprio usuário, que pode reativá-la;</li>
 *   <li>{@code SUSPENDED}: suspensa por ação administrativa; só um fluxo administrativo a reverte;</li>
 *   <li>{@code DELETED}: exclusão lógica definitiva; preservada para integridade e histórico, nunca volta.</li>
 * </ul>
 */
public enum AccountStatus {
    ACTIVE,
    DEACTIVATED,
    SUSPENDED,
    DELETED;

    /**
     * Estado equivalente à representação anterior ({@code is_active}/{@code deleted_at}), a mesma regra do backfill
     * da V21. Conta inativa sem exclusão não registra quem a desativou: vira {@code SUSPENDED}, que só a
     * administração reverte, para que um bloqueio manual nunca possa ser desfeito pelo próprio usuário.
     */
    public static AccountStatus fromLegacy(boolean isActive, Instant deletedAt) {
        if (deletedAt != null) {
            return DELETED;
        }
        return isActive ? ACTIVE : SUSPENDED;
    }
}
