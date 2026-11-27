package com.rewit.infrastructure.persistence.routing;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.List;

/**
 * Validador antecipado fail-closed das propriedades das três roles PostgreSQL de runtime
 * ({@code rewit_app}, {@code rewit_media} e {@code rewit_mod}).
 *
 * <p>Registrado em {@code META-INF/spring.factories} como {@link ApplicationContextInitializer},
 * executa antes da criação de qualquer bean — inclusive antes do {@code Flyway} aplicar migrations —
 * impedindo que uma configuração perigosa (ex.: {@code APP_DB_USER=postgres} ou senha igual à do
 * migrator) avance até a camada de migração ou abra a porta HTTP.
 */
public class RuntimeDatabaseRoleEnvironmentValidator
        implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        validate(applicationContext.getEnvironment());
    }

    public static void validate(ConfigurableEnvironment env) {
        List<String> errors = RuntimeDatabaseRolePolicy.validateEnvironment(env);
        if (!errors.isEmpty()) {
            throw new RuntimeDatabaseRoleException(
                    "Inicialização recusada (fail-closed) por configuração inválida das roles PostgreSQL de runtime: "
                            + String.join("; ", errors),
                    errors
            );
        }
    }
}
