package com.rewit.application.port;

import com.rewit.application.dto.auth.AuthDtos.ExternalIdentity;
import com.rewit.domain.enums.AuthProvider;

/**
 * Porta de aplicação para validar o ID token (OpenID Connect) emitido por um provedor externo (Google, Apple).
 *
 * <p>A identidade devolvida vem somente das claims de um token cuja assinatura, emissor, audiência, validade e
 * nonce foram verificados. Nenhum dado de identidade informado pelo cliente fora do token é aceito.
 */
public interface ExternalIdentityVerifier {

    /** Provedor configurado neste ambiente (com ao menos uma audiência aceita). */
    boolean isEnabled(AuthProvider provider);

    /**
     * Valida o ID token e devolve a identidade verificada.
     *
     * @param expectedNonce valor que a claim {@code nonce} do token precisa conter (o hash do nonce emitido pelo Rewit)
     * @throws com.rewit.common.exception.BusinessException 401 {@code INVALID_SOCIAL_TOKEN} para token inválido,
     *         expirado, de outra audiência, de outro emissor ou com nonce divergente; 503
     *         {@code SOCIAL_PROVIDER_UNAVAILABLE} se as chaves públicas do provedor não puderem ser obtidas
     */
    ExternalIdentity verify(AuthProvider provider, String idToken, String expectedNonce);
}
