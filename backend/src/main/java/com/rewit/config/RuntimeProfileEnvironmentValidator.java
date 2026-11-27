package com.rewit.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Política fail-closed de seleção explícita de profile Spring e consistência com o ambiente de implantação
 * (F2-DEPLOY-03.3 / F2-DEPLOY-03.3-R1).
 *
 * <p>Executa antes da criação de qualquer bean, DataSource, Flyway ou abertura de portas HTTP:
 * <ul>
 *   <li>Impede inicialização silenciosa sem {@code SPRING_PROFILES_ACTIVE} definido.</li>
 *   <li>Impõe allowlist estrita de profiles reconhecidos ({@code local}, {@code external-test}, {@code prod},
 *       {@code test}): qualquer profile desconhecido (ex.: {@code production}) é rejeitado.</li>
 *   <li>Rejeita combinação ambígua entre {@code local}, {@code external-test} e {@code prod} (ou {@code test}
 *       combinado com {@code prod}/{@code external-test}).</li>
 *   <li>Rejeita inicialização com {@code local} ou {@code external-test} quando {@code ENVIRONMENT} indica
 *       ambiente de implantação ({@code prod}, {@code production}, {@code staging}, {@code homolog},
 *       {@code homologation}, {@code hml}).</li>
 *   <li>Rejeita inicialização com {@code prod} quando {@code ENVIRONMENT} indica ambiente local
 *       ({@code local}, {@code dev}, {@code development}).</li>
 * </ul>
 */
public class RuntimeProfileEnvironmentValidator
        implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

    public static final String PROFILE_LOCAL = "local";
    public static final String PROFILE_EXTERNAL_TEST = "external-test";
    public static final String PROFILE_PROD = "prod";
    public static final String PROFILE_TEST = "test";

    public static final Set<String> ALLOWED_PROFILES = Set.of(
            PROFILE_LOCAL,
            PROFILE_EXTERNAL_TEST,
            PROFILE_PROD,
            PROFILE_TEST
    );

    private static final Set<String> EXCLUSIVE_RUNTIME_PROFILES = Set.of(
            PROFILE_LOCAL,
            PROFILE_EXTERNAL_TEST,
            PROFILE_PROD
    );

    private static final Set<String> DEPLOYMENT_ENVIRONMENTS = Set.of(
            "prod",
            "production",
            "staging",
            "homolog",
            "homologation",
            "hml"
    );

    private static final Set<String> LOCAL_ENVIRONMENTS = Set.of(
            "local",
            "dev",
            "development"
    );

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        validate(applicationContext.getEnvironment());
    }

    public static void validate(ConfigurableEnvironment env) {
        List<String> errors = new ArrayList<>();

        Set<String> activeProfiles = new LinkedHashSet<>();
        for (String rawProfile : env.getActiveProfiles()) {
            if (rawProfile != null && !rawProfile.isBlank()) {
                activeProfiles.add(rawProfile.trim());
            }
        }

        if (activeProfiles.isEmpty()) {
            errors.add("SPRING_PROFILES_ACTIVE não definido: selecione explicitamente um profile "
                    + "(local, external-test ou prod). Fallback silencioso para 'local' ou 'default' é proibido.");
        }

        List<String> unknownProfiles = activeProfiles.stream()
                .filter(profile -> !ALLOWED_PROFILES.contains(profile))
                .toList();
        if (!unknownProfiles.isEmpty()) {
            errors.add("Profile Spring desconhecido ou não permitido ("
                    + String.join(", ", unknownProfiles)
                    + "): utilize exclusivamente local, external-test, prod ou test.");
        }

        List<String> activeExclusive = activeProfiles.stream()
                .filter(EXCLUSIVE_RUNTIME_PROFILES::contains)
                .toList();
        if (activeExclusive.size() > 1) {
            errors.add("Profiles de runtime conflitantes ativos simultaneamente ("
                    + String.join(", ", activeExclusive)
                    + "): selecione exatamente um entre local, external-test ou prod.");
        }

        if (activeProfiles.contains(PROFILE_TEST)
                && (activeProfiles.contains(PROFILE_PROD) || activeProfiles.contains(PROFILE_EXTERNAL_TEST))) {
            errors.add("Profile 'test' não pode ser combinado com 'prod' ou 'external-test'.");
        }

        String environment = resolveDeploymentEnvironment(env);
        if (environment != null) {
            if (DEPLOYMENT_ENVIRONMENTS.contains(environment)) {
                if (activeProfiles.contains(PROFILE_LOCAL)) {
                    errors.add("Profile 'local' é proibido no ambiente de implantação (ENVIRONMENT="
                            + environment + "): configure SPRING_PROFILES_ACTIVE=prod.");
                } else if (activeProfiles.contains(PROFILE_EXTERNAL_TEST)) {
                    errors.add("Profile 'external-test' é proibido no ambiente de implantação (ENVIRONMENT="
                            + environment + "): configure SPRING_PROFILES_ACTIVE=prod.");
                } else if (!activeProfiles.isEmpty() && !activeProfiles.contains(PROFILE_PROD)) {
                    errors.add("Ambiente de implantação (ENVIRONMENT=" + environment
                            + ") exige SPRING_PROFILES_ACTIVE=prod.");
                }
            } else if (LOCAL_ENVIRONMENTS.contains(environment) && activeProfiles.contains(PROFILE_PROD)) {
                errors.add("Profile 'prod' é incompatível com ENVIRONMENT=" + environment
                        + ": use ENVIRONMENT=production/staging ou SPRING_PROFILES_ACTIVE=local.");
            }
        }

        if (!errors.isEmpty()) {
            throw new InvalidProfileConfigurationException(errors);
        }
    }

    private static String resolveDeploymentEnvironment(ConfigurableEnvironment env) {
        for (String key : Arrays.asList("rewit.environment", "ENVIRONMENT")) {
            try {
                String value = env.getProperty(key);
                if (value != null && !value.isBlank()) {
                    return value.trim().toLowerCase(Locale.ROOT);
                }
            } catch (IllegalArgumentException ignored) {
                // Propriedade opcional não resolvida
            }
        }
        return null;
    }

    public static final class InvalidProfileConfigurationException extends IllegalStateException {

        private final List<String> errors;

        InvalidProfileConfigurationException(List<String> errors) {
            super("Seleção de profile Spring recusada (fail-closed): " + String.join("; ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> getErrors() {
            return errors;
        }
    }
}
