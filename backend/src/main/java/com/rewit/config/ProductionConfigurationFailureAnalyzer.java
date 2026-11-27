package com.rewit.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Relatório "APPLICATION FAILED TO START" para o perfil {@code prod}: lista cada propriedade
 * recusada e a variável de ambiente correspondente sem expor valores reais ou segredos.
 */
public class ProductionConfigurationFailureAnalyzer
        extends AbstractFailureAnalyzer<ProductionEnvironmentValidator.InvalidConfigurationException> {

    @Override
    protected FailureAnalysis analyze(
            Throwable rootFailure,
            ProductionEnvironmentValidator.InvalidConfigurationException cause
    ) {
        String description = "O perfil " + ProductionEnvironmentValidator.PROFILE
                + " recusou a inicialização (fail-closed) antes de qualquer conexão ou migration:\n - "
                + String.join("\n - ", cause.getErrors());
        String action = "Corrija as variáveis obrigatórias e parâmetros de segurança no gerenciador de segredos "
                + "do ambiente de implantação. Consulte o contrato do perfil prod em docs/deployment/overview.md.";
        return new FailureAnalysis(description, action, cause);
    }
}
