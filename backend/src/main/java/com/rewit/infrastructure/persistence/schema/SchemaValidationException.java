package com.rewit.infrastructure.persistence.schema;

import java.util.List;

/**
 * Exceção fail-closed lançada durante o startup da API quando a validação somente leitura do
 * histórico de migrations Flyway encontra inconsistências (banco vazio, migration pendente,
 * migration com falha, checksum divergente ou migration futura/inesperada).
 *
 * <p>As mensagens citam apenas versões, nomes de tabelas/estados e diagnósticos operacionais,
 * nunca credenciais ou URLs completas.
 */
public final class SchemaValidationException extends IllegalStateException {

    private final List<String> errors;

    public SchemaValidationException(List<String> errors) {
        super("Validação somente leitura do schema Flyway recusou a inicialização da API (fail-closed): "
                + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public SchemaValidationException(List<String> errors, Throwable cause) {
        super("Validação somente leitura do schema Flyway recusou a inicialização da API (fail-closed): "
                + String.join("; ", errors), cause);
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
