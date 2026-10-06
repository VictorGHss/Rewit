package com.rewit.application.port;

import com.rewit.application.ratelimit.RateLimitPermit;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.Optional;

/**
 * Porta de saída para limitar a frequência de ações por sujeito, de forma consistente entre instâncias da API.
 *
 * <p>Cada tentativa concedida ocupa uma posição na janela deslizante da ação; tentativas negadas não ocupam.
 * Limite e janela pertencem à configuração da ação, não ao chamador.
 */
public interface RateLimiter {

    String ERROR_CODE = "RATE_LIMIT_EXCEEDED";

    /**
     * Tenta registrar uma tentativa do sujeito na janela da ação.
     *
     * @return a tentativa concedida, ou vazio se o limite da janela foi atingido
     */
    Optional<RateLimitPermit> tryAcquire(RateLimitedAction action, RateLimitSubject subject);

    /**
     * Devolve uma tentativa concedida, liberando a sua posição na janela (ex.: login bem-sucedido não conta
     * como tentativa falha). Idempotente e sem exceção: uma falha ao devolver mantém a tentativa contada.
     */
    void release(RateLimitPermit permit);

    /**
     * Registra uma tentativa ou rejeita com {@code 429 RATE_LIMIT_EXCEEDED} e a mensagem da ação.
     */
    default RateLimitPermit acquireOrThrow(RateLimitedAction action, RateLimitSubject subject) {
        return tryAcquire(action, subject).orElseThrow(() -> new BusinessException(
                action.exceededMessage(), HttpStatus.TOO_MANY_REQUESTS, ERROR_CODE));
    }
}
