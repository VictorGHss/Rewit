package com.rewit.infrastructure.persistence.routing;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Interceptor AOP ordenado antes do {@code TransactionInterceptor} para ativar
 * {@link DatabaseRole#MEDIA_LIFECYCLE} antes da abertura da transação física e removê-lo
 * deterministicamente em {@code finally} (SEC-F2-03).
 *
 * <p><b>Fail-closed em transação aninhada:</b> se já existir uma transação física ativa na thread atual sob
 * outra role ({@link DatabaseRole#APP} ou {@link DatabaseRole#MODERATION}), a execução é recusada
 * imediatamente em vez de herdar silenciosamente a conexão de outra identidade.
 */
public class MediaLifecycleRoutingMethodInterceptor implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !DatabaseRoleContext.isMediaLifecycleActive()) {
            throw new IllegalStateException(
                    "Roteamento transacional violado (fail-closed): operação @MediaLifecycleTransactional "
                            + "não pode entrar em uma transação já aberta sob " + DatabaseRoleContext.current() + "."
            );
        }

        DatabaseRoleContext.push(DatabaseRole.MEDIA_LIFECYCLE);
        try {
            return invocation.proceed();
        } finally {
            DatabaseRoleContext.pop();
        }
    }
}
