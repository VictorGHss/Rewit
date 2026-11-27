package com.rewit.config;

import com.rewit.infrastructure.persistence.PostgresProductionJdbcUrlValidator;
import com.rewit.infrastructure.persistence.routing.RuntimeDatabaseRolePolicy;
import com.rewit.infrastructure.ratelimit.RateLimitBackendFailureMode;
import com.rewit.infrastructure.ratelimit.RateLimitProperties;
import com.rewit.infrastructure.security.SecuritySecretValidator;
import org.springframework.boot.actuate.autoconfigure.endpoint.PropertiesEndpointAccessResolver;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.endpoint.Access;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.Show;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointProperties;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthProperties;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Validação fail-closed do perfil {@code prod} (homologação e produção, F2-DEPLOY-03.3 / R1 / 03.5.2A).
 *
 * <p>Registrado em {@code META-INF/spring.factories} como {@link ApplicationContextInitializer},
 * executa somente quando o perfil {@code prod} está ativo e antes da criação de DataSources,
 * da validação somente leitura do schema e da abertura das portas HTTP.
 *
 * <p>Reutiliza integralmente {@link RuntimeDatabaseRolePolicy}, {@link PostgresProductionJdbcUrlValidator}
 * e {@link SecuritySecretValidator}, sem duplicar regras nem enfraquecer requisitos de segurança.
 * Em {@code prod}, exige {@code spring.flyway.enabled=false} e proíbe qualquer credencial migrator ou
 * configuração incompatível do Flyway no processo da API. As mensagens citam apenas nomes de
 * propriedades e variáveis de ambiente, nunca valores reais, URLs completas ou segredos.
 */
public class ProductionEnvironmentValidator
        implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

    public static final String PROFILE = RuntimeProfileEnvironmentValidator.PROFILE_PROD;

    private static final Pattern KEY_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

    private static final Set<String> FORBIDDEN_PROD_MIGRATOR_ENV_VARS = Set.of(
            "FLYWAY_DB_USER",
            "FLYWAY_DB_PASSWORD",
            "POSTGRES_USER",
            "POSTGRES_PASSWORD"
    );

    private static final Set<String> INSECURE_PLACEHOLDERS = Set.of(
            "change-me",
            "change_me",
            "changeme",
            "placeholder",
            "default",
            "rewit_local_password",
            "rewit_local_access",
            "rewit_local_secret",
            "rewit-local",
            "exemplo_chave_maps_api"
    );

    private static final Set<String> WILDCARD_PROXY_PATTERNS = Set.of(".*", ".+", "*");

    private static final Set<String> ALLOWED_ACTUATOR_ENDPOINTS = Set.of(
            "health",
            "info",
            "prometheus"
    );

    private static final Set<String> REQUIRED_LIVENESS_MEMBERS = Set.of(
            "livenessstate"
    );

    private static final Set<String> REQUIRED_READINESS_MEMBERS = Set.of(
            "readinessstate",
            "db",
            "redis",
            "objectstorage"
    );

    private static final Set<String> UNHEALTHY_STATUS_KEYS = Set.of(
            "DOWN",
            "OUT_OF_SERVICE"
    );

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 15;
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        validate(applicationContext.getEnvironment());
    }

    public static void validate(ConfigurableEnvironment env) {
        if (!env.acceptsProfiles(Profiles.of(PROFILE))) {
            return;
        }

        List<String> errors = new ArrayList<>();

        validateHttpAndRuntimeHardening(env, errors);
        validateDatabaseAndFlyway(env, errors);
        validateCryptographicSecrets(env, errors);
        validateRedis(env, errors);
        validateObjectStorage(env, errors);
        validateOptionalIntegrations(env, errors);

        if (!errors.isEmpty()) {
            throw new InvalidConfigurationException(errors);
        }
    }

    private static void validateHttpAndRuntimeHardening(ConfigurableEnvironment env, List<String> errors) {
        Binder binder = Binder.get(env);

        // R1-05: valida o objeto ErrorProperties efetivamente vinculado pelo Spring Boot 4.1.1 (relaxed binding:
        // kebab-case, camelCase, variáveis de ambiente, CLI e SPRING_APPLICATION_JSON)
        try {
            ErrorProperties errorProperties = binder.bind("spring.web.error", ErrorProperties.class)
                    .orElseGet(ErrorProperties::new);
            if (errorProperties.getIncludeMessage() != ErrorProperties.IncludeAttribute.NEVER) {
                errors.add("spring.web.error.include-message deve ser 'never' em produção.");
            }
            if (errorProperties.getIncludeBindingErrors() != ErrorProperties.IncludeAttribute.NEVER) {
                errors.add("spring.web.error.include-binding-errors deve ser 'never' em produção.");
            }
            if (errorProperties.getIncludeStacktrace() != ErrorProperties.IncludeAttribute.NEVER) {
                errors.add("spring.web.error.include-stacktrace deve ser 'never' em produção.");
            }
            if (errorProperties.isIncludeException()) {
                errors.add("spring.web.error.include-exception deve ser 'false' em produção.");
            }
        } catch (BindException e) {
            errors.add("spring.web.error possui configuração inválida em produção.");
        }

        if (bindBoolean(binder, "springdoc.api-docs.enabled", false, errors)) {
            errors.add("springdoc.api-docs.enabled deve ser 'false' em produção.");
        }
        if (bindBoolean(binder, "springdoc.swagger-ui.enabled", false, errors)) {
            errors.add("springdoc.swagger-ui.enabled deve ser 'false' em produção.");
        }
        if (bindBoolean(binder, "spring.jpa.show-sql", false, errors)) {
            errors.add("spring.jpa.show-sql deve ser 'false' em produção.");
        }

        String ddlAuto = bindOptionalString(binder, "spring.jpa.hibernate.ddl-auto");
        if (!"validate".equalsIgnoreCase(trimOrEmpty(ddlAuto))) {
            errors.add("spring.jpa.hibernate.ddl-auto deve ser 'validate' em produção.");
        }

        String serverPort = bindOptionalString(binder, "server.port");
        String managementPort = bindOptionalString(binder, "management.server.port");
        if (isPresent(serverPort) && isPresent(managementPort)
                && serverPort.trim().equals(managementPort.trim())) {
            errors.add("management.server.port deve ser distinta de server.port em produção.");
        }

        String corsOrigins = resolveOptional(env, "rewit.security.cors.allowed-origins");
        if (!isPresent(corsOrigins)) {
            corsOrigins = resolveOptional(env, "CORS_ALLOWED_ORIGINS");
        }
        if (isPresent(corsOrigins)) {
            validateCorsAllowedOrigins(corsOrigins, errors);
        }

        String forwardStrategy = bindOptionalString(binder, "server.forward-headers-strategy");
        if ("native".equalsIgnoreCase(trimOrEmpty(forwardStrategy))) {
            String internalProxies = bindOptionalString(binder, "server.tomcat.remoteip.internal-proxies");
            if (!isPresent(internalProxies) || WILDCARD_PROXY_PATTERNS.contains(internalProxies.trim())) {
                errors.add("server.tomcat.remoteip.internal-proxies (SERVER_INTERNAL_PROXIES) deve definir proxies confiáveis explícitos sem wildcard irrestrito.");
            }
        }

        validateHealthProbesAndActuatorHardening(env, binder, errors);
        validateRateLimitProductionPolicy(env, binder, errors);
    }

    private static void validateRateLimitProductionPolicy(
            ConfigurableEnvironment env,
            Binder binder,
            List<String> errors
    ) {
        // R304-01: rate limiting é obrigatório em produção (enabled=true) e failure-mode=ALLOW é proibido
        try {
            BindResult<Boolean> boundEnabled = binder.bind("rewit.rate-limit.enabled", Boolean.class);
            if (boundEnabled.isBound()) {
                if (!Boolean.TRUE.equals(boundEnabled.get())) {
                    errors.add("rewit.rate-limit.enabled (RATE_LIMIT_ENABLED) deve ser 'true' em produção.");
                }
            } else {
                String rawEnvEnabled = resolveOptional(env, "RATE_LIMIT_ENABLED");
                if (isPresent(rawEnvEnabled) && !"true".equalsIgnoreCase(rawEnvEnabled.trim())) {
                    errors.add("rewit.rate-limit.enabled (RATE_LIMIT_ENABLED) deve ser 'true' em produção.");
                }
            }
        } catch (BindException e) {
            errors.add("rewit.rate-limit.enabled (RATE_LIMIT_ENABLED) possui valor booleano inválido em produção.");
        }

        try {
            BindResult<RateLimitProperties.Backend> boundBackend =
                    binder.bind("rewit.rate-limit.backend", RateLimitProperties.Backend.class);
            if (boundBackend.isBound()) {
                RateLimitBackendFailureMode mode = boundBackend.get().getFailureMode();
                if (mode == null) {
                    errors.add("rewit.rate-limit.backend.failure-mode (RATE_LIMIT_BACKEND_FAILURE_MODE) não pode ser nulo em produção.");
                } else if (mode == RateLimitBackendFailureMode.ALLOW) {
                    errors.add("rewit.rate-limit.backend.failure-mode (RATE_LIMIT_BACKEND_FAILURE_MODE) não pode ser 'ALLOW' em produção (use LOCAL_FALLBACK ou DENY).");
                }
            } else {
                String rawEnvMode = resolveOptional(env, "RATE_LIMIT_BACKEND_FAILURE_MODE");
                if (isPresent(rawEnvMode)) {
                    try {
                        RateLimitBackendFailureMode parsed = RateLimitBackendFailureMode.valueOf(
                                rawEnvMode.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
                        if (parsed == RateLimitBackendFailureMode.ALLOW) {
                            errors.add("rewit.rate-limit.backend.failure-mode (RATE_LIMIT_BACKEND_FAILURE_MODE) não pode ser 'ALLOW' em produção (use LOCAL_FALLBACK ou DENY).");
                        }
                    } catch (IllegalArgumentException invalid) {
                        errors.add("rewit.rate-limit.backend.failure-mode (RATE_LIMIT_BACKEND_FAILURE_MODE) possui valor inválido em produção (use LOCAL_FALLBACK ou DENY).");
                    }
                }
            }
        } catch (BindException e) {
            errors.add("rewit.rate-limit.backend.failure-mode (RATE_LIMIT_BACKEND_FAILURE_MODE) possui valor inválido em produção (use LOCAL_FALLBACK ou DENY).");
        }
    }

    private static void validateHealthProbesAndActuatorHardening(
            ConfigurableEnvironment env,
            Binder binder,
            List<String> errors
    ) {
        // R304-R1-02: garante que o endpoint health esteja efetivamente acessível para probes (READ_ONLY ou UNRESTRICTED),
        // respeitando max-permitted, access.default, enabled-by-default, health.access e health.enabled.
        validateHealthEndpointEffectiveAccess(env, binder, errors);

        // R304-02 & R304-R1-02: allowlist estrita de endpoints Actuator e integridade dos caminhos base/path-mapping
        try {
            WebEndpointProperties webEndpointProps = binder
                    .bind("management.endpoints.web", WebEndpointProperties.class)
                    .orElseGet(WebEndpointProperties::new);
            Set<String> included = webEndpointProps.getExposure().getInclude();
            if (included != null) {
                for (String endpoint : included) {
                    if (!isPresent(endpoint)) {
                        continue;
                    }
                    String normalized = endpoint.trim().toLowerCase(Locale.ROOT);
                    if (!ALLOWED_ACTUATOR_ENDPOINTS.contains(normalized)) {
                        errors.add("management.endpoints.web.exposure.include permite exclusivamente health, info e prometheus em produção.");
                        break;
                    }
                }
            }
            Set<String> excluded = webEndpointProps.getExposure().getExclude();
            if (excluded != null) {
                for (String endpoint : excluded) {
                    if (!isPresent(endpoint)) {
                        continue;
                    }
                    String normalized = endpoint.trim().toLowerCase(Locale.ROOT);
                    if ("health".equals(normalized) || "*".equals(normalized)) {
                        errors.add("management.endpoints.web.exposure.exclude não pode remover 'health' em produção.");
                        break;
                    }
                }
            }
            String basePath = webEndpointProps.getBasePath();
            if (basePath != null && !"/actuator".equals(basePath.trim())) {
                errors.add("management.endpoints.web.base-path deve permanecer '/actuator' em produção para preservar os caminhos de probes.");
            }
            Map<String, String> pathMapping = webEndpointProps.getPathMapping();
            if (pathMapping != null) {
                for (Map.Entry<String, String> entry : pathMapping.entrySet()) {
                    if (entry.getKey() != null && "health".equalsIgnoreCase(entry.getKey().trim())) {
                        String mapped = entry.getValue() == null ? "" : entry.getValue().trim();
                        if (!mapped.isEmpty() && !"health".equals(mapped) && !"/health".equals(mapped)) {
                            errors.add("management.endpoints.web.path-mapping não pode deslocar o endpoint 'health' em produção.");
                        }
                    }
                }
            }
        } catch (BindException e) {
            errors.add("management.endpoints.web possui configuração inválida em produção.");
        }

        String managementServerBasePath = bindOptionalString(binder, "management.server.base-path");
        if (isPresent(managementServerBasePath) && !"/".equals(managementServerBasePath.trim())) {
            errors.add("management.server.base-path não pode deslocar os endpoints de probes em produção.");
        }

        if (!bindBoolean(binder, "management.endpoint.health.probes.enabled", true, errors)) {
            errors.add("management.endpoint.health.probes.enabled deve ser 'true' em produção.");
        }
        // R304-R1-02: /livez e /readyz na porta principal são obrigatórios no contrato de produção
        if (!bindBoolean(binder, "management.endpoint.health.probes.add-additional-paths", false, errors)) {
            errors.add("management.endpoint.health.probes.add-additional-paths deve ser 'true' em produção para disponibilizar /livez e /readyz.");
        }
        if (!bindBoolean(binder, "management.health.defaults.enabled", true, errors)) {
            errors.add("management.health.defaults.enabled deve ser 'true' em produção.");
        }
        if (!bindBoolean(binder, "management.health.livenessstate.enabled", false, errors)) {
            errors.add("management.health.livenessstate.enabled deve ser 'true' em produção.");
        }
        if (!bindBoolean(binder, "management.health.readinessstate.enabled", false, errors)) {
            errors.add("management.health.readinessstate.enabled deve ser 'true' em produção.");
        }
        if (!bindBoolean(binder, "management.health.db.enabled", true, errors)) {
            errors.add("management.health.db.enabled deve ser 'true' em produção.");
        }
        if (!bindBoolean(binder, "management.health.redis.enabled", true, errors)) {
            errors.add("management.health.redis.enabled deve ser 'true' em produção.");
        }
        if (!bindBoolean(binder, "management.health.object-storage.enabled", true, errors)) {
            errors.add("management.health.object-storage.enabled deve ser 'true' em produção.");
        }

        try {
            HealthEndpointProperties healthProps = binder
                    .bind("management.endpoint.health", HealthEndpointProperties.class)
                    .orElseGet(HealthEndpointProperties::new);

            if (healthProps.getShowDetails() != null && healthProps.getShowDetails() != Show.NEVER) {
                errors.add("management.endpoint.health.show-details deve ser 'never' em produção.");
            }
            if (healthProps.getShowComponents() != null && healthProps.getShowComponents() != Show.NEVER) {
                errors.add("management.endpoint.health.show-components deve ser 'never' em produção.");
            }

            // R304-R1-01: valida status.order e status.http-mapping globais usando os agregadores reais do Spring Boot 4.1.1
            List<String> globalOrder = healthProps.getStatus().getOrder();
            StatusAggregator globalAggregator = buildEffectiveStatusAggregator(globalOrder, null);
            HttpCodeStatusMapper globalHttpMapper = buildEffectiveHttpCodeStatusMapper(healthProps.getStatus().getHttpMapping(), null);
            validateStatusOrderAndHttpMapping(
                    globalOrder,
                    healthProps.getStatus().getHttpMapping(),
                    globalAggregator,
                    globalHttpMapper,
                    "management.endpoint.health.status",
                    false,
                    errors
            );

            Map<String, HealthEndpointProperties.Group> groups = healthProps.getGroup();
            if (groups != null) {
                for (Map.Entry<String, HealthEndpointProperties.Group> entry : groups.entrySet()) {
                    HealthEndpointProperties.Group group = entry.getValue();
                    if (group == null) {
                        continue;
                    }
                    if (group.getShowDetails() != null && group.getShowDetails() != Show.NEVER) {
                        errors.add("management.endpoint.health.group." + entry.getKey()
                                + ".show-details deve ser 'never' em produção.");
                    }
                    if (group.getShowComponents() != null && group.getShowComponents() != Show.NEVER) {
                        errors.add("management.endpoint.health.group." + entry.getKey()
                                + ".show-components deve ser 'never' em produção.");
                    }
                }

                // R304-02 & R304-R1-01: liveness exige inclusão exclusiva de livenessState, sem exclusões e com /livez preservado
                HealthEndpointProperties.Group liveness = groups.get("liveness");
                if (liveness == null || liveness.getInclude() == null || liveness.getInclude().isEmpty()) {
                    errors.add("management.endpoint.health.group.liveness.include deve declarar exclusivamente 'livenessState' em produção.");
                } else {
                    Set<String> normalizedLiveness = normalizeMembers(liveness.getInclude());
                    if (!normalizedLiveness.equals(REQUIRED_LIVENESS_MEMBERS)) {
                        errors.add("management.endpoint.health.group.liveness.include deve conter exclusivamente 'livenessState' (sem '*', db, redis, objectStorage ou outros contributors).");
                    }
                }
                if (liveness != null) {
                    if (liveness.getExclude() != null && !liveness.getExclude().isEmpty()) {
                        Set<String> normalizedLivenessExclude = normalizeMembers(liveness.getExclude());
                        if (normalizedLivenessExclude.contains("*") || normalizedLivenessExclude.contains("livenessstate")) {
                            errors.add("management.endpoint.health.group.liveness.exclude não pode excluir 'livenessState' em produção.");
                        }
                    }
                    if (isPresent(liveness.getAdditionalPath())
                            && !"server:/livez".equals(liveness.getAdditionalPath().trim())) {
                        errors.add("management.endpoint.health.group.liveness.additional-path não pode deslocar 'server:/livez' em produção.");
                    }
                    StatusAggregator livenessAggregator = buildEffectiveStatusAggregator(liveness.getStatus().getOrder(), globalAggregator);
                    HttpCodeStatusMapper livenessHttpMapper = buildEffectiveHttpCodeStatusMapper(liveness.getStatus().getHttpMapping(), globalHttpMapper);
                    validateStatusOrderAndHttpMapping(
                            liveness.getStatus().getOrder(),
                            liveness.getStatus().getHttpMapping(),
                            livenessAggregator,
                            livenessHttpMapper,
                            "management.endpoint.health.group.liveness.status",
                            false,
                            errors
                    );
                }

                // R304-02 & R304-R1-01: readiness exige exatamente readinessState, db, redis e objectStorage, sem exclusões,
                // com /readyz preservado e com DOWN, OUT_OF_SERVICE e UNKNOWN sempre resultando em HTTP não saudável (>= 400).
                HealthEndpointProperties.Group readiness = groups.get("readiness");
                if (readiness == null || readiness.getInclude() == null || readiness.getInclude().isEmpty()) {
                    errors.add("management.endpoint.health.group.readiness.include deve declarar exatamente readinessState, db, redis e objectStorage em produção.");
                } else {
                    Set<String> normalizedReadiness = normalizeMembers(readiness.getInclude());
                    if (!normalizedReadiness.equals(REQUIRED_READINESS_MEMBERS)) {
                        errors.add("management.endpoint.health.group.readiness.include deve conter exatamente readinessState, db, redis e objectStorage em produção.");
                    }
                }
                if (readiness != null) {
                    if (readiness.getExclude() != null && !readiness.getExclude().isEmpty()) {
                        Set<String> normalizedReadinessExclude = normalizeMembers(readiness.getExclude());
                        boolean removesRequired = normalizedReadinessExclude.contains("*");
                        if (!removesRequired) {
                            for (String excludedMember : normalizedReadinessExclude) {
                                if (REQUIRED_READINESS_MEMBERS.contains(excludedMember)) {
                                    removesRequired = true;
                                    break;
                                }
                            }
                        }
                        if (removesRequired) {
                            errors.add("management.endpoint.health.group.readiness.exclude não pode remover readinessState, db, redis ou objectStorage em produção.");
                        }
                    }
                    if (isPresent(readiness.getAdditionalPath())
                            && !"server:/readyz".equals(readiness.getAdditionalPath().trim())) {
                        errors.add("management.endpoint.health.group.readiness.additional-path não pode deslocar 'server:/readyz' em produção.");
                    }
                    StatusAggregator readinessAggregator = buildEffectiveStatusAggregator(readiness.getStatus().getOrder(), globalAggregator);
                    HttpCodeStatusMapper readinessHttpMapper = buildEffectiveHttpCodeStatusMapper(readiness.getStatus().getHttpMapping(), globalHttpMapper);
                    validateStatusOrderAndHttpMapping(
                            readiness.getStatus().getOrder(),
                            readiness.getStatus().getHttpMapping(),
                            readinessAggregator,
                            readinessHttpMapper,
                            "management.endpoint.health.group.readiness.status",
                            true,
                            errors
                    );
                }
            } else {
                errors.add("management.endpoint.health.group.liveness.include deve declarar exclusivamente 'livenessState' em produção.");
                errors.add("management.endpoint.health.group.readiness.include deve declarar exatamente readinessState, db, redis e objectStorage em produção.");
            }
        } catch (BindException e) {
            errors.add("management.endpoint.health possui configuração inválida em produção.");
        }
    }

    private static void validateHealthEndpointEffectiveAccess(
            ConfigurableEnvironment env,
            Binder binder,
            List<String> errors
    ) {
        // 1. Consulta o PropertiesEndpointAccessResolver real do Spring Boot 4.1.1 com ApplicationConversionService
        //    (mesmo serviço de conversão leniente usado pelo SpringApplication em runtime para read_only/read-only/none)
        try {
            PropertySourcesPropertyResolver accessPropertyResolver =
                    new PropertySourcesPropertyResolver(env.getPropertySources());
            accessPropertyResolver.setConversionService(new ApplicationConversionService());
            PropertiesEndpointAccessResolver resolver = new PropertiesEndpointAccessResolver(accessPropertyResolver);
            Access resolved = resolver.accessFor(EndpointId.of("health"), Access.UNRESTRICTED);
            if (resolved == Access.NONE) {
                errors.add("O endpoint 'health' está desabilitado ou com acesso NONE em produção (verifique management.endpoint.health.access, management.endpoints.access.default, management.endpoints.access.max-permitted e enabled-by-default).");
                return;
            }
        } catch (RuntimeException e) {
            errors.add("Configuração de acesso do endpoint 'health' é inválida ou mutuamente exclusiva em produção.");
            return;
        }

        // 2. Validação complementar via Binder (cobre sobrescritas camelCase / relaxed binding)
        try {
            Access maxPermitted = binder.bind("management.endpoints.access.max-permitted", Access.class)
                    .orElse(Access.UNRESTRICTED);
            BindResult<Access> defaultAccess = binder.bind("management.endpoints.access.default", Access.class);
            BindResult<Boolean> enabledByDefault = binder.bind("management.endpoints.enabled-by-default", Boolean.class);
            BindResult<Access> healthAccess = binder.bind("management.endpoint.health.access", Access.class);
            BindResult<Boolean> healthEnabled = binder.bind("management.endpoint.health.enabled", Boolean.class);

            if (defaultAccess.isBound() && enabledByDefault.isBound()) {
                errors.add("management.endpoints.access.default e management.endpoints.enabled-by-default são mutuamente exclusivos em produção.");
                return;
            }
            if (healthAccess.isBound() && healthEnabled.isBound()) {
                errors.add("management.endpoint.health.access e management.endpoint.health.enabled são mutuamente exclusivos em produção.");
                return;
            }

            Access effectiveDefault = Access.UNRESTRICTED;
            if (defaultAccess.isBound()) {
                effectiveDefault = defaultAccess.get();
            } else if (enabledByDefault.isBound()) {
                effectiveDefault = Boolean.TRUE.equals(enabledByDefault.get()) ? Access.UNRESTRICTED : Access.NONE;
            }

            Access effectiveHealth;
            if (healthAccess.isBound()) {
                effectiveHealth = healthAccess.get();
            } else if (healthEnabled.isBound()) {
                effectiveHealth = Boolean.TRUE.equals(healthEnabled.get()) ? Access.UNRESTRICTED : Access.NONE;
            } else {
                effectiveHealth = effectiveDefault;
            }

            Access capped = effectiveHealth.cap(maxPermitted);
            if (capped == Access.NONE) {
                errors.add("O endpoint 'health' está desabilitado ou com acesso NONE em produção (verifique management.endpoint.health.access, management.endpoints.access.default, management.endpoints.access.max-permitted e enabled-by-default).");
            }
        } catch (BindException e) {
            errors.add("Configuração de acesso do endpoint 'health' contém valor inválido em produção.");
        }
    }

    private static StatusAggregator buildEffectiveStatusAggregator(List<String> order, StatusAggregator fallback) {
        if (order != null && !order.isEmpty()) {
            return StatusAggregator.of(order);
        }
        return fallback != null ? fallback : StatusAggregator.getDefault();
    }

    private static HttpCodeStatusMapper buildEffectiveHttpCodeStatusMapper(
            Map<String, Integer> httpMapping,
            HttpCodeStatusMapper fallback
    ) {
        if (httpMapping != null && !httpMapping.isEmpty()) {
            return HttpCodeStatusMapper.of(httpMapping);
        }
        return fallback != null ? fallback : HttpCodeStatusMapper.getDefault();
    }

    private static void validateStatusOrderAndHttpMapping(
            List<String> explicitOrder,
            Map<String, Integer> explicitHttpMapping,
            StatusAggregator effectiveAggregator,
            HttpCodeStatusMapper effectiveHttpMapper,
            String propertyPrefix,
            boolean strictUnknownUnhealthy,
            List<String> errors
    ) {
        // 1. Se uma ordem explícita foi fornecida, DOWN e OUT_OF_SERVICE precisam constar nela (não podem cair em UNKNOWN)
        if (explicitOrder != null && !explicitOrder.isEmpty()) {
            Set<String> normalizedOrder = new java.util.LinkedHashSet<>();
            for (String item : explicitOrder) {
                if (isPresent(item)) {
                    normalizedOrder.add(item.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
                }
            }
            if (!normalizedOrder.contains("DOWN") || !normalizedOrder.contains("OUT_OF_SERVICE")) {
                errors.add(propertyPrefix + ".order deve incluir obrigatoriamente 'DOWN' e 'OUT_OF_SERVICE' em produção.");
            }
            if (strictUnknownUnhealthy && !normalizedOrder.contains("UNKNOWN")) {
                errors.add(propertyPrefix + ".order deve incluir 'UNKNOWN' antes de 'UP' na readiness em produção.");
            }
        }

        // 2. Comprova via SimpleStatusAggregator real que DOWN e OUT_OF_SERVICE prevalecem sobre UP e UNKNOWN
        if (!Status.DOWN.equals(effectiveAggregator.getAggregateStatus(Set.of(Status.UP, Status.DOWN)))
                || !Status.DOWN.equals(effectiveAggregator.getAggregateStatus(Set.of(Status.UNKNOWN, Status.DOWN)))) {
            errors.add(propertyPrefix + ".order não pode atribuir prioridade igual ou superior de UP/UNKNOWN sobre DOWN em produção.");
        }
        if (!Status.OUT_OF_SERVICE.equals(effectiveAggregator.getAggregateStatus(Set.of(Status.UP, Status.OUT_OF_SERVICE)))
                || !Status.OUT_OF_SERVICE.equals(effectiveAggregator.getAggregateStatus(Set.of(Status.UNKNOWN, Status.OUT_OF_SERVICE)))) {
            errors.add(propertyPrefix + ".order não pode atribuir prioridade igual ou superior de UP/UNKNOWN sobre OUT_OF_SERVICE em produção.");
        }
        if (strictUnknownUnhealthy
                && !Status.UNKNOWN.equals(effectiveAggregator.getAggregateStatus(Set.of(Status.UP, Status.UNKNOWN)))) {
            errors.add(propertyPrefix + ".order deve priorizar UNKNOWN acima de UP na readiness para não mascarar dependência desconhecida como saudável.");
        }

        // 3. Comprova via SimpleHttpCodeStatusMapper real que estados não saudáveis nunca retornam HTTP 2xx/3xx
        if (explicitHttpMapping != null) {
            for (Map.Entry<String, Integer> entry : explicitHttpMapping.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String normalizedStatus = entry.getKey().trim().toUpperCase(Locale.ROOT).replace('-', '_');
                boolean mustBeUnhealthy = UNHEALTHY_STATUS_KEYS.contains(normalizedStatus)
                        || (strictUnknownUnhealthy && "UNKNOWN".equals(normalizedStatus));
                if (mustBeUnhealthy) {
                    Integer httpCode = entry.getValue();
                    if (httpCode == null || httpCode < 400 || httpCode > 599) {
                        errors.add(propertyPrefix + ".http-mapping não pode mapear '" + normalizedStatus
                                + "' para código HTTP de sucesso em produção (exige status não saudável >= 400).");
                    }
                }
            }
        }

        int downCode = effectiveHttpMapper.getStatusCode(Status.DOWN);
        if (downCode < 400 || downCode > 599) {
            errors.add(propertyPrefix + ".http-mapping efetivo deve mapear DOWN para código HTTP não saudável (>= 400).");
        }
        int oosCode = effectiveHttpMapper.getStatusCode(Status.OUT_OF_SERVICE);
        if (oosCode < 400 || oosCode > 599) {
            errors.add(propertyPrefix + ".http-mapping efetivo deve mapear OUT_OF_SERVICE para código HTTP não saudável (>= 400).");
        }
        if (strictUnknownUnhealthy) {
            int unknownCode = effectiveHttpMapper.getStatusCode(Status.UNKNOWN);
            if (unknownCode < 400 || unknownCode > 599) {
                errors.add(propertyPrefix + ".http-mapping efetivo na readiness deve mapear UNKNOWN para código HTTP não saudável (ex.: 503).");
            }
        }
    }

    private static Set<String> normalizeMembers(Set<String> members) {
        Set<String> normalized = new java.util.LinkedHashSet<>();
        for (String member : members) {
            if (isPresent(member)) {
                normalized.add(member.trim().toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    private static void validateCorsAllowedOrigins(String corsOrigins, List<String> errors) {
        for (String rawOrigin : corsOrigins.split(",")) {
            String origin = rawOrigin.trim();
            if (origin.isEmpty()) {
                continue;
            }
            if (origin.contains("*")) {
                errors.add("rewit.security.cors.allowed-origins (CORS_ALLOWED_ORIGINS) não pode usar wildcard '*' em produção.");
                return;
            }
            URI uri;
            try {
                uri = URI.create(origin);
            } catch (IllegalArgumentException e) {
                errors.add("rewit.security.cors.allowed-origins (CORS_ALLOWED_ORIGINS) contém origem inválida.");
                return;
            }
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                errors.add("rewit.security.cors.allowed-origins (CORS_ALLOWED_ORIGINS) deve conter somente origens HTTPS em produção.");
                return;
            }
            if (!isPresent(uri.getHost())
                    || uri.getRawUserInfo() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                errors.add("rewit.security.cors.allowed-origins (CORS_ALLOWED_ORIGINS) exige origens puras (https://host[:porta]) sem path, query, fragmento ou userinfo.");
                return;
            }
            if (!hasValidOptionalPort(uri)) {
                errors.add("rewit.security.cors.allowed-origins (CORS_ALLOWED_ORIGINS) contém porta inválida (use 1-65535 ou omita a porta).");
                return;
            }
        }
    }

    private static void validateDatabaseAndFlyway(ConfigurableEnvironment env, List<String> errors) {
        Binder binder = Binder.get(env);

        // Reutiliza integralmente a política canônica F2-DEPLOY-02 das 3 roles runtime
        errors.addAll(RuntimeDatabaseRolePolicy.validateEnvironment(env));

        String datasourceUrl = resolveRequired(env, "spring.datasource.url", "DATABASE_URL", errors);
        if (datasourceUrl != null) {
            PostgresProductionJdbcUrlValidator.validateProductionUrl(
                    datasourceUrl.trim(), "spring.datasource.url (DATABASE_URL)", errors);
        }

        // F2-DEPLOY-03.5.2A: Flyway no runtime da API é estritamente proibido em produção.
        // Exige spring.flyway.enabled=false explícito (cobrindo YAML, env var, CLI e SPRING_APPLICATION_JSON).
        try {
            BindResult<Boolean> boundEnabled = binder.bind("spring.flyway.enabled", Boolean.class);
            if (!boundEnabled.isBound() || !Boolean.FALSE.equals(boundEnabled.get())) {
                errors.add("spring.flyway.enabled (SPRING_FLYWAY_ENABLED) deve ser 'false' em produção: "
                        + "migrations Flyway executam exclusivamente no runner pré-deploy isolado.");
            }
        } catch (BindException e) {
            errors.add("spring.flyway.enabled (SPRING_FLYWAY_ENABLED) possui valor inválido em produção (deve ser 'false').");
        }

        // Proíbe presença de credenciais migrator (FLYWAY_DB_* / POSTGRES_*) e overrides FLYWAY_* na API de produção
        Set<String> forbiddenMigratorVars = new TreeSet<>();
        for (String varName : FORBIDDEN_PROD_MIGRATOR_ENV_VARS) {
            if (isPresent(resolveOptional(env, varName))) {
                forbiddenMigratorVars.add(varName);
            }
        }
        for (PropertySource<?> propertySource : env.getPropertySources()) {
            if (!(propertySource instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String propName : enumerable.getPropertyNames()) {
                if (propName == null) {
                    continue;
                }
                String upper = propName.trim().toUpperCase(Locale.ROOT);
                if (FORBIDDEN_PROD_MIGRATOR_ENV_VARS.contains(upper)
                        || upper.startsWith("FLYWAY_")) {
                    Object rawVal = enumerable.getProperty(propName);
                    if (rawVal != null && isPresent(String.valueOf(rawVal))) {
                        forbiddenMigratorVars.add(propName);
                    }
                }
            }
        }
        for (String forbiddenVar : forbiddenMigratorVars) {
            errors.add("Credencial/variável migrator proibida no processo da API em produção: " + forbiddenVar
                    + " (remova da API e injete somente no runner pré-deploy).");
        }

        // Proíbe qualquer propriedade spring.flyway.* diferente de spring.flyway.enabled=false
        // (cobre relaxed binding via SPRING_FLYWAY_*, CLI, camelCase e SPRING_APPLICATION_JSON)
        Set<String> forbiddenFlywayKeys = new LinkedHashSet<>();
        try {
            Map<String, Object> boundFlyway = binder
                    .bind("spring.flyway", Bindable.mapOf(String.class, Object.class))
                    .orElse(Map.of());
            collectNonEmptyNestedKeys("spring.flyway", boundFlyway, forbiddenFlywayKeys);
        } catch (BindException e) {
            errors.add("spring.flyway contém propriedades incompatíveis ou inválidas no runtime de produção.");
        }
        for (String key : forbiddenFlywayKeys) {
            String normalizedKey = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
            if ("spring.flyway.enabled".equals(normalizedKey)) {
                continue;
            }
            errors.add("Propriedade Flyway proibida no runtime da API em produção: " + key
                    + " (apenas spring.flyway.enabled=false é permitido).");
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectNonEmptyNestedKeys(String prefix, Map<?, ?> map, Set<String> collector) {
        if (map == null || map.isEmpty()) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String fullKey = prefix + "." + entry.getKey();
            Object val = entry.getValue();
            if (val instanceof Map<?, ?> nested) {
                collectNonEmptyNestedKeys(fullKey, nested, collector);
            } else if (val instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null && isPresent(String.valueOf(item))) {
                        collector.add(fullKey);
                        break;
                    }
                }
            } else if (isPresent(String.valueOf(val))) {
                collector.add(fullKey);
            }
        }
    }

    private static void validateCryptographicSecrets(ConfigurableEnvironment env, List<String> errors) {
        Map<String, String> allSecrets = new LinkedHashMap<>();

        validateRequiredSecret(env, "rewit.security.jwt-secret", "JWT_SECRET", allSecrets, errors);
        validateRequiredSecret(env, "rewit.security.password-pepper", "PASSWORD_PEPPER_SECRET", allSecrets, errors);
        String pepperKeyId = validateRequiredKeyId(env, "rewit.security.password-pepper-key-id", "PASSWORD_PEPPER_KEY_ID", errors);
        validateOptionalRotationPair(
                env,
                "rewit.security.password-pepper-previous", "PASSWORD_PEPPER_SECRET_PREVIOUS",
                "rewit.security.password-pepper-previous-key-id", "PASSWORD_PEPPER_PREVIOUS_KEY_ID",
                pepperKeyId,
                allSecrets,
                errors
        );

        validateRequiredSecret(env, "rewit.security.refresh-token-secret", "REFRESH_TOKEN_SECRET", allSecrets, errors);
        validateOptionalSecret(env, "rewit.security.refresh-token-secret-previous", "REFRESH_TOKEN_SECRET_PREVIOUS", allSecrets, errors);

        validateRequiredSecret(env, "rewit.security.moderation-audit-secret", "MODERATION_AUDIT_SECRET", allSecrets, errors);
        String modAuditKeyId = validateRequiredKeyId(env, "rewit.security.moderation-audit-key-id", "MODERATION_AUDIT_KEY_ID", errors);
        validateOptionalRotationPair(
                env,
                "rewit.security.moderation-audit-secret-previous", "MODERATION_AUDIT_SECRET_PREVIOUS",
                "rewit.security.moderation-audit-previous-key-id", "MODERATION_AUDIT_PREVIOUS_KEY_ID",
                modAuditKeyId,
                allSecrets,
                errors
        );

        // R304-01: em produção o rate limiting é sempre obrigatório, logo RATE_LIMIT_KEY_SECRET é sempre exigido
        validateRequiredSecret(env, "rewit.rate-limit.key-secret", "RATE_LIMIT_KEY_SECRET", allSecrets, errors);

        // F2-DEPLOY-03.3 §6.3: ACCOUNT_EMAIL_RESERVATION_SECRET obrigatório quando ACCOUNT_PURGE_ENABLED=true
        boolean accountPurgeEnabled = isTrue(env, "rewit.account.purge.enabled", true);
        if (accountPurgeEnabled) {
            validateRequiredSecret(env, "rewit.account.email-reservation-secret", "ACCOUNT_EMAIL_RESERVATION_SECRET", allSecrets, errors);
        } else {
            validateOptionalSecret(env, "rewit.account.email-reservation-secret", "ACCOUNT_EMAIL_RESERVATION_SECRET", allSecrets, errors);
        }

        if (allSecrets.size() >= 2) {
            try {
                SecuritySecretValidator.validateDistinct(allSecrets);
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
            }
        }

        Map<String, String> dbPasswords = new LinkedHashMap<>();
        putIfPresent(dbPasswords, "APP_DB_PASSWORD", resolveOptional(env, "spring.datasource.password"));
        putIfPresent(dbPasswords, "MEDIA_DB_PASSWORD", resolveOptional(env, "rewit.datasource.media.password"));
        putIfPresent(dbPasswords, "MOD_DB_PASSWORD", resolveOptional(env, "rewit.datasource.moderation.password"));

        for (Map.Entry<String, String> secretEntry : allSecrets.entrySet()) {
            byte[] secretBytes;
            try {
                secretBytes = SecuritySecretValidator.decodeSecret(secretEntry.getKey(), secretEntry.getValue());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            for (Map.Entry<String, String> dbEntry : dbPasswords.entrySet()) {
                String dbPass = dbEntry.getValue();
                if (secretEntry.getValue().trim().equals(dbPass.trim())
                        || MessageDigest.isEqual(secretBytes, dbPass.trim().getBytes(StandardCharsets.UTF_8))) {
                    errors.add("Reutilização de credencial proibida: '" + secretEntry.getKey()
                            + "' não pode coincidir com '" + dbEntry.getKey() + "'.");
                }
            }
        }
    }

    private static void validateRedis(ConfigurableEnvironment env, List<String> errors) {
        Binder binder = Binder.get(env);

        // R1-01 & R1-02: validar topologias efetivamente vinculadas via Binder/DataRedisProperties
        // (cobre relaxed binding indexado como SPRING_DATA_REDIS_CLUSTER_NODES_0,
        // SPRING_DATA_REDIS_SENTINEL_NODES_0, SPRING_DATA_REDIS_MASTERREPLICA_NODES_0, CLI e JSON).
        validateForbiddenRedisTopologies(binder, errors);

        Boolean sslEnabledExplicit = null;
        boolean sslBindError = false;
        try {
            BindResult<Boolean> boundSsl = binder.bind("spring.data.redis.ssl.enabled", Boolean.class);
            if (boundSsl.isBound()) {
                sslEnabledExplicit = boundSsl.get();
            }
        } catch (BindException e) {
            sslBindError = true;
            errors.add("spring.data.redis.ssl.enabled (REDIS_SSL_ENABLED) possui valor inválido em produção: TLS é obrigatório para Redis.");
        }
        if (!sslBindError && Boolean.FALSE.equals(sslEnabledExplicit)) {
            errors.add("spring.data.redis.ssl.enabled (REDIS_SSL_ENABLED) não pode ser 'false' em produção: TLS é obrigatório para Redis.");
        }

        String redisUrl = bindOptionalString(binder, "spring.data.redis.url");
        if (isPresent(redisUrl)) {
            validateRedisUrl(redisUrl.trim(), binder, errors);
            verifyBoundRedisPropertiesCoherence(binder, true, errors);
            return;
        }

        String host = bindRequiredString(binder, "spring.data.redis.host", "REDIS_HOST", errors);
        if (host != null && isLocalOrDevHost(host)) {
            errors.add("spring.data.redis.host (REDIS_HOST) não pode apontar para localhost/desenvolvimento em produção.");
        }

        String portStr = bindRequiredString(binder, "spring.data.redis.port", "REDIS_PORT", errors);
        if (portStr != null) {
            if (parseValidPort(portStr) < 1) {
                errors.add("spring.data.redis.port (REDIS_PORT) deve ser uma porta TCP válida (1-65535).");
            }
        }

        String password = bindRequiredString(binder, "spring.data.redis.password", "REDIS_PASSWORD", errors);
        if (password != null && isInsecurePlaceholder(password)) {
            errors.add("spring.data.redis.password (REDIS_PASSWORD) usa valor de exemplo proibido.");
        }

        if (!sslBindError && !Boolean.TRUE.equals(sslEnabledExplicit)) {
            errors.add("spring.data.redis.ssl.enabled (REDIS_SSL_ENABLED) deve ser 'true' em produção: TLS é obrigatório para Redis.");
        }

        verifyBoundRedisPropertiesCoherence(binder, false, errors);
    }

    private static void validateForbiddenRedisTopologies(Binder binder, List<String> errors) {
        try {
            DataRedisProperties.Cluster cluster = binder
                    .bind("spring.data.redis.cluster", DataRedisProperties.Cluster.class)
                    .orElse(null);
            if (cluster != null && (hasNonBlankItems(cluster.getNodes()) || cluster.getMaxRedirects() != null)) {
                errors.add("spring.data.redis.cluster.nodes não é permitido em produção nesta etapa (use Redis standalone com TLS).");
            }
        } catch (BindException e) {
            errors.add("spring.data.redis.cluster.* contém configuração inválida ou não permitida em produção.");
        }

        try {
            DataRedisProperties.Sentinel sentinel = binder
                    .bind("spring.data.redis.sentinel", DataRedisProperties.Sentinel.class)
                    .orElse(null);
            if (sentinel != null && (hasNonBlankItems(sentinel.getNodes())
                    || isPresent(sentinel.getMaster())
                    || isPresent(sentinel.getUsername())
                    || isPresent(sentinel.getPassword()))) {
                errors.add("spring.data.redis.sentinel.* não é permitido em produção nesta etapa (use Redis standalone com TLS).");
            }
        } catch (BindException e) {
            errors.add("spring.data.redis.sentinel.* contém configuração inválida ou não permitida em produção.");
        }

        try {
            DataRedisProperties.Masterreplica masterreplica = binder
                    .bind("spring.data.redis.masterreplica", DataRedisProperties.Masterreplica.class)
                    .orElse(null);
            if (masterreplica != null && hasNonBlankItems(masterreplica.getNodes())) {
                errors.add("spring.data.redis.masterreplica.nodes não é permitido em produção nesta etapa (use Redis standalone com TLS).");
            }
        } catch (BindException e) {
            errors.add("spring.data.redis.masterreplica.* contém configuração inválida ou não permitida em produção.");
        }
    }

    private static void verifyBoundRedisPropertiesCoherence(Binder binder, boolean usingUrl, List<String> errors) {
        if (!errors.isEmpty()) {
            return;
        }
        try {
            DataRedisProperties bound = binder.bind("spring.data.redis", DataRedisProperties.class).orElse(null);
            if (bound == null) {
                return;
            }
            if (bound.getCluster() != null && hasNonBlankItems(bound.getCluster().getNodes())) {
                errors.add("spring.data.redis.cluster.nodes não é permitido em produção nesta etapa (use Redis standalone com TLS).");
            }
            if (bound.getSentinel() != null
                    && (hasNonBlankItems(bound.getSentinel().getNodes()) || isPresent(bound.getSentinel().getMaster()))) {
                errors.add("spring.data.redis.sentinel.* não é permitido em produção nesta etapa (use Redis standalone com TLS).");
            }
            if (bound.getMasterreplica() != null && hasNonBlankItems(bound.getMasterreplica().getNodes())) {
                errors.add("spring.data.redis.masterreplica.nodes não é permitido em produção nesta etapa (use Redis standalone com TLS).");
            }
            if (!usingUrl) {
                if (isLocalOrDevHost(bound.getHost())) {
                    errors.add("spring.data.redis.host (REDIS_HOST) não pode apontar para localhost/desenvolvimento em produção.");
                }
                if (bound.getPort() < 1 || bound.getPort() > 65535) {
                    errors.add("spring.data.redis.port (REDIS_PORT) deve ser uma porta TCP válida (1-65535).");
                }
                if (!bound.getSsl().isEnabled()) {
                    errors.add("spring.data.redis.ssl.enabled (REDIS_SSL_ENABLED) deve ser 'true' em produção: TLS é obrigatório para Redis.");
                }
            }
        } catch (BindException e) {
            if (!usingUrl) {
                errors.add("spring.data.redis contém propriedades inválidas em produção.");
            }
        }
    }

    private static void validateRedisUrl(String redisUrl, Binder binder, List<String> errors) {
        URI uri;
        try {
            uri = URI.create(redisUrl);
        } catch (IllegalArgumentException e) {
            errors.add("spring.data.redis.url possui formato inválido.");
            return;
        }
        if (!"rediss".equalsIgnoreCase(uri.getScheme())) {
            errors.add("spring.data.redis.url exige esquema 'rediss://' (TLS obrigatório) em produção.");
        }
        if (!isPresent(uri.getHost()) || isLocalOrDevHost(uri.getHost())) {
            errors.add("spring.data.redis.url exige host não local explícito em produção.");
        }
        if (!hasValidRequiredPort(uri)) {
            errors.add("spring.data.redis.url exige porta explícita válida (1-65535) em produção.");
        }

        // AUDIT-04A: extrai e valida senha embutida na URL ou fallback REDIS_PASSWORD contra placeholders
        String rawUserInfo = uri.getRawUserInfo();
        String fallbackPassword = bindOptionalString(binder, "spring.data.redis.password");
        if (rawUserInfo != null) {
            String decodedUserInfo;
            try {
                decodedUserInfo = URLDecoder.decode(rawUserInfo, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                errors.add("spring.data.redis.url possui userinfo com encoding inválido.");
                return;
            }
            int colonIdx = decodedUserInfo.indexOf(':');
            if (colonIdx < 0) {
                errors.add("spring.data.redis.url possui userinfo malformado: use 'rediss://:senha@host:porta' ou 'rediss://usuario:senha@host:porta'.");
            } else {
                String urlPassword = decodedUserInfo.substring(colonIdx + 1);
                if (urlPassword.isBlank()) {
                    errors.add("spring.data.redis.url exige senha não vazia em produção.");
                } else if (isInsecurePlaceholder(urlPassword)) {
                    errors.add("spring.data.redis.url utiliza senha de exemplo proibida em produção.");
                }
            }
        } else {
            if (!isPresent(fallbackPassword)) {
                errors.add("Redis exige autenticação explícita (senha na URL rediss:// ou REDIS_PASSWORD) em produção.");
            } else if (isInsecurePlaceholder(fallbackPassword)) {
                errors.add("spring.data.redis.password (REDIS_PASSWORD) usa valor de exemplo proibido.");
            }
        }

        // AUDIT-04B: rejeitar conflito entre spring.data.redis.url e host/port individuais quando ambos definidos
        String configuredHost = bindOptionalString(binder, "spring.data.redis.host");
        if (isPresent(configuredHost) && isPresent(uri.getHost())
                && !stripBrackets(configuredHost).equalsIgnoreCase(stripBrackets(uri.getHost()))) {
            errors.add("Configuração conflitante de Redis: spring.data.redis.url diverge de spring.data.redis.host.");
        }
        String configuredPort = bindOptionalString(binder, "spring.data.redis.port");
        if (isPresent(configuredPort) && uri.getPort() > 0
                && !configuredPort.trim().equals(String.valueOf(uri.getPort()))) {
            errors.add("Configuração conflitante de Redis: spring.data.redis.url diverge de spring.data.redis.port.");
        }
    }

    private static void validateObjectStorage(ConfigurableEnvironment env, List<String> errors) {
        String endpoint = resolveRequired(env, "rewit.minio.endpoint", "OBJECT_STORAGE_ENDPOINT", errors);
        if (endpoint != null) {
            try {
                URI uri = URI.create(endpoint.trim());
                if (!"https".equalsIgnoreCase(uri.getScheme())) {
                    errors.add("rewit.minio.endpoint (OBJECT_STORAGE_ENDPOINT) exige HTTPS em produção.");
                }
                if (!isPresent(uri.getHost()) || isLocalOrDevHost(uri.getHost())) {
                    errors.add("rewit.minio.endpoint (OBJECT_STORAGE_ENDPOINT) não pode apontar para localhost, loopback ou endereço de desenvolvimento em produção.");
                }
                if (!hasValidOptionalPort(uri)) {
                    errors.add("rewit.minio.endpoint (OBJECT_STORAGE_ENDPOINT) possui porta inválida (use 1-65535 ou omita a porta).");
                }
                if (uri.getRawUserInfo() != null) {
                    errors.add("rewit.minio.endpoint (OBJECT_STORAGE_ENDPOINT) não pode conter userinfo.");
                }
                if (uri.getRawQuery() != null) {
                    errors.add("rewit.minio.endpoint (OBJECT_STORAGE_ENDPOINT) não pode conter query string.");
                }
                if (uri.getRawFragment() != null) {
                    errors.add("rewit.minio.endpoint (OBJECT_STORAGE_ENDPOINT) não pode conter fragmento.");
                }
            } catch (IllegalArgumentException e) {
                errors.add("rewit.minio.endpoint (OBJECT_STORAGE_ENDPOINT) possui formato de URL inválido.");
            }
        }

        String accessKey = resolveRequired(env, "rewit.minio.access-key", "OBJECT_STORAGE_ACCESS_KEY", errors);
        if (accessKey != null && isInsecurePlaceholder(accessKey)) {
            errors.add("rewit.minio.access-key (OBJECT_STORAGE_ACCESS_KEY) usa valor de exemplo ou desenvolvimento proibido.");
        }

        String secretKey = resolveRequired(env, "rewit.minio.secret-key", "OBJECT_STORAGE_SECRET_KEY", errors);
        if (secretKey != null && isInsecurePlaceholder(secretKey)) {
            errors.add("rewit.minio.secret-key (OBJECT_STORAGE_SECRET_KEY) usa valor de exemplo ou desenvolvimento proibido.");
        }

        String bucket = resolveRequired(env, "rewit.minio.bucket-name", "OBJECT_STORAGE_BUCKET", errors);
        if (bucket != null && isInsecurePlaceholder(bucket)) {
            errors.add("rewit.minio.bucket-name (OBJECT_STORAGE_BUCKET) não pode usar bucket default de desenvolvimento em produção.");
        }

        String region = resolveOptional(env, "rewit.minio.region");
        if (isPresent(region) && isInsecurePlaceholder(region)) {
            errors.add("rewit.minio.region (OBJECT_STORAGE_REGION) usa valor de exemplo proibido.");
        }

        if (isTrue(env, "rewit.minio.auto-create-bucket", false)) {
            errors.add("rewit.minio.auto-create-bucket (OBJECT_STORAGE_AUTO_CREATE_BUCKET) deve ser 'false' em produção: o bucket deve ser provisionado previamente.");
        }
    }

    private static void validateOptionalIntegrations(ConfigurableEnvironment env, List<String> errors) {
        String googleClientIds = resolveOptional(env, "rewit.social-auth.google.client-ids");
        if (isPresent(googleClientIds)) {
            if (isInsecurePlaceholder(googleClientIds)) {
                errors.add("rewit.social-auth.google.client-ids (GOOGLE_SIGNIN_CLIENT_IDS) usa valor de exemplo proibido.");
            }
            String googleJwks = resolveOptional(env, "rewit.social-auth.google.jwks-uri");
            if (isPresent(googleJwks) && !googleJwks.trim().toLowerCase(Locale.ROOT).startsWith("https://")) {
                errors.add("rewit.social-auth.google.jwks-uri deve usar HTTPS em produção.");
            }
        }

        String appleClientIds = resolveOptional(env, "rewit.social-auth.apple.client-ids");
        if (isPresent(appleClientIds)) {
            if (isInsecurePlaceholder(appleClientIds)) {
                errors.add("rewit.social-auth.apple.client-ids (APPLE_SIGNIN_CLIENT_IDS) usa valor de exemplo proibido.");
            }
            String applePackage = resolveOptional(env, "rewit.social-auth.apple-android-package");
            if (applePackage != null && applePackage.isBlank()) {
                errors.add("rewit.social-auth.apple-android-package (APPLE_SIGNIN_ANDROID_PACKAGE) não pode ser vazio quando Apple Sign-In estiver ativo.");
            }
            String appleJwks = resolveOptional(env, "rewit.social-auth.apple.jwks-uri");
            if (isPresent(appleJwks) && !appleJwks.trim().toLowerCase(Locale.ROOT).startsWith("https://")) {
                errors.add("rewit.social-auth.apple.jwks-uri deve usar HTTPS em produção.");
            }
        }

        String discoveryProvider = resolveOptional(env, "rewit.place-discovery.provider");
        if (isPresent(discoveryProvider)) {
            String normalized = discoveryProvider.trim().toUpperCase(Locale.ROOT);
            if (!"GOOGLE".equals(normalized) && !"GEOAPIFY".equals(normalized)) {
                errors.add("rewit.place-discovery.provider (PLACE_DISCOVERY_PROVIDER) inválido: use GOOGLE ou GEOAPIFY.");
            }
        }
        String googlePlacesKey = resolveOptional(env, "rewit.google.places.api-key");
        if (isPresent(googlePlacesKey)) {
            if (isInsecurePlaceholder(googlePlacesKey)) {
                errors.add("rewit.google.places.api-key (GOOGLE_PLACES_API_KEY) usa valor de exemplo proibido.");
            }
            String googlePlacesBaseUrl = resolveOptional(env, "rewit.google.places.base-url");
            if (isPresent(googlePlacesBaseUrl) && !googlePlacesBaseUrl.trim().toLowerCase(Locale.ROOT).startsWith("https://")) {
                errors.add("rewit.google.places.base-url (GOOGLE_PLACES_BASE_URL) deve usar HTTPS em produção.");
            }
        }
        String geoapifyKey = resolveOptional(env, "rewit.geoapify.api-key");
        if (isPresent(geoapifyKey)) {
            if (isInsecurePlaceholder(geoapifyKey)) {
                errors.add("rewit.geoapify.api-key (GEOAPIFY_API_KEY) usa valor de exemplo proibido.");
            }
            String geoapifyBaseUrl = resolveOptional(env, "rewit.geoapify.base-url");
            if (isPresent(geoapifyBaseUrl) && !geoapifyBaseUrl.trim().toLowerCase(Locale.ROOT).startsWith("https://")) {
                errors.add("rewit.geoapify.base-url (GEOAPIFY_BASE_URL) deve usar HTTPS em produção.");
            }
        }

        String otlpEndpoint = resolveOptional(env, "management.opentelemetry.tracing.export.otlp.endpoint");
        if (isPresent(otlpEndpoint)) {
            try {
                URI uri = URI.create(otlpEndpoint.trim());
                String scheme = uri.getScheme();
                if (!isPresent(uri.getHost()) || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                    errors.add("management.opentelemetry.tracing.export.otlp.endpoint (MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT) deve ser uma URL http(s) válida.");
                }
            } catch (IllegalArgumentException e) {
                errors.add("management.opentelemetry.tracing.export.otlp.endpoint (MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT) possui formato inválido.");
            }
            String samplingStr = resolveOptional(env, "management.tracing.sampling.probability");
            if (isPresent(samplingStr)) {
                try {
                    double prob = Double.parseDouble(samplingStr.trim());
                    if (prob < 0.0 || prob > 1.0) {
                        errors.add("management.tracing.sampling.probability (TRACING_SAMPLING_PROBABILITY) deve estar entre 0.0 e 1.0.");
                    }
                } catch (NumberFormatException e) {
                    errors.add("management.tracing.sampling.probability (TRACING_SAMPLING_PROBABILITY) deve ser numérico.");
                }
            }
        }
    }

    private static void validateRequiredSecret(
            ConfigurableEnvironment env,
            String property,
            String envVar,
            Map<String, String> collector,
            List<String> errors
    ) {
        String value = resolveRequired(env, property, envVar, errors);
        if (value == null) {
            return;
        }
        try {
            SecuritySecretValidator.decodeSecret(envVar, value);
            collector.put(envVar, value);
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
    }

    private static void validateOptionalSecret(
            ConfigurableEnvironment env,
            String property,
            String envVar,
            Map<String, String> collector,
            List<String> errors
    ) {
        String value = resolveOptional(env, property);
        if (!isPresent(value)) {
            return;
        }
        try {
            SecuritySecretValidator.decodeSecret(envVar, value);
            collector.put(envVar, value);
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
    }

    private static String validateRequiredKeyId(
            ConfigurableEnvironment env,
            String property,
            String envVar,
            List<String> errors
    ) {
        String keyId = resolveRequired(env, property, envVar, errors);
        if (keyId == null) {
            return null;
        }
        String trimmed = keyId.trim();
        if (!KEY_ID_PATTERN.matcher(trimmed).matches()) {
            errors.add(property + " (" + envVar + ") inválido: use [A-Za-z0-9_-]{1,32}.");
            return null;
        }
        return trimmed;
    }

    private static void validateOptionalRotationPair(
            ConfigurableEnvironment env,
            String secretProperty, String secretEnvVar,
            String keyIdProperty, String keyIdEnvVar,
            String activeKeyId,
            Map<String, String> collector,
            List<String> errors
    ) {
        String prevSecret = resolveOptional(env, secretProperty);
        String prevKeyId = resolveOptional(env, keyIdProperty);
        boolean hasSecret = isPresent(prevSecret);
        boolean hasKeyId = isPresent(prevKeyId);

        if (!hasSecret && !hasKeyId) {
            return;
        }
        if (hasSecret != hasKeyId) {
            errors.add("Configuração inconsistente de rotação: " + secretEnvVar + " e " + keyIdEnvVar
                    + " devem ser configurados juntos.");
            return;
        }
        try {
            SecuritySecretValidator.decodeSecret(secretEnvVar, prevSecret);
            collector.put(secretEnvVar, prevSecret);
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
        String trimmedPrevKeyId = prevKeyId.trim();
        if (!KEY_ID_PATTERN.matcher(trimmedPrevKeyId).matches()) {
            errors.add(keyIdProperty + " (" + keyIdEnvVar + ") inválido: use [A-Za-z0-9_-]{1,32}.");
        } else if (activeKeyId != null && activeKeyId.equals(trimmedPrevKeyId)) {
            errors.add(keyIdEnvVar + " não pode ser igual ao key ID ativo.");
        }
    }

    /**
     * Delega para {@link PostgresProductionJdbcUrlValidator#isLocalOrDevHost(String)}.
     */
    static boolean isLocalOrDevHost(String host) {
        return PostgresProductionJdbcUrlValidator.isLocalOrDevHost(host);
    }

    private static int parseValidPort(String raw) {
        return PostgresProductionJdbcUrlValidator.parseValidPort(raw);
    }

    private static boolean hasValidOptionalPort(URI uri) {
        return validateUriPort(uri, false);
    }

    private static boolean hasValidRequiredPort(URI uri) {
        return validateUriPort(uri, true);
    }

    private static boolean validateUriPort(URI uri, boolean required) {
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isBlank()) {
            return false;
        }
        int atIdx = authority.lastIndexOf('@');
        String hostAndPort = atIdx >= 0 ? authority.substring(atIdx + 1) : authority;
        if (hostAndPort.startsWith("[")) {
            int closeBracket = hostAndPort.indexOf(']');
            if (closeBracket <= 1) {
                return false;
            }
            String remainder = hostAndPort.substring(closeBracket + 1);
            if (remainder.isEmpty()) {
                return !required;
            }
            if (!remainder.startsWith(":")) {
                return false;
            }
            return parseValidPort(remainder.substring(1)) >= 1;
        }
        int colonIdx = hostAndPort.indexOf(':');
        if (colonIdx < 0) {
            return !required;
        }
        if (hostAndPort.indexOf(':', colonIdx + 1) >= 0) {
            return false;
        }
        return parseValidPort(hostAndPort.substring(colonIdx + 1)) >= 1;
    }

    private static String stripBrackets(String host) {
        String trimmed = host.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean hasNonBlankItems(List<String> items) {
        if (items == null || items.isEmpty()) {
            return false;
        }
        for (String item : items) {
            if (isPresent(item)) {
                return true;
            }
        }
        return false;
    }

    private static String bindOptionalString(Binder binder, String property) {
        try {
            return binder.bind(property, String.class).orElse(null);
        } catch (BindException | IllegalArgumentException unresolved) {
            return null;
        }
    }

    private static String bindRequiredString(
            Binder binder,
            String property,
            String envVar,
            List<String> errors
    ) {
        String value;
        try {
            value = binder.bind(property, String.class).orElse(null);
        } catch (BindException | IllegalArgumentException unresolved) {
            errors.add(property + " sem valor: defina " + envVar + ".");
            return null;
        }
        if (value == null || value.isBlank()) {
            errors.add(property + " ausente ou vazio: defina " + envVar + ".");
            return null;
        }
        return value;
    }

    private static boolean bindBoolean(
            Binder binder,
            String property,
            boolean defaultValue,
            List<String> errors
    ) {
        try {
            return binder.bind(property, Boolean.class).orElse(defaultValue);
        } catch (BindException e) {
            errors.add(property + " possui valor booleano inválido em produção.");
            return defaultValue;
        }
    }

    private static String resolveRequired(
            ConfigurableEnvironment env,
            String property,
            String envVar,
            List<String> errors
    ) {
        String value;
        try {
            value = env.getProperty(property);
        } catch (IllegalArgumentException unresolved) {
            errors.add(property + " sem valor: defina " + envVar + ".");
            return null;
        }
        if (value == null || value.isBlank()) {
            errors.add(property + " ausente ou vazio: defina " + envVar + ".");
            return null;
        }
        return value;
    }

    private static String resolveOptional(ConfigurableEnvironment env, String property) {
        try {
            return env.getProperty(property);
        } catch (IllegalArgumentException unresolved) {
            return null;
        }
    }

    private static boolean isTrue(ConfigurableEnvironment env, String property, boolean defaultValue) {
        String value = resolveOptional(env, property);
        if (!isPresent(value)) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static boolean isInsecurePlaceholder(String value) {
        return INSECURE_PLACEHOLDERS.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (isPresent(value)) {
            target.put(key, value);
        }
    }

    public static final class InvalidConfigurationException extends IllegalStateException {

        private final List<String> errors;

        InvalidConfigurationException(List<String> errors) {
            super("Perfil " + PROFILE + " recusou a inicialização (fail-closed): " + String.join("; ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> getErrors() {
            return errors;
        }
    }
}
