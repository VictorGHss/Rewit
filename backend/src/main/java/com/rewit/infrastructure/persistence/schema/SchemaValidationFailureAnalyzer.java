package com.rewit.infrastructure.persistence.schema;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Formata falhas de validação somente leitura do schema Flyway em "APPLICATION FAILED TO START"
 * com orientação operacional clara para executar o runner pré-deploy, sem expor credenciais.
 */
public class SchemaValidationFailureAnalyzer
        extends AbstractFailureAnalyzer<SchemaValidationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, SchemaValidationException cause) {
        String description = "A validação somente leitura do schema Flyway falhou antes de liberar a API:\n - "
                + String.join("\n - ", cause.getErrors());
        String action = "Execute o runner isolado de migrations pré-deploy "
                + "(java -Dloader.main=com.rewit.migration.RewitMigrationRunner -jar /app/app.jar) "
                + "com as credenciais dedicadas do migrator antes de iniciar a API em produção.";
        return new FailureAnalysis(description, action, cause);
    }
}
