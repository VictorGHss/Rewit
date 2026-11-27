package com.rewit.infrastructure.persistence.routing;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Relatório "APPLICATION FAILED TO START" para violações fail-closed de configuração ou identidade real
 * das roles PostgreSQL de runtime ({@code rewit_app}, {@code rewit_media} e {@code rewit_mod}).
 *
 * <p>Garante que o motivo da recusa apareça na mensagem de log (preservada pelos logs JSON sanitizados),
 * sem jamais expor senhas ou segredos.
 */
public class RuntimeDatabaseRoleFailureAnalyzer extends AbstractFailureAnalyzer<RuntimeDatabaseRoleException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, RuntimeDatabaseRoleException cause) {
        String description = "A validação fail-closed de identidade das roles PostgreSQL recusou a inicialização:\n - "
                + String.join("\n - ", cause.getErrors());
        String action = "Configure exclusivamente as roles de runtime fixas (APP_DB_USER=rewit_app, "
                + "MEDIA_DB_USER=rewit_media, MOD_DB_USER=rewit_mod) com senhas CSPRNG independentes entre si e "
                + "diferentes da credencial migrator/owner, provisionadas via infrastructure/postgres/provision-runtime-role.sql "
                + "(NOSUPERUSER NOCREATEROLE NOCREATEDB NOREPLICATION NOBYPASSRLS NOINHERIT, sem memberships "
                + "e sem ownership do banco ou de schemas).";
        return new FailureAnalysis(description, action, cause);
    }
}
