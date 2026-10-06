package com.rewit.application.ratelimit;

/**
 * Tentativa concedida por {@link com.rewit.application.port.RateLimiter#tryAcquire}. Opaco para a aplicação:
 * serve apenas para devolver a tentativa com {@link com.rewit.application.port.RateLimiter#release}.
 */
public interface RateLimitPermit {
}
