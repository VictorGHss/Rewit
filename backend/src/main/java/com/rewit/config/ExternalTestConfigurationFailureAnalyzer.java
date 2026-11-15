package com.rewit.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Relatório "APPLICATION FAILED TO START" para o perfil external-test: lista cada propriedade recusada e a variável de
 * ambiente a definir. Sai como mensagem de log, que os logs JSON preservam (mensagens de exceção não).
 */
public class ExternalTestConfigurationFailureAnalyzer
        extends AbstractFailureAnalyzer<ExternalTestEnvironmentValidator.InvalidConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ExternalTestEnvironmentValidator.InvalidConfigurationException cause) {
        String description = "O perfil " + ExternalTestEnvironmentValidator.PROFILE
                + " recusou a inicialização antes de qualquer conexão:\n - " + String.join("\n - ", cause.getErrors());
        String action = "Defina as variáveis listadas no ambiente (.env carregado por scripts/start-external-test.ps1), com "
                + "valores próprios e sem os exemplos do .env.example. Veja docs/development/local-setup.md, seção 6.";
        return new FailureAnalysis(description, action, cause);
    }
}
