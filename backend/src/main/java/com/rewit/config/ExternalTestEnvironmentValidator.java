package com.rewit.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validação de inicialização do perfil {@code external-test} (API publicada pelo Cloudflare Tunnel).
 *
 * <p>Registrado em {@code META-INF/spring.factories}, roda antes de qualquer bean: com o perfil ativo, a aplicação não
 * sobe se uma configuração obrigatória estiver ausente, vazia ou com valor de exemplo/fallback de desenvolvimento.
 * Sem isso, um placeholder sem valor chegaria literal ao datasource e a falha seria uma autenticação recusada pelo
 * banco. As mensagens citam só a propriedade e a variável de ambiente, nunca o valor.
 */
public class ExternalTestEnvironmentValidator implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String PROFILE = "external-test";

    /** Propriedade efetiva -> variável de ambiente que a alimenta no perfil. */
    private static final Map<String, String> REQUIRED = new LinkedHashMap<>();

    static {
        REQUIRED.put("spring.datasource.url", "DATABASE_URL");
        REQUIRED.put("spring.datasource.username", "APP_DB_USER");
        REQUIRED.put("spring.datasource.password", "APP_DB_PASSWORD");
        REQUIRED.put("spring.flyway.user", "FLYWAY_DB_USER ou POSTGRES_USER");
        REQUIRED.put("spring.flyway.password", "FLYWAY_DB_PASSWORD ou POSTGRES_PASSWORD");
        REQUIRED.put("spring.data.redis.host", "REDIS_HOST");
        REQUIRED.put("spring.data.redis.port", "REDIS_PORT");
        REQUIRED.put("rewit.minio.endpoint", "OBJECT_STORAGE_ENDPOINT");
        REQUIRED.put("rewit.minio.access-key", "OBJECT_STORAGE_ACCESS_KEY");
        REQUIRED.put("rewit.minio.secret-key", "OBJECT_STORAGE_SECRET_KEY");
        REQUIRED.put("rewit.minio.bucket-name", "OBJECT_STORAGE_BUCKET");
        REQUIRED.put("rewit.security.jwt-secret", "JWT_SECRET");
        REQUIRED.put("rewit.rate-limit.key-secret", "RATE_LIMIT_KEY_SECRET");
    }

    /** Credenciais que não podem valer num ambiente publicado: valores do .env.example e fallbacks do perfil local. */
    private static final Set<String> CREDENTIAL_PROPERTIES = Set.of(
            "spring.datasource.password",
            "spring.flyway.password",
            "rewit.minio.access-key",
            "rewit.minio.secret-key",
            "rewit.security.jwt-secret",
            "rewit.rate-limit.key-secret"
    );

    private static final Set<String> INSECURE_VALUES = Set.of(
            "change-me",
            "changeme",
            "rewit_local_password",
            "rewit_local_access",
            "rewit_local_secret"
    );

    private static final Pattern JDBC_URL_WITH_EXPLICIT_PORT = Pattern.compile("^jdbc:postgresql://[^/?#:]+:(\\d+)/[^/?#]+.*$");

    /**
     * Portas dos serviços compartilhados de desenvolvimento (docker-compose.yml e PostgreSQL nativo). O perfil publicado
     * usa a infraestrutura isolada de docker-compose.external-test.yml: apontar para estas portas aplicaria as
     * migrations Flyway e gravaria dados de teste externo nos serviços de desenvolvimento.
     */
    private static final Set<Integer> SHARED_POSTGRES_PORTS = Set.of(5432, 5433);
    private static final int SHARED_REDIS_PORT = 6379;
    private static final int SHARED_STORAGE_PORT = 8333;
    private static final String ISOLATED_INFRASTRUCTURE = "use a infraestrutura isolada (docker-compose.external-test.yml)";

    /** Propriedades efetivas exigidas pelo perfil. */
    public static Set<String> requiredProperties() {
        return Collections.unmodifiableSet(REQUIRED.keySet());
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
        for (Map.Entry<String, String> entry : REQUIRED.entrySet()) {
            String property = entry.getKey();
            String variable = entry.getValue();
            String value;
            try {
                value = env.getProperty(property);
            } catch (IllegalArgumentException unresolved) {
                errors.add(property + " sem valor: defina " + variable);
                continue;
            }
            if (value == null || value.isBlank()) {
                errors.add(property + " ausente ou vazio: defina " + variable);
                continue;
            }
            if (CREDENTIAL_PROPERTIES.contains(property) && INSECURE_VALUES.contains(value.trim().toLowerCase(Locale.ROOT))) {
                errors.add(property + " usa valor de exemplo ou de desenvolvimento: defina " + variable + " com um segredo próprio");
            }
            if ("spring.datasource.url".equals(property)) {
                Matcher jdbc = JDBC_URL_WITH_EXPLICIT_PORT.matcher(value.trim());
                if (!jdbc.matches()) {
                    errors.add(property + " precisa ser jdbc:postgresql://host:porta/database com porta explícita: corrija "
                            + variable);
                } else if (SHARED_POSTGRES_PORTS.contains(Integer.parseInt(jdbc.group(1)))) {
                    errors.add(property + " aponta para o PostgreSQL compartilhado de desenvolvimento (porta "
                            + jdbc.group(1) + "): " + ISOLATED_INFRASTRUCTURE);
                }
            }
            if ("spring.data.redis.port".equals(property) && !isIsolatedPort(value, SHARED_REDIS_PORT)) {
                errors.add(property + " precisa ser uma porta diferente da do Redis compartilhado (" + SHARED_REDIS_PORT + "): "
                        + ISOLATED_INFRASTRUCTURE);
            }
            if ("rewit.minio.endpoint".equals(property) && !isIsolatedPort(storagePort(value), SHARED_STORAGE_PORT)) {
                errors.add(property + " precisa ser http(s)://host:porta com porta diferente da do storage compartilhado ("
                        + SHARED_STORAGE_PORT + "): " + ISOLATED_INFRASTRUCTURE);
            }
        }

        if (!errors.isEmpty()) {
            throw new InvalidConfigurationException(errors);
        }
    }

    /** Porta explícita, numérica e diferente da compartilhada. */
    private static boolean isIsolatedPort(String port, int sharedPort) {
        if (port == null) {
            return false;
        }
        try {
            return Integer.parseInt(port.trim()) != sharedPort;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    /** Porta explícita do endpoint S3, ou null quando ausente ou inválida. */
    private static String storagePort(String endpoint) {
        try {
            int port = URI.create(endpoint.trim()).getPort();
            return port == -1 ? null : String.valueOf(port);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /**
     * Falha de configuração do perfil. Os logs JSON descartam mensagens de exceção (SanitizedStackTracePrinter), então o
     * motivo chega ao operador pelo {@link ExternalTestConfigurationFailureAnalyzer}, como mensagem de log.
     */
    public static final class InvalidConfigurationException extends IllegalStateException {

        private final List<String> errors;

        InvalidConfigurationException(List<String> errors) {
            super("Perfil " + PROFILE + " recusou a inicialização: " + String.join("; ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> getErrors() {
            return errors;
        }
    }
}
