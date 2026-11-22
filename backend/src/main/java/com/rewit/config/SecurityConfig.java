package com.rewit.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.domain.enums.Role;
import com.rewit.infrastructure.security.SecuritySecretValidator;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.annotation.web.configurers.FormLoginConfigurer;
import org.springframework.security.config.annotation.web.configurers.HttpBasicConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuração central do Spring Security 7.x para autenticação stateless via JWT.
 * Valida de forma fail-closed todos os segredos criptográficos de autenticação no startup
 * e compartilha o mesmo material binário decodificado de {@code JWT_SECRET} com o emissor.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final byte[] jwtSecretBytes;
    private final String jwtIssuer;
    private final String jwtAudience;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public SecurityConfig(
            @Value("${rewit.security.jwt-secret}") String jwtSecret,
            @Value("${rewit.security.password-pepper}") String passwordPepper,
            @Value("${rewit.security.password-pepper-previous:}") String passwordPepperPrevious,
            @Value("${rewit.security.refresh-token-secret}") String refreshTokenSecret,
            @Value("${rewit.security.refresh-token-secret-previous:}") String refreshTokenSecretPrevious,
            @Value("${rewit.security.moderation-audit-secret}") String moderationAuditSecret,
            @Value("${rewit.security.moderation-audit-secret-previous:}") String moderationAuditSecretPrevious,
            @Value("${spring.datasource.password:}") String appDbPassword,
            @Value("${rewit.datasource.media.password:}") String mediaDbPassword,
            @Value("${rewit.datasource.moderation.password:}") String modDbPassword,
            @Value("${spring.flyway.password:}") String flywayDbPassword,
            @Value("${rewit.security.jwt-issuer:rewit-api}") String jwtIssuer,
            @Value("${rewit.security.jwt-audience:rewit-clients}") String jwtAudience
    ) {
        this.jwtSecretBytes = SecuritySecretValidator.decodeSecret("JWT_SECRET", jwtSecret);
        SecuritySecretValidator.validateSecret("PASSWORD_PEPPER_SECRET", passwordPepper);
        SecuritySecretValidator.validateSecret("REFRESH_TOKEN_SECRET", refreshTokenSecret);
        byte[] modAuditBytes = SecuritySecretValidator.decodeSecret("MODERATION_AUDIT_SECRET", moderationAuditSecret);

        Map<String, String> configuredSecrets = new LinkedHashMap<>();
        configuredSecrets.put("JWT_SECRET", jwtSecret);
        configuredSecrets.put("PASSWORD_PEPPER_SECRET", passwordPepper);
        if (passwordPepperPrevious != null && !passwordPepperPrevious.isBlank()) {
            SecuritySecretValidator.validateSecret("PASSWORD_PEPPER_SECRET_PREVIOUS", passwordPepperPrevious);
            configuredSecrets.put("PASSWORD_PEPPER_SECRET_PREVIOUS", passwordPepperPrevious);
        }
        configuredSecrets.put("REFRESH_TOKEN_SECRET", refreshTokenSecret);
        if (refreshTokenSecretPrevious != null && !refreshTokenSecretPrevious.isBlank()) {
            SecuritySecretValidator.validateSecret("REFRESH_TOKEN_SECRET_PREVIOUS", refreshTokenSecretPrevious);
            configuredSecrets.put("REFRESH_TOKEN_SECRET_PREVIOUS", refreshTokenSecretPrevious);
        }
        configuredSecrets.put("MODERATION_AUDIT_SECRET", moderationAuditSecret);
        byte[] modAuditPrevBytes = null;
        if (moderationAuditSecretPrevious != null && !moderationAuditSecretPrevious.isBlank()) {
            modAuditPrevBytes = SecuritySecretValidator.decodeSecret("MODERATION_AUDIT_SECRET_PREVIOUS", moderationAuditSecretPrevious);
            configuredSecrets.put("MODERATION_AUDIT_SECRET_PREVIOUS", moderationAuditSecretPrevious);
        }
        SecuritySecretValidator.validateDistinct(configuredSecrets);

        // Garante independência de MODERATION_AUDIT_SECRET (e anterior) em relação às senhas de banco (runtime e migrator)
        validateNotSameAsPassword("MODERATION_AUDIT_SECRET", moderationAuditSecret, modAuditBytes, "APP_DB_PASSWORD", appDbPassword);
        validateNotSameAsPassword("MODERATION_AUDIT_SECRET", moderationAuditSecret, modAuditBytes, "MEDIA_DB_PASSWORD", mediaDbPassword);
        validateNotSameAsPassword("MODERATION_AUDIT_SECRET", moderationAuditSecret, modAuditBytes, "MOD_DB_PASSWORD", modDbPassword);
        validateNotSameAsPassword("MODERATION_AUDIT_SECRET", moderationAuditSecret, modAuditBytes, "FLYWAY_DB_PASSWORD", flywayDbPassword);
        if (modAuditPrevBytes != null) {
            validateNotSameAsPassword("MODERATION_AUDIT_SECRET_PREVIOUS", moderationAuditSecretPrevious, modAuditPrevBytes, "APP_DB_PASSWORD", appDbPassword);
            validateNotSameAsPassword("MODERATION_AUDIT_SECRET_PREVIOUS", moderationAuditSecretPrevious, modAuditPrevBytes, "MEDIA_DB_PASSWORD", mediaDbPassword);
            validateNotSameAsPassword("MODERATION_AUDIT_SECRET_PREVIOUS", moderationAuditSecretPrevious, modAuditPrevBytes, "MOD_DB_PASSWORD", modDbPassword);
            validateNotSameAsPassword("MODERATION_AUDIT_SECRET_PREVIOUS", moderationAuditSecretPrevious, modAuditPrevBytes, "FLYWAY_DB_PASSWORD", flywayDbPassword);
        }

        this.jwtIssuer = jwtIssuer;
        this.jwtAudience = jwtAudience;
    }

    public SecurityConfig(
            String jwtSecret,
            String passwordPepper,
            String passwordPepperPrevious,
            String refreshTokenSecret,
            String refreshTokenSecretPrevious,
            String moderationAuditSecret,
            String moderationAuditSecretPrevious,
            String appDbPassword,
            String mediaDbPassword,
            String modDbPassword,
            String jwtIssuer,
            String jwtAudience
    ) {
        this(
                jwtSecret,
                passwordPepper,
                passwordPepperPrevious,
                refreshTokenSecret,
                refreshTokenSecretPrevious,
                moderationAuditSecret,
                moderationAuditSecretPrevious,
                appDbPassword,
                mediaDbPassword,
                modDbPassword,
                "",
                jwtIssuer,
                jwtAudience
        );
    }

    public SecurityConfig(String jwtSecret, String jwtIssuer, String jwtAudience) {
        this(
                jwtSecret,
                "test_password_pepper_secret_with_sufficient_entropy_and_at_least_32_bytes_12345!",
                "",
                "test_refresh_token_secret_with_sufficient_entropy_and_at_least_32_bytes_12345!",
                "",
                "test_moderation_audit_secret_with_sufficient_entropy_and_at_least_32_bytes_1!",
                "",
                "",
                "",
                "",
                "",
                jwtIssuer,
                jwtAudience
        );
    }

    private static void validateNotSameAsPassword(String secretName, String secretRaw, byte[] secretBytes,
                                                  String passwordName, String passwordRaw) {
        if (passwordRaw == null || passwordRaw.isBlank()) {
            return;
        }
        if (secretRaw.trim().equals(passwordRaw.trim())
                || java.security.MessageDigest.isEqual(secretBytes, passwordRaw.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException(
                    "Reutilização de credencial proibida: '" + secretName + "' não pode coincidir com '" + passwordName + "'.");
        }
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(SecurityConfig::disableCsrf)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(SecurityConfig::disableFormLogin)
                .httpBasic(SecurityConfig::disableHttpBasic)
                .authorizeHttpRequests(auth -> auth
                        // Actuator (ADR-012): esta chain também protege a porta de management. Só health, info e
                        // prometheus, sem credencial (o isolamento é a porta interna); qualquer outro endpoint é negado
                        .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).denyAll()
                        // Endpoints públicos de autenticação e documentação
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/reactivate", "/api/v1/auth/social/nonce", "/api/v1/auth/social/google",
                                "/api/v1/auth/social/apple", "/api/v1/auth/social/google/reactivate",
                                "/api/v1/auth/social/apple/reactivate", "/api/v1/auth/apple/callback").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        // Endpoints de autenticação protegidos
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        // Endpoints de identidade e perfil protegidos
                        .requestMatchers("/api/v1/me", "/api/v1/me/**").authenticated()
                        // Qualquer outro endpoint da API exige autenticação por padrão
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder())
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                        .authenticationEntryPoint(customAuthenticationEntryPoint())
                );

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        SecretKeySpec secretKey = new SecretKeySpec(jwtSecretBytes, "HmacSHA256");

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(jwtIssuer);
        OAuth2TokenValidator<Jwt> withAudience = new AudienceValidator(jwtAudience);
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience);

        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("sub");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            if (role == null || role.isBlank()) {
                return List.of(new SimpleGrantedAuthority(Role.USER.getAuthority()));
            }
            Role resolvedRole;
            try {
                resolvedRole = Role.valueOf(role.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                resolvedRole = Role.USER;
            }
            return List.of(new SimpleGrantedAuthority(resolvedRole.getAuthority()));
        });
        return converter;
    }

    @Bean
    public AuthenticationEntryPoint customAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());

            Map<String, Object> errorBody = new HashMap<>();
            errorBody.put("type", "about:blank");
            errorBody.put("title", "Não autorizado");
            errorBody.put("status", 401);
            errorBody.put("detail", "Autenticação requerida ou token inválido/expirado");
            // "code" é a chave do código de negócio em todos os ProblemDetail da API (GlobalExceptionHandler) e a
            // que os clientes leem; "errorCode" permanece por compatibilidade com consumidores existentes
            errorBody.put("code", "AUTHENTICATION_REQUIRED");
            errorBody.put("errorCode", "AUTHENTICATION_REQUIRED");
            errorBody.put("timestamp", Instant.now().toString());

            response.getWriter().write(objectMapper.writeValueAsString(errorBody));
        };
    }

    private static void disableCsrf(CsrfConfigurer<HttpSecurity> configurer) {
        configurer.disable();
    }

    private static void disableFormLogin(FormLoginConfigurer<HttpSecurity> configurer) {
        configurer.disable();
    }

    private static void disableHttpBasic(HttpBasicConfigurer<HttpSecurity> configurer) {
        configurer.disable();
    }

    /**
     * Validador customizado de audience (aud) para tokens JWT emitidos.
     */
    static class AudienceValidator implements OAuth2TokenValidator<Jwt> {
        private final String requiredAudience;

        public AudienceValidator(String requiredAudience) {
            this.requiredAudience = requiredAudience;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            List<String> audience = jwt.getAudience();
            if (audience != null && audience.contains(requiredAudience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "O audience do token é inválido", null));
        }
    }
}
