package com.rewit.infrastructure.persistence.schema;

import com.rewit.config.ProductionEnvironmentValidator;
import com.rewit.infrastructure.persistence.routing.ModerationDataSourceConfig;
import com.rewit.infrastructure.persistence.routing.PostgresRuntimeRoleIdentityValidator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Registra e ordena o {@link ReadOnlyFlywaySchemaValidator} no startup da API (F2-DEPLOY-03.5.2A).
 *
 * <p>Ativação:
 * <ul>
 *   <li>No perfil {@code prod}: sempre ativo de forma incondicional (não pode ser desabilitado por propriedade).</li>
 *   <li>Fora de {@code prod} ({@code local}, {@code external-test}): inativo por padrão para preservar o fluxo
 *       com Flyway no startup; pode ser ativado explicitamente via {@code rewit.schema-validation.read-only.enabled=true}
 *       em cenários de teste integrado.</li>
 * </ul>
 *
 * <p>Ordem determinística no startup:
 * <ol>
 *   <li>{@link PostgresRuntimeRoleIdentityValidator} valida identidades reais das 3 roles runtime.</li>
 *   <li>{@link ReadOnlyFlywaySchemaValidator} valida o histórico Flyway em modo somente leitura ({@code rewit_app}).</li>
 *   <li>{@code entityManagerFactory} (Hibernate {@code ddl-auto=validate}) e servidor HTTP só iniciam depois.</li>
 * </ol>
 */
@Configuration
public class ReadOnlySchemaValidationConfig {

    public static final String SCHEMA_VALIDATOR_BEAN_NAME = "readOnlyFlywaySchemaValidator";
    public static final String ENABLED_PROPERTY = "rewit.schema-validation.read-only.enabled";

    @Bean(name = SCHEMA_VALIDATOR_BEAN_NAME)
    @Lazy(false)
    @Conditional(ReadOnlySchemaValidationActiveCondition.class)
    public ReadOnlyFlywaySchemaValidator readOnlyFlywaySchemaValidator(
            @Qualifier("appDataSource") DataSource appDataSource,
            PostgresRuntimeRoleIdentityValidator runtimeRoleIdentityValidator
    ) {
        // A injeção de runtimeRoleIdentityValidator garante que as 3 roles reais já foram validadas no PostgreSQL.
        ReadOnlyFlywaySchemaValidator validator = new ReadOnlyFlywaySchemaValidator(appDataSource);
        validator.validate();
        return validator;
    }

    /**
     * Garante que {@code entityManagerFactory} dependa de {@code readOnlyFlywaySchemaValidator} sempre que
     * este bean estiver registrado, abortando o startup antes do Hibernate {@code ddl-auto=validate} e antes
     * da abertura da porta HTTP.
     */
    @Bean
    public static BeanFactoryPostProcessor entityManagerFactoryDependsOnReadOnlySchemaValidator() {
        return beanFactory -> {
            if (!beanFactory.containsBeanDefinition(SCHEMA_VALIDATOR_BEAN_NAME)) {
                return;
            }
            if (beanFactory.containsBeanDefinition("entityManagerFactory")) {
                BeanDefinition emfDef = beanFactory.getBeanDefinition("entityManagerFactory");
                String[] existing = emfDef.getDependsOn();
                List<String> deps = existing == null
                        ? new ArrayList<>()
                        : new ArrayList<>(Arrays.asList(existing));
                if (!deps.contains(ModerationDataSourceConfig.IDENTITY_VALIDATOR_BEAN_NAME)) {
                    deps.add(ModerationDataSourceConfig.IDENTITY_VALIDATOR_BEAN_NAME);
                }
                if (!deps.contains(SCHEMA_VALIDATOR_BEAN_NAME)) {
                    deps.add(SCHEMA_VALIDATOR_BEAN_NAME);
                }
                emfDef.setDependsOn(deps.toArray(String[]::new));
            }
        };
    }

    static final class ReadOnlySchemaValidationActiveCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            if (env.acceptsProfiles(Profiles.of(ProductionEnvironmentValidator.PROFILE))) {
                return true;
            }
            return Boolean.parseBoolean(env.getProperty(ENABLED_PROPERTY, "false"));
        }
    }
}
