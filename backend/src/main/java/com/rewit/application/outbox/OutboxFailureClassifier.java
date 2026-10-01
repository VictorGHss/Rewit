package com.rewit.application.outbox;

import com.rewit.common.exception.BusinessException;

import java.util.Objects;

/**
 * Regra explícita de classificação de falhas do dispatcher do Outbox (Step 27.2).
 * Nenhuma exceção é classificada por omissão de regra:
 *
 * <ul>
 *   <li>{@link OutboxPermanentException} -&gt; PERMANENT: falha determinística
 *       declarada pelo handler.</li>
 *   <li>{@link BusinessException} -&gt; PERMANENT: rejeição de regra de negócio não
 *       muda de resultado entre tentativas.</li>
 *   <li>Qualquer outra exceção (incluindo {@link OutboxTransientException} e falhas
 *       desconhecidas de runtime) -&gt; TRANSIENT: default conservador at-least-once,
 *       sempre limitado pelo teto de tentativas da política de retry.</li>
 * </ul>
 */
public final class OutboxFailureClassifier {

    private OutboxFailureClassifier() {
    }

    public static OutboxFailureType classify(Throwable error) {
        Objects.requireNonNull(error, "error must not be null");
        if (error instanceof OutboxPermanentException) {
            return OutboxFailureType.PERMANENT;
        }
        if (error instanceof BusinessException) {
            return OutboxFailureType.PERMANENT;
        }
        return OutboxFailureType.TRANSIENT;
    }
}
