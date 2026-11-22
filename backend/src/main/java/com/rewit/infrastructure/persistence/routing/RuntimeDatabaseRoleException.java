package com.rewit.infrastructure.persistence.routing;

import java.util.List;

/**
 * Exceção fail-closed para violações de configuração ou de identidade real PostgreSQL das roles
 * de runtime ({@code rewit_app}, {@code rewit_media} e {@code rewit_mod}).
 *
 * <p>As mensagens citam apenas nomes de propriedades, variáveis de ambiente, nomes de pools e roles
 * esperadas/observadas, jamais expondo senhas ou segredos.
 */
public class RuntimeDatabaseRoleException extends IllegalStateException {

    private final List<String> errors;

    public RuntimeDatabaseRoleException(String message, List<String> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    public RuntimeDatabaseRoleException(String message, List<String> errors, Throwable cause) {
        super(message, cause);
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
