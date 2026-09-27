package com.rewit.infrastructure.integration.google;

import com.rewit.application.port.IdentityProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Adaptador de infraestrutura implementando a porta IdentityProvider via Google Identity.
 * A implementação completa com verificação de assinatura JWT/OIDC será realizada na etapa de autenticação.
 */
@Component
public class GoogleIdentityAdapter implements IdentityProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleIdentityAdapter.class);

    @Value("${GOOGLE_CLIENT_ID:mock-google-client-id}")
    private String clientId;

    @Override
    public AuthUserData verifyToken(String idToken) {
        log.debug("Verificação de token Google chamada na porta de infraestrutura (scaffold de adaptador).");
        return new AuthUserData("google-sub-placeholder", "user@example.com", "Usuário Google", null);
    }
}
