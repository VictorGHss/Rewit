package com.rewit.application.service;

import com.rewit.application.dto.auth.AuthDtos.AuthResult;
import com.rewit.application.dto.auth.AuthDtos.ExternalIdentity;
import com.rewit.application.dto.auth.AuthDtos.SocialLoginCommand;
import com.rewit.application.dto.auth.AuthDtos.SocialNonceResult;
import com.rewit.application.port.ExternalIdentityVerifier;
import com.rewit.application.port.RateLimiter;
import com.rewit.application.port.SocialLoginNonceStore;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Login social (Google, Apple) com ID token OpenID Connect validado no servidor.
 *
 * <p>Fluxo: o app pede um nonce ao Rewit, entrega ao provedor o SHA-256 do nonce (hex minúsculo) e devolve ao Rewit
 * o ID token e o nonce em claro. O token só é aceito se a claim {@code nonce} for o hash desse nonce, emitido aqui e
 * consumido uma única vez. Quem intercepta o token (por exemplo no redirecionamento do fluxo web da Apple) vê apenas
 * o hash e não consegue reapresentá-lo; um token de outra pessoa injetado no app não casa com o nonce da tentativa.
 *
 * <p>Sem transação neste nível: o nonce é consumido antes da validação e não volta em caso de erro, e a consulta às
 * chaves públicas do provedor não segura conexão do banco. A sessão é criada em {@link AuthService}.
 */
@Service
public class SocialAuthService {

    static final Duration NONCE_TTL = Duration.ofMinutes(10);
    private static final int NONCE_BYTES = 32;
    // 32 bytes em Base64 URL-safe sem padding
    private static final Pattern NONCE_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final AuthService authService;
    private final ExternalIdentityVerifier identityVerifier;
    private final SocialLoginNonceStore nonceStore;
    private final RateLimiter rateLimiter;
    private final SecureRandom secureRandom = new SecureRandom();

    public SocialAuthService(
            AuthService authService,
            ExternalIdentityVerifier identityVerifier,
            SocialLoginNonceStore nonceStore,
            RateLimiter rateLimiter
    ) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.identityVerifier = Objects.requireNonNull(identityVerifier, "identityVerifier must not be null");
        this.nonceStore = Objects.requireNonNull(nonceStore, "nonceStore must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter must not be null");
    }

    /** Emite um nonce de uso único, válido por {@link #NONCE_TTL}. Toda emissão conta no limite global. */
    public SocialNonceResult issueNonce() {
        rateLimiter.acquireOrThrow(RateLimitedAction.SOCIAL_LOGIN, RateLimitSubject.global());

        byte[] bytes = new byte[NONCE_BYTES];
        secureRandom.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        nonceStore.store(hashNonce(nonce), NONCE_TTL);
        return new SocialNonceResult(nonce, NONCE_TTL.toSeconds());
    }

    public AuthResult authenticate(SocialLoginCommand cmd) {
        Objects.requireNonNull(cmd, "SocialLoginCommand cannot be null");

        AuthProvider provider = cmd.provider();
        if (!isProviderEnabled(provider)) {
            throw new BusinessException("Login com este provedor não está disponível", HttpStatus.SERVICE_UNAVAILABLE,
                    "SOCIAL_PROVIDER_UNAVAILABLE");
        }
        if (cmd.idToken() == null || cmd.idToken().isBlank()) {
            throw invalidToken();
        }
        if (cmd.nonce() == null || !NONCE_FORMAT.matcher(cmd.nonce()).matches()) {
            throw invalidNonce();
        }

        String nonceHash = hashNonce(cmd.nonce());
        // Consumido antes da validação: um nonce serve a uma única tentativa, válida ou não
        if (!nonceStore.consume(nonceHash)) {
            throw invalidNonce();
        }

        ExternalIdentity identity = identityVerifier.verify(provider, cmd.idToken(), nonceHash);

        try {
            return authService.authenticateExternalIdentity(identity, cmd.userAgent(), cmd.ipAddress());
        } catch (DataIntegrityViolationException concurrentCreation) {
            // Criação concorrente da mesma identidade (ou do handle gerado): a nova transação encontra a conta
            return authService.authenticateExternalIdentity(identity, cmd.userAgent(), cmd.ipAddress());
        }
    }

    /** Provedor externo configurado neste ambiente. */
    public boolean isProviderEnabled(AuthProvider provider) {
        return provider != null && provider != AuthProvider.LOCAL && identityVerifier.isEnabled(provider);
    }

    /** SHA-256 (hex minúsculo) do nonce em UTF-8: o valor entregue ao provedor e esperado na claim {@code nonce}. */
    public static String hashNonce(String nonce) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(nonce.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }

    private static BusinessException invalidToken() {
        return new BusinessException("Credencial do provedor inválida", HttpStatus.UNAUTHORIZED, "INVALID_SOCIAL_TOKEN");
    }

    private static BusinessException invalidNonce() {
        return new BusinessException("Tentativa de login expirada ou já utilizada. Tente novamente.",
                HttpStatus.UNAUTHORIZED, "INVALID_SOCIAL_NONCE");
    }
}
