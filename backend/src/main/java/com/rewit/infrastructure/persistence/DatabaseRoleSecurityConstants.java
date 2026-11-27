package com.rewit.infrastructure.persistence;

import java.util.Locale;
import java.util.Set;

/**
 * Constantes puras (sem dependência do Spring Framework) de segurança para roles PostgreSQL
 * e rejeição de senhas inseguras/placeholder.
 *
 * <p>Compartilhadas entre o runtime da API ({@code RuntimeDatabaseRolePolicy}) e o runner
 * isolado de migrations ({@code com.rewit.migration.RewitMigrationRunner}), garantindo política
 * única sem arrastar classes do Spring para o processo de migração.
 */
public final class DatabaseRoleSecurityConstants {

    public static final String EXPECTED_APP_ROLE = "rewit_app";
    public static final String EXPECTED_MEDIA_ROLE = "rewit_media";
    public static final String EXPECTED_MOD_ROLE = "rewit_mod";

    public static final Set<String> RUNTIME_ROLES = Set.of(
            EXPECTED_APP_ROLE,
            EXPECTED_MEDIA_ROLE,
            EXPECTED_MOD_ROLE
    );

    public static final Set<String> FORBIDDEN_PASSWORDS = Set.of(
            "change-me",
            "change_me",
            "changeme",
            "placeholder",
            "default",
            "password",
            "postgres",
            "rewit_local_password",
            "rewit_local_access",
            "rewit_local_secret",
            "rewit_password"
    );

    private DatabaseRoleSecurityConstants() {
    }

    public static boolean isRuntimeRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return false;
        }
        return RUNTIME_ROLES.contains(roleName.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isForbiddenPassword(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return FORBIDDEN_PASSWORDS.contains(value.trim().toLowerCase(Locale.ROOT));
    }
}
