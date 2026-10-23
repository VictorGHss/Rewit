package com.rewit.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Validação de inicialização do perfil external-test (Cloudflare Tunnel)")
class ExternalTestEnvironmentValidatorTest {

    private static final String DB_PASSWORD = "senha-runtime-Xq7#kP2v";
    private static final String STORAGE_SECRET = "storage-secret-9fT2mQ8z";

    private static MockEnvironment validEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(ExternalTestEnvironmentValidator.PROFILE);
        env.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5433/rewit_db");
        env.setProperty("spring.datasource.username", "rewit_app");
        env.setProperty("spring.datasource.password", DB_PASSWORD);
        env.setProperty("spring.flyway.user", "rewit_user");
        env.setProperty("spring.flyway.password", "senha-migracao-Lw3!rT9d");
        env.setProperty("spring.data.redis.host", "localhost");
        env.setProperty("spring.data.redis.port", "6379");
        env.setProperty("rewit.minio.endpoint", "http://localhost:8333");
        env.setProperty("rewit.minio.access-key", "storage-access-4hJ8");
        env.setProperty("rewit.minio.secret-key", STORAGE_SECRET);
        env.setProperty("rewit.minio.bucket-name", "rewit-local");
        env.setProperty("rewit.security.jwt-secret", "jwt-externo-com-entropia-suficiente-Zp4!Qw8@Lk2#");
        env.setProperty("rewit.rate-limit.key-secret", "rate-limit-externo-com-entropia-Hy6$Vb3%Nm1&");
        return env;
    }

    @Test
    @DisplayName("Configuração completa passa")
    void acceptsCompleteConfiguration() {
        assertDoesNotThrow(() -> ExternalTestEnvironmentValidator.validate(validEnvironment()));
    }

    @Test
    @DisplayName("Fora do perfil external-test nada é exigido")
    void ignoresOtherProfiles() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        assertDoesNotThrow(() -> ExternalTestEnvironmentValidator.validate(env));
    }

    @Test
    @DisplayName("Placeholder sem variável de ambiente falha citando a variável, sem subir com o literal")
    void rejectsUnresolvedPlaceholder() {
        MockEnvironment env = validEnvironment();
        env.setProperty("spring.datasource.password", "${APP_DB_PASSWORD}");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ExternalTestEnvironmentValidator.validate(env));
        assertTrue(error.getMessage().contains("spring.datasource.password"));
        assertTrue(error.getMessage().contains("APP_DB_PASSWORD"));
    }

    @Test
    @DisplayName("Propriedade obrigatória ausente ou vazia falha")
    void rejectsMissingOrBlank() {
        MockEnvironment missing = new MockEnvironment();
        missing.setActiveProfiles(ExternalTestEnvironmentValidator.PROFILE);
        IllegalStateException all = assertThrows(IllegalStateException.class,
                () -> ExternalTestEnvironmentValidator.validate(missing));
        for (String property : ExternalTestEnvironmentValidator.requiredProperties()) {
            if (!"spring.datasource.username".equals(property)) {
                assertTrue(all.getMessage().contains(property), "a mensagem deve citar " + property);
            }
        }
        for (String variable : new String[]{"APP_DB_PASSWORD", "OBJECT_STORAGE_SECRET_KEY", "JWT_SECRET",
                "RATE_LIMIT_KEY_SECRET"}) {
            assertTrue(all.getMessage().contains(variable), "a mensagem deve citar " + variable);
        }

        MockEnvironment blank = validEnvironment();
        blank.setProperty("rewit.minio.secret-key", "  ");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ExternalTestEnvironmentValidator.validate(blank));
        assertTrue(error.getMessage().contains("OBJECT_STORAGE_SECRET_KEY"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"change-me", "CHANGE-ME", "changeme", "rewit_local_password"})
    @DisplayName("Senha de exemplo ou fallback do perfil local é recusada")
    void rejectsExampleDatabasePassword(String insecure) {
        MockEnvironment env = validEnvironment();
        env.setProperty("spring.flyway.password", insecure);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ExternalTestEnvironmentValidator.validate(env));
        assertTrue(error.getMessage().contains("spring.flyway.password"));
    }

    @Test
    @DisplayName("Credenciais de storage do fallback local são recusadas")
    void rejectsLocalStorageFallback() {
        MockEnvironment env = validEnvironment();
        env.setProperty("rewit.minio.access-key", "rewit_local_access");
        env.setProperty("rewit.minio.secret-key", "rewit_local_secret");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ExternalTestEnvironmentValidator.validate(env));
        assertTrue(error.getMessage().contains("rewit.minio.access-key"));
        assertTrue(error.getMessage().contains("rewit.minio.secret-key"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://localhost/rewit_db", "jdbc:postgresql:rewit_db", "jdbc:postgresql:///rewit_db",
            "jdbc:mysql://localhost:3306/rewit_db"})
    @DisplayName("URL JDBC sem porta explícita ou de outro banco é recusada")
    void rejectsImplicitDatasourceUrl(String url) {
        MockEnvironment env = validEnvironment();
        env.setProperty("spring.datasource.url", url);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ExternalTestEnvironmentValidator.validate(env));
        assertTrue(error.getMessage().contains("spring.datasource.url precisa ser jdbc:postgresql://host:porta/database"));
    }

    @Test
    @DisplayName("Relatório de falha na inicialização lista cada variável a definir, sem valores")
    void failureAnalysisListsEachProblem() {
        MockEnvironment env = validEnvironment();
        env.setProperty("spring.datasource.password", "${APP_DB_PASSWORD}");
        env.setProperty("rewit.minio.secret-key", "rewit_local_secret");

        ExternalTestEnvironmentValidator.InvalidConfigurationException error = assertThrows(
                ExternalTestEnvironmentValidator.InvalidConfigurationException.class,
                () -> ExternalTestEnvironmentValidator.validate(env));
        FailureAnalysis analysis = new ExternalTestConfigurationFailureAnalyzer().analyze(error);

        assertNotNull(analysis);
        assertEquals(2, error.getErrors().size());
        assertTrue(analysis.getDescription().contains("APP_DB_PASSWORD"));
        assertTrue(analysis.getDescription().contains("OBJECT_STORAGE_SECRET_KEY"));
        assertFalse(analysis.getDescription().contains("rewit_local_secret"));
        assertTrue(analysis.getAction().contains("local-setup.md"));
    }

    @Test
    @DisplayName("Mensagem de erro nunca expõe valores configurados")
    void errorMessageNeverContainsValues() {
        MockEnvironment env = validEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql://db-host-secreto/rewit_db");
        env.setProperty("rewit.minio.access-key", "change-me");
        env.setProperty("spring.data.redis.host", "");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ExternalTestEnvironmentValidator.validate(env));
        assertFalse(error.getMessage().contains("db-host-secreto"));
        assertFalse(error.getMessage().contains("change-me"));
        assertFalse(error.getMessage().contains(DB_PASSWORD));
        assertFalse(error.getMessage().contains(STORAGE_SECRET));
    }
}
