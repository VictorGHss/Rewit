package com.rewit.infrastructure.persistence.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("F2-DEPLOY-02: validação antecipada fail-closed das propriedades das roles PostgreSQL de runtime")
class RuntimeDatabaseRoleStartupGuardUnitTest {

    private static final String APP_PASS = "app-runtime-secret-Xq7#kP2v9mL1";
    private static final String MEDIA_PASS = "media-runtime-secret-Kp4!xV7s3nQ8";
    private static final String MOD_PASS = "mod-runtime-secret-Yr8#mN4q6tW2";
    private static final String MIG_PASS = "migrator-owner-secret-Lw3!rT9d5jZ4";

    private static MockEnvironment validEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:35432/rewit_test");
        env.setProperty("spring.datasource.username", "rewit_app");
        env.setProperty("spring.datasource.password", APP_PASS);
        env.setProperty("rewit.datasource.media.username", "rewit_media");
        env.setProperty("rewit.datasource.media.password", MEDIA_PASS);
        env.setProperty("rewit.datasource.moderation.username", "rewit_mod");
        env.setProperty("rewit.datasource.moderation.password", MOD_PASS);
        env.setProperty("spring.flyway.user", "rewit_migrator");
        env.setProperty("spring.flyway.password", MIG_PASS);
        return env;
    }

    @Test
    @DisplayName("Configuração válida com as três roles fixas e senhas independentes passa")
    void acceptsValidRuntimeRoleConfiguration() {
        assertDoesNotThrow(() -> RuntimeDatabaseRoleEnvironmentValidator.validate(validEnvironment()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rewit_mig", "postgres", "rewit_user", "rewit_owner", "rewit_mod", "rewit_media", " ", ""})
    @DisplayName("Rejeita APP_DB_USER diferente de rewit_app ou vazio")
    void rejectsInvalidAppDbUser(String invalidUser) {
        MockEnvironment env = validEnvironment();
        env.setProperty("spring.datasource.username", invalidUser);

        RuntimeDatabaseRoleException ex = assertThrows(RuntimeDatabaseRoleException.class,
                () -> RuntimeDatabaseRoleEnvironmentValidator.validate(env));
        assertTrue(ex.getMessage().contains("APP_DB_USER"));
        assertTrue(ex.getMessage().contains("rewit_app"));
        assertFalse(ex.getMessage().contains(APP_PASS));
    }

    @Test
    @DisplayName("Rejeita APP_DB_USER explicitamente vazio mesmo se spring.datasource.username tiver valor padrão")
    void rejectsExplicitBlankAppDbUserEnvVar() {
        MockEnvironment env = validEnvironment();
        env.setProperty("APP_DB_USER", "");

        RuntimeDatabaseRoleException ex = assertThrows(RuntimeDatabaseRoleException.class,
                () -> RuntimeDatabaseRoleEnvironmentValidator.validate(env));
        assertTrue(ex.getMessage().contains("APP_DB_USER"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rewit_app", "rewit_mod", "postgres", "rewit_user", ""})
    @DisplayName("Rejeita MEDIA_DB_USER diferente de rewit_media ou vazio")
    void rejectsInvalidMediaDbUser(String invalidUser) {
        MockEnvironment env = validEnvironment();
        env.setProperty("rewit.datasource.media.username", invalidUser);

        RuntimeDatabaseRoleException ex = assertThrows(RuntimeDatabaseRoleException.class,
                () -> RuntimeDatabaseRoleEnvironmentValidator.validate(env));
        assertTrue(ex.getMessage().contains("MEDIA_DB_USER"));
        assertTrue(ex.getMessage().contains("rewit_media"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rewit_media", "rewit_app", "postgres", "rewit_user", ""})
    @DisplayName("Rejeita MOD_DB_USER diferente de rewit_mod ou vazio")
    void rejectsInvalidModDbUser(String invalidUser) {
        MockEnvironment env = validEnvironment();
        env.setProperty("rewit.datasource.moderation.username", invalidUser);

        RuntimeDatabaseRoleException ex = assertThrows(RuntimeDatabaseRoleException.class,
                () -> RuntimeDatabaseRoleEnvironmentValidator.validate(env));
        assertTrue(ex.getMessage().contains("MOD_DB_USER"));
        assertTrue(ex.getMessage().contains("rewit_mod"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"change-me", "CHANGE-ME", "changeme", "rewit_local_password", "placeholder", ""})
    @DisplayName("Rejeita placeholders ou senhas vazias em APP_DB_PASSWORD")
    void rejectsForbiddenAppPasswords(String forbidden) {
        MockEnvironment env = validEnvironment();
        env.setProperty("spring.datasource.password", forbidden);

        RuntimeDatabaseRoleException ex = assertThrows(RuntimeDatabaseRoleException.class,
                () -> RuntimeDatabaseRoleEnvironmentValidator.validate(env));
        assertTrue(ex.getMessage().contains("APP_DB_PASSWORD"));
    }

    @Test
    @DisplayName("Rejeita reutilização de senha entre as três roles runtime")
    void rejectsPasswordReuseBetweenRuntimeRoles() {
        MockEnvironment appMediaSame = validEnvironment();
        appMediaSame.setProperty("rewit.datasource.media.password", APP_PASS);
        RuntimeDatabaseRoleException ex1 = assertThrows(RuntimeDatabaseRoleException.class,
                () -> RuntimeDatabaseRoleEnvironmentValidator.validate(appMediaSame));
        assertTrue(ex1.getMessage().contains("MEDIA_DB_PASSWORD"));
        assertFalse(ex1.getMessage().contains(APP_PASS));

        MockEnvironment appModSame = validEnvironment();
        appModSame.setProperty("rewit.datasource.moderation.password", APP_PASS);
        RuntimeDatabaseRoleException ex2 = assertThrows(RuntimeDatabaseRoleException.class,
                () -> RuntimeDatabaseRoleEnvironmentValidator.validate(appModSame));
        assertTrue(ex2.getMessage().contains("MOD_DB_PASSWORD"));
        assertFalse(ex2.getMessage().contains(APP_PASS));

        MockEnvironment mediaModSame = validEnvironment();
        mediaModSame.setProperty("rewit.datasource.moderation.password", MEDIA_PASS);
        RuntimeDatabaseRoleException ex3 = assertThrows(RuntimeDatabaseRoleException.class,
                () -> RuntimeDatabaseRoleEnvironmentValidator.validate(mediaModSame));
        assertTrue(ex3.getMessage().contains("MOD_DB_PASSWORD"));
        assertFalse(ex3.getMessage().contains(MEDIA_PASS));
    }

    @Test
    @DisplayName("Rejeita senha runtime igual à senha migrator/owner (spring.flyway.password, FLYWAY_DB_PASSWORD ou POSTGRES_PASSWORD)")
    void rejectsRuntimePasswordMatchingMigrator() {
        MockEnvironment flywaySame = validEnvironment();
        flywaySame.setProperty("spring.datasource.password", MIG_PASS);
        RuntimeDatabaseRoleException ex1 = assertThrows(RuntimeDatabaseRoleException.class,
                () -> RuntimeDatabaseRoleEnvironmentValidator.validate(flywaySame));
        assertTrue(ex1.getMessage().contains("APP_DB_PASSWORD"));
        assertTrue(ex1.getMessage().contains("spring.flyway.password"));
        assertFalse(ex1.getMessage().contains(MIG_PASS));

        MockEnvironment postgresPassSame = validEnvironment();
        postgresPassSame.setProperty(RuntimeDatabaseRolePolicy.MIGRATOR_POSTGRES_PASSWORD_ENV, MEDIA_PASS);
        RuntimeDatabaseRoleException ex2 = assertThrows(RuntimeDatabaseRoleException.class,
                () -> RuntimeDatabaseRoleEnvironmentValidator.validate(postgresPassSame));
        assertTrue(ex2.getMessage().contains("MEDIA_DB_PASSWORD"));
        assertTrue(ex2.getMessage().contains(RuntimeDatabaseRolePolicy.MIGRATOR_POSTGRES_PASSWORD_ENV));
        assertFalse(ex2.getMessage().contains(MEDIA_PASS));

        MockEnvironment flywayEnvPassSame = validEnvironment();
        flywayEnvPassSame.setProperty(RuntimeDatabaseRolePolicy.MIGRATOR_FLYWAY_PASSWORD_ENV, MOD_PASS);
        RuntimeDatabaseRoleException ex3 = assertThrows(RuntimeDatabaseRoleException.class,
                () -> RuntimeDatabaseRoleEnvironmentValidator.validate(flywayEnvPassSame));
        assertTrue(ex3.getMessage().contains("MOD_DB_PASSWORD"));
        assertTrue(ex3.getMessage().contains(RuntimeDatabaseRolePolicy.MIGRATOR_FLYWAY_PASSWORD_ENV));
        assertFalse(ex3.getMessage().contains(MOD_PASS));
    }

    @Test
    @DisplayName("Rejeita colisão entre identidade migrator/owner e role runtime")
    void rejectsMigratorUserCollidingWithRuntimeRole() {
        MockEnvironment env = validEnvironment();
        env.setProperty(RuntimeDatabaseRolePolicy.MIGRATOR_POSTGRES_USER_ENV, "rewit_app");

        RuntimeDatabaseRoleException ex = assertThrows(RuntimeDatabaseRoleException.class,
                () -> RuntimeDatabaseRoleEnvironmentValidator.validate(env));
        assertTrue(ex.getMessage().contains("APP_DB_USER"));
        assertTrue(ex.getMessage().contains(RuntimeDatabaseRolePolicy.MIGRATOR_POSTGRES_USER_ENV));

        MockEnvironment env2 = validEnvironment();
        env2.setProperty(RuntimeDatabaseRolePolicy.MIGRATOR_FLYWAY_USER_ENV, "rewit_mod");

        RuntimeDatabaseRoleException ex2 = assertThrows(RuntimeDatabaseRoleException.class,
                () -> RuntimeDatabaseRoleEnvironmentValidator.validate(env2));
        assertTrue(ex2.getMessage().contains("MOD_DB_USER"));
        assertTrue(ex2.getMessage().contains(RuntimeDatabaseRolePolicy.MIGRATOR_FLYWAY_USER_ENV));
    }

    @Test
    @DisplayName("Rejeita override por spring.datasource.hikari.username e credenciais embutidas na URL JDBC")
    void rejectsHikariUsernameOverrideAndEmbeddedJdbcCredentials() {
        MockEnvironment hikariOverride = validEnvironment();
        hikariOverride.setProperty("spring.datasource.hikari.username", "postgres");
        RuntimeDatabaseRoleException ex1 = assertThrows(RuntimeDatabaseRoleException.class,
                () -> RuntimeDatabaseRoleEnvironmentValidator.validate(hikariOverride));
        assertTrue(ex1.getMessage().contains("spring.datasource.hikari.username"));

        MockEnvironment urlUser = validEnvironment();
        urlUser.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:35432/rewit_test?user=postgres");
        RuntimeDatabaseRoleException ex2 = assertThrows(RuntimeDatabaseRoleException.class,
                () -> RuntimeDatabaseRoleEnvironmentValidator.validate(urlUser));
        assertTrue(ex2.getMessage().contains("user"));

        MockEnvironment urlPass = validEnvironment();
        urlPass.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:35432/rewit_test?password=secret");
        RuntimeDatabaseRoleException ex3 = assertThrows(RuntimeDatabaseRoleException.class,
                () -> RuntimeDatabaseRoleEnvironmentValidator.validate(urlPass));
        assertTrue(ex3.getMessage().contains("password"));
        assertFalse(ex3.getMessage().contains("secret"));
    }

    @Test
    @DisplayName("FailureAnalyzer formata diagnóstico sem expor nenhuma senha configurada")
    void failureAnalyzerProducesSanitizedReport() {
        MockEnvironment env = validEnvironment();
        env.setProperty("spring.datasource.username", "postgres");
        env.setProperty("spring.datasource.password", MIG_PASS);

        RuntimeDatabaseRoleException ex = assertThrows(RuntimeDatabaseRoleException.class,
                () -> RuntimeDatabaseRoleEnvironmentValidator.validate(env));
        FailureAnalysis analysis = new RuntimeDatabaseRoleFailureAnalyzer().analyze(ex);

        assertNotNull(analysis);
        assertTrue(analysis.getDescription().contains("APP_DB_USER"));
        assertTrue(analysis.getDescription().contains("APP_DB_PASSWORD"));
        assertFalse(analysis.getDescription().contains(MIG_PASS));
        assertFalse(analysis.getDescription().contains(APP_PASS));
        assertTrue(analysis.getAction().contains("rewit_app"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application-local.yml", "application-external-test.yml"})
    @DisplayName("Perfis YAML nunca usam identidade migrator/owner como fallback para os pools runtime")
    void yamlProfilesNeverFallbackRuntimePoolsToMigratorUser(String yamlFile) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load(yamlFile, new FileSystemResource(Path.of("src", "main", "resources", yamlFile)));
        assertFalse(sources.isEmpty());
        PropertySource<?> source = sources.getFirst();

        for (String runtimeUserKey : List.of(
                "spring.datasource.username",
                "rewit.datasource.media.username",
                "rewit.datasource.moderation.username")) {
            Object raw = source.getProperty(runtimeUserKey);
            assertNotNull(raw, runtimeUserKey + " deve estar declarado em " + yamlFile);
            String value = raw.toString();
            assertFalse(value.contains(RuntimeDatabaseRolePolicy.MIGRATOR_POSTGRES_USER_ENV),
                    runtimeUserKey + " não pode fazer fallback para " + RuntimeDatabaseRolePolicy.MIGRATOR_POSTGRES_USER_ENV);
            assertFalse(value.contains(RuntimeDatabaseRolePolicy.MIGRATOR_FLYWAY_USER_ENV),
                    runtimeUserKey + " não pode fazer fallback para " + RuntimeDatabaseRolePolicy.MIGRATOR_FLYWAY_USER_ENV);
        }
    }
}
