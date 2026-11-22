package com.rewit.infrastructure.persistence.routing;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Configuração fail-closed dos DataSources separados ({@code rewit_app}, {@code rewit_media} e {@code rewit_mod}),
 * da verificação da identidade real das conexões PostgreSQL no startup e do roteamento transacional
 * {@link ModerationRoutingDataSource} sem duplicação de repositórios JPA (S3-A13-F2 / SEC-F2-03 / F2-DEPLOY-02).
 */
@Configuration
public class ModerationDataSourceConfig {

    public static final String IDENTITY_VALIDATOR_BEAN_NAME = "postgresRuntimeRoleIdentityValidator";

    @Bean(name = "appDataSource", destroyMethod = "close")
    public HikariDataSource appDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.flyway.user:}") String flywayUsername,
            @Value("${spring.flyway.password:}") String flywayPassword,
            @Value("${FLYWAY_DB_USER:}") String flywayEnvUser,
            @Value("${FLYWAY_DB_PASSWORD:}") String flywayEnvPassword,
            @Value("${POSTGRES_USER:}") String postgresUser,
            @Value("${POSTGRES_PASSWORD:}") String postgresPassword,
            @Value("${spring.datasource.driver-class-name:org.postgresql.Driver}") String driverClassName,
            @Value("${spring.datasource.hikari.maximum-pool-size:10}") int maxPoolSize,
            @Value("${spring.datasource.hikari.minimum-idle:2}") int minIdle,
            @Value("${spring.datasource.hikari.connection-timeout:30000}") long connectionTimeout,
            @Value("${spring.datasource.hikari.idle-timeout:600000}") long idleTimeout,
            @Value("${spring.datasource.hikari.max-lifetime:1800000}") long maxLifetime
    ) {
        RuntimeDatabaseRolePolicy.validatePoolCredentials(
                "APP_DB_USER",
                username,
                RuntimeDatabaseRolePolicy.EXPECTED_APP_ROLE,
                "APP_DB_PASSWORD",
                password,
                url,
                Map.of(),
                collectNamedValues(
                        "spring.flyway.user", flywayUsername,
                        "FLYWAY_DB_USER", flywayEnvUser,
                        "POSTGRES_USER", postgresUser
                ),
                collectNamedValues(
                        "spring.flyway.password", flywayPassword,
                        "FLYWAY_DB_PASSWORD", flywayEnvPassword,
                        "POSTGRES_PASSWORD", postgresPassword
                )
        );

        HikariConfig config = new HikariConfig();
        config.setPoolName("rewit-app-pool");
        config.setJdbcUrl(url);
        config.setUsername(username.trim());
        config.setPassword(password);
        config.setDriverClassName(driverClassName);
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setConnectionTimeout(connectionTimeout);
        config.setIdleTimeout(idleTimeout);
        config.setMaxLifetime(maxLifetime);
        return new HikariDataSource(config);
    }

    @Bean(name = "moderationDataSource", destroyMethod = "close")
    public HikariDataSource moderationDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String appUsername,
            @Value("${spring.datasource.password}") String appPassword,
            @Value("${rewit.datasource.moderation.username}") String modUsername,
            @Value("${rewit.datasource.moderation.password}") String modPassword,
            @Value("${spring.flyway.user:}") String flywayUsername,
            @Value("${spring.flyway.password:}") String flywayPassword,
            @Value("${FLYWAY_DB_USER:}") String flywayEnvUser,
            @Value("${FLYWAY_DB_PASSWORD:}") String flywayEnvPassword,
            @Value("${POSTGRES_USER:}") String postgresUser,
            @Value("${POSTGRES_PASSWORD:}") String postgresPassword,
            @Value("${spring.datasource.driver-class-name:org.postgresql.Driver}") String driverClassName,
            @Value("${rewit.datasource.moderation.hikari.maximum-pool-size:5}") int maxPoolSize,
            @Value("${rewit.datasource.moderation.hikari.minimum-idle:1}") int minIdle,
            @Value("${rewit.datasource.moderation.hikari.connection-timeout:30000}") long connectionTimeout,
            @Value("${rewit.datasource.moderation.hikari.idle-timeout:600000}") long idleTimeout,
            @Value("${rewit.datasource.moderation.hikari.max-lifetime:1800000}") long maxLifetime
    ) {
        RuntimeDatabaseRolePolicy.validatePoolCredentials(
                "MOD_DB_USER",
                modUsername,
                RuntimeDatabaseRolePolicy.EXPECTED_MOD_ROLE,
                "MOD_DB_PASSWORD",
                modPassword,
                url,
                collectNamedValues("APP_DB_PASSWORD", appPassword),
                collectNamedValues(
                        "spring.flyway.user", flywayUsername,
                        "FLYWAY_DB_USER", flywayEnvUser,
                        "POSTGRES_USER", postgresUser
                ),
                collectNamedValues(
                        "spring.flyway.password", flywayPassword,
                        "FLYWAY_DB_PASSWORD", flywayEnvPassword,
                        "POSTGRES_PASSWORD", postgresPassword
                )
        );
        if (appUsername != null && modUsername.trim().equals(appUsername.trim())) {
            throw new IllegalArgumentException(
                    "MOD_DB_USER não pode coincidir com APP_DB_USER: as roles de runtime e moderação devem ser distintas.");
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("rewit-mod-pool");
        config.setJdbcUrl(url);
        config.setUsername(modUsername.trim());
        config.setPassword(modPassword);
        config.setDriverClassName(driverClassName);
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setConnectionTimeout(connectionTimeout);
        config.setIdleTimeout(idleTimeout);
        config.setMaxLifetime(maxLifetime);
        return new HikariDataSource(config);
    }

    @Bean(name = "mediaLifecycleDataSource", destroyMethod = "close")
    public HikariDataSource mediaLifecycleDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String appUsername,
            @Value("${spring.datasource.password}") String appPassword,
            @Value("${rewit.datasource.moderation.username}") String modUsername,
            @Value("${rewit.datasource.moderation.password}") String modPassword,
            @Value("${rewit.datasource.media.username}") String mediaUsername,
            @Value("${rewit.datasource.media.password}") String mediaPassword,
            @Value("${spring.flyway.user:}") String flywayUsername,
            @Value("${spring.flyway.password:}") String flywayPassword,
            @Value("${FLYWAY_DB_USER:}") String flywayEnvUser,
            @Value("${FLYWAY_DB_PASSWORD:}") String flywayEnvPassword,
            @Value("${POSTGRES_USER:}") String postgresUser,
            @Value("${POSTGRES_PASSWORD:}") String postgresPassword,
            @Value("${spring.datasource.driver-class-name:org.postgresql.Driver}") String driverClassName,
            @Value("${rewit.datasource.media.hikari.maximum-pool-size:5}") int maxPoolSize,
            @Value("${rewit.datasource.media.hikari.minimum-idle:1}") int minIdle,
            @Value("${rewit.datasource.media.hikari.connection-timeout:30000}") long connectionTimeout,
            @Value("${rewit.datasource.media.hikari.idle-timeout:600000}") long idleTimeout,
            @Value("${rewit.datasource.media.hikari.max-lifetime:1800000}") long maxLifetime
    ) {
        RuntimeDatabaseRolePolicy.validatePoolCredentials(
                "MEDIA_DB_USER",
                mediaUsername,
                RuntimeDatabaseRolePolicy.EXPECTED_MEDIA_ROLE,
                "MEDIA_DB_PASSWORD",
                mediaPassword,
                url,
                collectNamedValues(
                        "APP_DB_PASSWORD", appPassword,
                        "MOD_DB_PASSWORD", modPassword
                ),
                collectNamedValues(
                        "spring.flyway.user", flywayUsername,
                        "FLYWAY_DB_USER", flywayEnvUser,
                        "POSTGRES_USER", postgresUser
                ),
                collectNamedValues(
                        "spring.flyway.password", flywayPassword,
                        "FLYWAY_DB_PASSWORD", flywayEnvPassword,
                        "POSTGRES_PASSWORD", postgresPassword
                )
        );
        if (appUsername != null && mediaUsername.trim().equals(appUsername.trim())) {
            throw new IllegalArgumentException(
                    "MEDIA_DB_USER não pode coincidir com APP_DB_USER: as roles devem ser distintas.");
        }
        if (modUsername != null && mediaUsername.trim().equals(modUsername.trim())) {
            throw new IllegalArgumentException(
                    "MEDIA_DB_USER não pode coincidir com MOD_DB_USER: as roles devem ser distintas.");
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("rewit-media-pool");
        config.setJdbcUrl(url);
        config.setUsername(mediaUsername.trim());
        config.setPassword(mediaPassword);
        config.setDriverClassName(driverClassName);
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setConnectionTimeout(connectionTimeout);
        config.setIdleTimeout(idleTimeout);
        config.setMaxLifetime(maxLifetime);
        return new HikariDataSource(config);
    }

    @Bean(name = IDENTITY_VALIDATOR_BEAN_NAME)
    public PostgresRuntimeRoleIdentityValidator postgresRuntimeRoleIdentityValidator(
            @Qualifier("appDataSource") DataSource appDataSource,
            @Qualifier("mediaLifecycleDataSource") DataSource mediaLifecycleDataSource,
            @Qualifier("moderationDataSource") DataSource moderationDataSource
    ) {
        return new PostgresRuntimeRoleIdentityValidator(appDataSource, mediaLifecycleDataSource, moderationDataSource);
    }

    /**
     * Garante que a comprovação real de identidade dos três pools runtime ocorra também antes do
     * {@code flywayInitializer} quando o Flyway estiver habilitado no startup, sem misturar a credencial
     * migrator com os pools runtime e sem impedir que o Flyway utilize sua própria conexão dedicada.
     */
    @Bean
    public static BeanFactoryPostProcessor flywayDependsOnRuntimeRoleIdentityValidator() {
        return beanFactory -> {
            if (!beanFactory.containsBeanDefinition(IDENTITY_VALIDATOR_BEAN_NAME)) {
                return;
            }
            if (beanFactory.containsBeanDefinition("flywayInitializer")) {
                BeanDefinition flywayInitDef = beanFactory.getBeanDefinition("flywayInitializer");
                Set<String> deps = new LinkedHashSet<>();
                if (flywayInitDef.getDependsOn() != null) {
                    deps.addAll(Arrays.asList(flywayInitDef.getDependsOn()));
                }
                deps.add(IDENTITY_VALIDATOR_BEAN_NAME);
                flywayInitDef.setDependsOn(deps.toArray(String[]::new));
            }
        };
    }

    @Bean
    @Primary
    public DataSource dataSource(
            @Qualifier("appDataSource") DataSource appDataSource,
            @Qualifier("mediaLifecycleDataSource") DataSource mediaLifecycleDataSource,
            @Qualifier("moderationDataSource") DataSource moderationDataSource
    ) {
        ModerationRoutingDataSource routingDataSource = new ModerationRoutingDataSource();
        Map<Object, Object> targets = new java.util.HashMap<>();
        targets.put(DatabaseRole.APP, appDataSource);
        targets.put(DatabaseRole.MEDIA_LIFECYCLE, mediaLifecycleDataSource);
        targets.put(DatabaseRole.MODERATION, moderationDataSource);
        routingDataSource.setTargetDataSources(targets);
        routingDataSource.setDefaultTargetDataSource(appDataSource);
        routingDataSource.afterPropertiesSet();
        return routingDataSource;
    }

    /**
     * Advisor com precedência estritamente superior (número menor) à do {@code TransactionInterceptor}
     * ({@link Ordered#LOWEST_PRECEDENCE}), garantindo que {@link DatabaseRole#MODERATION} esteja ativo
     * antes da abertura da transação e da aquisição da conexão física.
     */
    @Bean
    public Advisor moderationRoutingAdvisor() {
        Pointcut methodPointcut = new AnnotationMatchingPointcut(null, ModerationTransactional.class, true);
        Pointcut classPointcut = new AnnotationMatchingPointcut(ModerationTransactional.class, true);
        ComposablePointcut combined = new ComposablePointcut(methodPointcut).union(classPointcut);

        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(combined, new ModerationRoutingMethodInterceptor());
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return advisor;
    }

    /**
     * Advisor com precedência estritamente superior à do {@code TransactionInterceptor}, garantindo que
     * {@link DatabaseRole#MEDIA_LIFECYCLE} esteja ativo antes da abertura da transação física (SEC-F2-03).
     */
    @Bean
    public Advisor mediaLifecycleRoutingAdvisor() {
        Pointcut methodPointcut = new AnnotationMatchingPointcut(null, MediaLifecycleTransactional.class, true);
        Pointcut classPointcut = new AnnotationMatchingPointcut(MediaLifecycleTransactional.class, true);
        ComposablePointcut combined = new ComposablePointcut(methodPointcut).union(classPointcut);

        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(combined, new MediaLifecycleRoutingMethodInterceptor());
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return advisor;
    }

    private static Map<String, String> collectNamedValues(String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String key = pairs[i];
            String value = pairs[i + 1];
            if (value != null && !value.isBlank()) {
                result.put(key, value);
            }
        }
        return result;
    }
}
