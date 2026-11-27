package com.rewit.infrastructure.persistence.routing;

import com.rewit.infrastructure.persistence.DatabaseRoleSecurityConstants;
import org.springframework.core.env.ConfigurableEnvironment;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Política canônica e fail-closed de validação antecipada das credenciais e identidades das três roles
 * PostgreSQL de runtime ({@code rewit_app}, {@code rewit_media} e {@code rewit_mod}) e de isolamento em
 * relação às credenciais de migração/owner ({@code FLYWAY_DB_*} / {@code POSTGRES_*}).
 *
 * <p>Todas as mensagens citam somente nomes de propriedades/variáveis e roles esperadas, nunca senhas.
 */
public final class RuntimeDatabaseRolePolicy {

    public static final String EXPECTED_APP_ROLE = DatabaseRoleSecurityConstants.EXPECTED_APP_ROLE;
    public static final String EXPECTED_MEDIA_ROLE = DatabaseRoleSecurityConstants.EXPECTED_MEDIA_ROLE;
    public static final String EXPECTED_MOD_ROLE = DatabaseRoleSecurityConstants.EXPECTED_MOD_ROLE;

    public static final String POOL_APP = "appDataSource";
    public static final String POOL_MEDIA = "mediaLifecycleDataSource";
    public static final String POOL_MOD = "moderationDataSource";

    public static final String MIGRATOR_POSTGRES_USER_ENV = "POSTGRES_USER";
    public static final String MIGRATOR_POSTGRES_PASSWORD_ENV = "POSTGRES_PASSWORD";
    public static final String MIGRATOR_FLYWAY_USER_ENV = "FLYWAY_DB_USER";
    public static final String MIGRATOR_FLYWAY_PASSWORD_ENV = "FLYWAY_DB_PASSWORD";

    private static final Set<String> FORBIDDEN_PASSWORDS = DatabaseRoleSecurityConstants.FORBIDDEN_PASSWORDS;

    private RuntimeDatabaseRolePolicy() {
    }

    /**
     * Valida antecipadamente todas as propriedades de roles runtime e seu isolamento da identidade migrator
     * a partir do {@link ConfigurableEnvironment}, antes da criação de qualquer DataSource ou execução do Flyway.
     */
    public static List<String> validateEnvironment(ConfigurableEnvironment env) {
        List<String> errors = new ArrayList<>();

        // 1. Rejeita parâmetros 'user' ou 'password' embutidos na URL JDBC de runtime
        validateJdbcUrlWithoutEmbeddedCredentials(env, "spring.datasource.url", errors);
        validateJdbcUrlWithoutEmbeddedCredentials(env, "spring.datasource.hikari.jdbc-url", errors);
        validateJdbcUrlWithoutEmbeddedCredentials(env, "spring.datasource.jdbc-url", errors);

        // 2. Se APP_DB_USER/MEDIA_DB_USER/MOD_DB_USER estiverem explicitamente declaradas mas vazias, rejeita
        //    antes mesmo de eventual default de YAML mascarar o erro operacional.
        validateExplicitEnvVarNotBlank(env, "APP_DB_USER", EXPECTED_APP_ROLE, errors);
        validateExplicitEnvVarNotBlank(env, "MEDIA_DB_USER", EXPECTED_MEDIA_ROLE, errors);
        validateExplicitEnvVarNotBlank(env, "MOD_DB_USER", EXPECTED_MOD_ROLE, errors);

        // 3. Resolve e valida as três credenciais runtime
        String appUser = resolveRequiredRoleUsername(env, "spring.datasource.username", "APP_DB_USER", EXPECTED_APP_ROLE, errors);
        String appPassword = resolveRequiredPassword(env, "spring.datasource.password", "APP_DB_PASSWORD", errors);

        String hikariAppUser = resolveOptional(env, "spring.datasource.hikari.username");
        if (hikariAppUser != null && !hikariAppUser.isBlank() && !EXPECTED_APP_ROLE.equals(hikariAppUser.trim())) {
            errors.add("spring.datasource.hikari.username inválido: a role pública de runtime é fixa (" + EXPECTED_APP_ROLE + ").");
        }

        String mediaUser = resolveRequiredRoleUsername(env, "rewit.datasource.media.username", "MEDIA_DB_USER", EXPECTED_MEDIA_ROLE, errors);
        String mediaPassword = resolveRequiredPassword(env, "rewit.datasource.media.password", "MEDIA_DB_PASSWORD", errors);

        String modUser = resolveRequiredRoleUsername(env, "rewit.datasource.moderation.username", "MOD_DB_USER", EXPECTED_MOD_ROLE, errors);
        String modPassword = resolveRequiredPassword(env, "rewit.datasource.moderation.password", "MOD_DB_PASSWORD", errors);

        // 4. Independência estrita de senhas entre as três roles de runtime
        validateDistinctRuntimePasswords(appPassword, mediaPassword, modPassword, errors);

        // 5. Isolamento estrito em relação às identidades e senhas de migração/owner (quando configuradas)
        Map<String, String> migratorUsers = collectOptionalProperties(env,
                "spring.flyway.user", "FLYWAY_DB_USER", "POSTGRES_USER", "TEST_MIGRATION_DB_USER");
        Map<String, String> migratorPasswords = collectOptionalProperties(env,
                "spring.flyway.password", "FLYWAY_DB_PASSWORD", "POSTGRES_PASSWORD", "TEST_MIGRATION_DB_PASSWORD");

        validateRuntimeIsolatedFromMigrator(
                appUser, appPassword,
                mediaUser, mediaPassword,
                modUser, modPassword,
                migratorUsers, migratorPasswords,
                errors
        );

        return errors;
    }

    /**
     * Valida diretamente credenciais e isolamento de migrator na construção dos beans em
     * {@link ModerationDataSourceConfig} (defesa em profundidade mesmo fora do ciclo completo do SpringApplication).
     */
    public static void validatePoolCredentials(
            String roleUserEnv,
            String roleUsername,
            String expectedRole,
            String rolePasswordEnv,
            String rolePassword,
            String jdbcUrl,
            Map<String, String> otherRolePasswords,
            Map<String, String> migratorUsers,
            Map<String, String> migratorPasswords
    ) {
        List<String> errors = new ArrayList<>();

        if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            validateJdbcUrlString(jdbcUrl, "spring.datasource.url", errors);
        }

        validateUsernameValue(roleUserEnv, roleUsername, expectedRole, errors);
        validatePasswordValue(rolePasswordEnv, rolePassword, errors);

        if (rolePassword != null && !rolePassword.isBlank() && otherRolePasswords != null) {
            for (Map.Entry<String, String> other : otherRolePasswords.entrySet()) {
                if (other.getValue() != null && !other.getValue().isBlank()
                        && constantTimeEquals(rolePassword, other.getValue())) {
                    errors.add(rolePasswordEnv + " não pode coincidir com " + other.getKey()
                            + ": cada role PostgreSQL exige senha independente.");
                }
            }
        }

        if (roleUsername != null && !roleUsername.isBlank() && migratorUsers != null) {
            String trimmedRole = roleUsername.trim();
            for (Map.Entry<String, String> mig : migratorUsers.entrySet()) {
                if (mig.getValue() != null && !mig.getValue().isBlank()
                        && trimmedRole.equals(mig.getValue().trim())) {
                    errors.add(roleUserEnv + " não pode coincidir com a identidade migrator/owner (" + mig.getKey() + ").");
                }
            }
        }

        if (rolePassword != null && !rolePassword.isBlank() && migratorPasswords != null) {
            for (Map.Entry<String, String> mig : migratorPasswords.entrySet()) {
                if (mig.getValue() != null && !mig.getValue().isBlank()
                        && constantTimeEquals(rolePassword, mig.getValue())) {
                    errors.add(rolePasswordEnv + " não pode coincidir com a senha migrator/owner (" + mig.getKey() + ").");
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new RuntimeDatabaseRoleException(
                    "Configuração inválida das roles PostgreSQL de runtime: " + String.join("; ", errors),
                    errors
            );
        }
    }

    public static void validateDistinctRuntimePasswords(
            String appPassword,
            String mediaPassword,
            String modPassword,
            List<String> errors
    ) {
        if (isPresent(appPassword) && isPresent(mediaPassword) && constantTimeEquals(appPassword, mediaPassword)) {
            errors.add("MEDIA_DB_PASSWORD não pode coincidir com APP_DB_PASSWORD: cada role PostgreSQL exige senha independente.");
        }
        if (isPresent(appPassword) && isPresent(modPassword) && constantTimeEquals(appPassword, modPassword)) {
            errors.add("MOD_DB_PASSWORD não pode coincidir com APP_DB_PASSWORD: cada role PostgreSQL exige senha independente.");
        }
        if (isPresent(mediaPassword) && isPresent(modPassword) && constantTimeEquals(mediaPassword, modPassword)) {
            errors.add("MOD_DB_PASSWORD não pode coincidir com MEDIA_DB_PASSWORD: cada role PostgreSQL exige senha independente.");
        }
    }

    public static void validateRuntimeIsolatedFromMigrator(
            String appUser, String appPassword,
            String mediaUser, String mediaPassword,
            String modUser, String modPassword,
            Map<String, String> migratorUsers,
            Map<String, String> migratorPasswords,
            List<String> errors
    ) {
        if (migratorUsers != null) {
            for (Map.Entry<String, String> entry : migratorUsers.entrySet()) {
                String migKey = entry.getKey();
                String migUser = entry.getValue();
                if (!isPresent(migUser)) {
                    continue;
                }
                String trimmedMig = migUser.trim();
                if (isPresent(appUser) && appUser.trim().equals(trimmedMig)) {
                    errors.add("APP_DB_USER não pode coincidir com a identidade migrator/owner (" + migKey + ").");
                }
                if (isPresent(mediaUser) && mediaUser.trim().equals(trimmedMig)) {
                    errors.add("MEDIA_DB_USER não pode coincidir com a identidade migrator/owner (" + migKey + ").");
                }
                if (isPresent(modUser) && modUser.trim().equals(trimmedMig)) {
                    errors.add("MOD_DB_USER não pode coincidir com a identidade migrator/owner (" + migKey + ").");
                }
            }
        }

        if (migratorPasswords != null) {
            for (Map.Entry<String, String> entry : migratorPasswords.entrySet()) {
                String migKey = entry.getKey();
                String migPass = entry.getValue();
                if (!isPresent(migPass)) {
                    continue;
                }
                if (isPresent(appPassword) && constantTimeEquals(appPassword, migPass)) {
                    errors.add("APP_DB_PASSWORD não pode coincidir com a senha migrator/owner (" + migKey + ").");
                }
                if (isPresent(mediaPassword) && constantTimeEquals(mediaPassword, migPass)) {
                    errors.add("MEDIA_DB_PASSWORD não pode coincidir com a senha migrator/owner (" + migKey + ").");
                }
                if (isPresent(modPassword) && constantTimeEquals(modPassword, migPass)) {
                    errors.add("MOD_DB_PASSWORD não pode coincidir com a senha migrator/owner (" + migKey + ").");
                }
            }
        }
    }

    public static boolean isForbiddenPassword(String password) {
        return password != null && FORBIDDEN_PASSWORDS.contains(password.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void validateExplicitEnvVarNotBlank(
            ConfigurableEnvironment env,
            String envVar,
            String expectedRole,
            List<String> errors
    ) {
        if (!env.containsProperty(envVar)) {
            return;
        }
        String value;
        try {
            value = env.getProperty(envVar);
        } catch (IllegalArgumentException unresolved) {
            errors.add(envVar + " sem valor resolvido: defina " + envVar + "=" + expectedRole + ".");
            return;
        }
        if (value == null || value.isBlank()) {
            errors.add(envVar + " está definido porém vazio: a role de runtime obrigatória é " + expectedRole + ".");
        }
    }

    private static String resolveRequiredRoleUsername(
            ConfigurableEnvironment env,
            String property,
            String envVar,
            String expectedRole,
            List<String> errors
    ) {
        String value;
        try {
            value = env.getProperty(property);
        } catch (IllegalArgumentException unresolved) {
            errors.add(property + " sem valor resolvido: defina " + envVar + "=" + expectedRole + ".");
            return null;
        }
        validateUsernameValue(envVar + " (" + property + ")", value, expectedRole, errors);
        return value;
    }

    private static String resolveRequiredPassword(
            ConfigurableEnvironment env,
            String property,
            String envVar,
            List<String> errors
    ) {
        String value;
        try {
            value = env.getProperty(property);
        } catch (IllegalArgumentException unresolved) {
            errors.add(property + " sem valor resolvido: defina " + envVar + ".");
            return null;
        }
        validatePasswordValue(envVar + " (" + property + ")", value, errors);
        return value;
    }

    private static void validateUsernameValue(String label, String value, String expectedRole, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(label + " ausente ou vazio: configure exatamente '" + expectedRole + "'.");
            return;
        }
        if (!expectedRole.equals(value.trim())) {
            errors.add(label + " inválido: a role PostgreSQL de runtime é fixa ('" + expectedRole + "').");
        }
    }

    private static void validatePasswordValue(String label, String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(label + " ausente ou vazio: configure uma senha exclusiva gerada por CSPRNG.");
            return;
        }
        if (isForbiddenPassword(value)) {
            errors.add(label + " usa valor de exemplo proibido: defina uma senha exclusiva gerada por CSPRNG.");
        }
    }

    private static void validateJdbcUrlWithoutEmbeddedCredentials(
            ConfigurableEnvironment env,
            String property,
            List<String> errors
    ) {
        String url = resolveOptional(env, property);
        if (url != null && !url.isBlank()) {
            validateJdbcUrlString(url, property, errors);
        }
    }

    private static void validateJdbcUrlString(String url, String property, List<String> errors) {
        String trimmed = url.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql:")) {
            return;
        }
        boolean hasEmbeddedCredential = false;

        // 1. Verifica userinfo embutido na authority da URL JDBC (credenciais antes de '@host:5432/db')
        String withoutJdbcPrefix = trimmed.substring("jdbc:".length());
        try {
            URI uri = URI.create(withoutJdbcPrefix);
            if (uri.getRawUserInfo() != null && !uri.getRawUserInfo().isBlank()) {
                hasEmbeddedCredential = true;
            }
        } catch (IllegalArgumentException ignored) {
            // URLs com múltiplos hosts ou caracteres especiais ainda passam pela checagem de query abaixo
            int schemeEnd = withoutJdbcPrefix.indexOf("://");
            if (schemeEnd >= 0) {
                int authorityStart = schemeEnd + 3;
                int slashIdx = withoutJdbcPrefix.indexOf('/', authorityStart);
                int queryIdx = withoutJdbcPrefix.indexOf('?', authorityStart);
                int authorityEnd = withoutJdbcPrefix.length();
                if (slashIdx >= 0) {
                    authorityEnd = Math.min(authorityEnd, slashIdx);
                }
                if (queryIdx >= 0) {
                    authorityEnd = Math.min(authorityEnd, queryIdx);
                }
                if (withoutJdbcPrefix.substring(authorityStart, authorityEnd).contains("@")) {
                    hasEmbeddedCredential = true;
                }
            }
        }

        // 2. Verifica parâmetros de query (?user=...&password=...)
        int qIdx = trimmed.indexOf('?');
        if (!hasEmbeddedCredential && qIdx >= 0 && qIdx + 1 < trimmed.length()) {
            String query = trimmed.substring(qIdx + 1);
            int hashIdx = query.indexOf('#');
            if (hashIdx >= 0) {
                query = query.substring(0, hashIdx);
            }
            for (String param : query.split("&")) {
                if (param.isEmpty()) {
                    continue;
                }
                int eq = param.indexOf('=');
                String rawKey = eq >= 0 ? param.substring(0, eq) : param;
                String decodedKey;
                try {
                    decodedKey = URLDecoder.decode(rawKey, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
                } catch (IllegalArgumentException e) {
                    decodedKey = rawKey.trim().toLowerCase(Locale.ROOT);
                }
                if ("user".equals(decodedKey) || "password".equals(decodedKey)) {
                    hasEmbeddedCredential = true;
                    break;
                }
            }
        }

        if (hasEmbeddedCredential) {
            errors.add(property + " não pode embutir parâmetros 'user' ou 'password' na URL JDBC: "
                    + "as credenciais de cada pool devem vir exclusivamente de APP_DB_*, MEDIA_DB_* e MOD_DB_*.");
        }
    }

    private static Map<String, String> collectOptionalProperties(ConfigurableEnvironment env, String... keys) {
        Map<String, String> found = new LinkedHashMap<>();
        for (String key : keys) {
            String value = resolveOptional(env, key);
            if (isPresent(value)) {
                found.put(key, value);
            }
        }
        return found;
    }

    private static String resolveOptional(ConfigurableEnvironment env, String key) {
        try {
            return env.getProperty(key);
        } catch (IllegalArgumentException unresolved) {
            return null;
        }
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
