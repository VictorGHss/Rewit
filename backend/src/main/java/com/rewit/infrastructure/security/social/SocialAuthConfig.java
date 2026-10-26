package com.rewit.infrastructure.security.social;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.rewit.application.port.ExternalIdentityVerifier;
import com.rewit.application.port.SocialLoginNonceStore;
import com.rewit.domain.enums.AuthProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Clock;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Montagem do login social: verificadores de ID token para os provedores com client ID configurado e o
 * armazenamento de nonces no Redis.
 *
 * <p>As chaves públicas (JWKS) são buscadas sob demanda e mantidas em cache pelo Nimbus, com renovação antecipada,
 * nova busca quando aparece um {@code kid} desconhecido (rotação de chaves) e intervalo mínimo entre buscas.
 */
@Configuration
public class SocialAuthConfig {

    // Documentos JWKS dos provedores têm poucos KB
    private static final int JWKS_SIZE_LIMIT_BYTES = 64 * 1024;

    @Bean
    public ExternalIdentityVerifier externalIdentityVerifier(SocialAuthProperties properties) {
        properties.validate();
        Map<AuthProvider, OidcIdTokenVerifier> verifiers = new EnumMap<>(AuthProvider.class);
        addIfEnabled(verifiers, AuthProvider.GOOGLE, properties.getGoogle(), properties.getJwksTimeoutMs());
        addIfEnabled(verifiers, AuthProvider.APPLE, properties.getApple(), properties.getJwksTimeoutMs());
        return new OidcExternalIdentityVerifier(verifiers);
    }

    @Bean
    public SocialLoginNonceStore socialLoginNonceStore(RedisConnectionFactory redisConnectionFactory) {
        return new RedisSocialLoginNonceStore(new StringRedisTemplate(redisConnectionFactory));
    }

    private static void addIfEnabled(Map<AuthProvider, OidcIdTokenVerifier> verifiers, AuthProvider provider,
                                     SocialAuthProperties.Provider config, int timeoutMs) {
        if (!config.isEnabled()) {
            return;
        }
        verifiers.put(provider, new OidcIdTokenVerifier(
                provider,
                new HashSet<>(config.getIssuers()),
                new HashSet<>(config.configuredClientIds()),
                remoteJwkSource(config.getJwksUri(), timeoutMs),
                Clock.systemUTC()));
    }

    private static JWKSource<SecurityContext> remoteJwkSource(String jwksUri, int timeoutMs) {
        try {
            return JWKSourceBuilder.<SecurityContext>create(
                            URI.create(jwksUri).toURL(),
                            new DefaultResourceRetriever(timeoutMs, timeoutMs, JWKS_SIZE_LIMIT_BYTES))
                    .retrying(true)
                    .build();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("jwks-uri inválida no login social", e);
        }
    }
}
