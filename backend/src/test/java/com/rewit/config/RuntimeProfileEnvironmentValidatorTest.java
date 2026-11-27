package com.rewit.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Política fail-closed de seleção de profile Spring (F2-DEPLOY-03.3 / R1)")
class RuntimeProfileEnvironmentValidatorTest {

    @Test
    @DisplayName("1. Profile prod selecionado explicitamente é aceito")
    void acceptsProdProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("rewit.environment", "production");
        assertDoesNotThrow(() -> RuntimeProfileEnvironmentValidator.validate(env));
    }

    @Test
    @DisplayName("1b. Profile prod sem ENVIRONMENT definido também é aceito")
    void acceptsProdProfileWithoutEnvironmentVar() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertDoesNotThrow(() -> RuntimeProfileEnvironmentValidator.validate(env));
    }

    @ParameterizedTest
    @ValueSource(strings = {"production", "staging", "homolog", "dev", "default", "unknown-profile"})
    @DisplayName("AUDIT-01: Profile desconhecido ou typo (ex.: production) é rejeitado pela allowlist")
    void rejectsUnknownOrTypoProfiles(String unknownProfile) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(unknownProfile);

        RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException ex = assertThrows(
                RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException.class,
                () -> RuntimeProfileEnvironmentValidator.validate(env)
        );
        assertTrue(ex.getMessage().contains(unknownProfile));
    }

    @Test
    @DisplayName("AUDIT-01: Profile desconhecido combinado com prod também é rejeitado")
    void rejectsProdCombinedWithUnknownProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod", "extra-custom");

        assertThrows(RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException.class,
                () -> RuntimeProfileEnvironmentValidator.validate(env));
    }

    @Test
    @DisplayName("2. Ausência de seleção explícita de profile falha (sem fallback silencioso para local/default)")
    void rejectsMissingActiveProfile() {
        MockEnvironment env = new MockEnvironment();
        RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException ex = assertThrows(
                RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException.class,
                () -> RuntimeProfileEnvironmentValidator.validate(env)
        );
        assertTrue(ex.getMessage().contains("SPRING_PROFILES_ACTIVE"));

        FailureAnalysis analysis = new RuntimeProfileConfigurationFailureAnalyzer().analyze(ex);
        assertNotNull(analysis);
        assertTrue(analysis.getDescription().contains("SPRING_PROFILES_ACTIVE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production", "staging", "homolog", "homologation", "hml"})
    @DisplayName("3. Tentativa de iniciar ambiente de implantação com profile local é rejeitada")
    void rejectsLocalProfileInDeploymentEnvironment(String deploymentEnv) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        env.setProperty("rewit.environment", deploymentEnv);

        RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException ex = assertThrows(
                RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException.class,
                () -> RuntimeProfileEnvironmentValidator.validate(env)
        );
        assertTrue(ex.getMessage().contains("local"));
        assertTrue(ex.getMessage().contains("prod"));
    }

    @Test
    @DisplayName("3b. Tentativa de iniciar profile prod com ENVIRONMENT=local é rejeitada")
    void rejectsProdProfileWithLocalEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("ENVIRONMENT", "local");

        assertThrows(RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException.class,
                () -> RuntimeProfileEnvironmentValidator.validate(env));
    }

    @Test
    @DisplayName("4. Combinações conflitantes entre local, external-test, prod e test são rejeitadas")
    void rejectsConflictingProfiles() {
        for (String[] pair : new String[][]{
                {"local", "prod"},
                {"external-test", "prod"},
                {"local", "external-test"},
                {"test", "prod"},
                {"test", "external-test"}
        }) {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles(pair);
            assertThrows(RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException.class,
                    () -> RuntimeProfileEnvironmentValidator.validate(env),
                    "Deve rejeitar combinação: " + String.join(",", pair));
        }
    }

    @Test
    @DisplayName("5. Inicialização local explícita é preservada")
    void preservesLocalProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        env.setProperty("rewit.environment", "local");
        assertDoesNotThrow(() -> RuntimeProfileEnvironmentValidator.validate(env));
    }

    @Test
    @DisplayName("5b. Inicialização external-test explícita é preservada")
    void preservesExternalTestProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("external-test");
        assertDoesNotThrow(() -> RuntimeProfileEnvironmentValidator.validate(env));
    }

    @Test
    @DisplayName("6. Ambiente de testes com profile test explícito é preservado")
    void preservesTestProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        assertDoesNotThrow(() -> RuntimeProfileEnvironmentValidator.validate(env));
    }
}
