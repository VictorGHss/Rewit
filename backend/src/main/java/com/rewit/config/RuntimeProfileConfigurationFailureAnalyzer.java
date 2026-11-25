package com.rewit.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Relatório "APPLICATION FAILED TO START" para seleção ausente, conflitante ou incompatível de profile Spring.
 */
public class RuntimeProfileConfigurationFailureAnalyzer
        extends AbstractFailureAnalyzer<RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException> {

    @Override
    protected FailureAnalysis analyze(
            Throwable rootFailure,
            RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException cause
    ) {
        String description = "A seleção de profile Spring foi recusada (fail-closed) antes de qualquer conexão:\n - "
                + String.join("\n - ", cause.getErrors());
        String action = "Defina SPRING_PROFILES_ACTIVE explicitamente (local para desenvolvimento, external-test "
                + "para túnel de testes externos, ou prod para homologação/produção) e mantenha ENVIRONMENT "
                + "consistente. Veja docs/deployment/overview.md.";
        return new FailureAnalysis(description, action, cause);
    }
}
