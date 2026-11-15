package com.rewit.infrastructure.security.social;

import com.rewit.application.dto.auth.AuthDtos.ExternalIdentity;
import com.rewit.application.port.ExternalIdentityVerifier;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import org.springframework.http.HttpStatus;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Implementação de {@link ExternalIdentityVerifier} com um {@link OidcIdTokenVerifier} por provedor ativo.
 */
public class OidcExternalIdentityVerifier implements ExternalIdentityVerifier {

    private final Map<AuthProvider, OidcIdTokenVerifier> verifiers;

    public OidcExternalIdentityVerifier(Map<AuthProvider, OidcIdTokenVerifier> verifiers) {
        Objects.requireNonNull(verifiers, "verifiers must not be null");
        this.verifiers = verifiers.isEmpty() ? Map.of() : new EnumMap<>(verifiers);
        if (this.verifiers.containsKey(AuthProvider.LOCAL)) {
            throw new IllegalArgumentException("LOCAL não é um provedor externo");
        }
    }

    @Override
    public boolean isEnabled(AuthProvider provider) {
        return provider != null && verifiers.containsKey(provider);
    }

    @Override
    public ExternalIdentity verify(AuthProvider provider, String idToken, String expectedNonce) {
        OidcIdTokenVerifier verifier = provider != null ? verifiers.get(provider) : null;
        if (verifier == null) {
            throw new BusinessException("Login com este provedor não está disponível", HttpStatus.SERVICE_UNAVAILABLE,
                    "SOCIAL_PROVIDER_UNAVAILABLE");
        }
        return verifier.verify(idToken, expectedNonce);
    }
}
