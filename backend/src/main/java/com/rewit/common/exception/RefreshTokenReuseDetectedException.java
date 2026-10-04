package com.rewit.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Reúso de refresh token revogado fora da janela de rotação concorrente (Step 29.1).
 *
 * <p>Distinta de {@link BusinessException} para que {@code AuthService.refresh} possa commitar a revogação
 * em massa das sessões do usuário antes de devolver o erro: apenas esta exceção está em
 * {@code noRollbackFor}. A resposta HTTP é a mesma do {@code BusinessException} de origem.
 */
public class RefreshTokenReuseDetectedException extends BusinessException {

    public RefreshTokenReuseDetectedException() {
        super("Refresh token revogado ou já reutilizado. Todas as sessões foram invalidadas.",
                HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REVOKED");
    }
}
