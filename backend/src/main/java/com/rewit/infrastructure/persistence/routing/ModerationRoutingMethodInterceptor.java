package com.rewit.infrastructure.persistence.routing;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Interceptor AOP ordenado antes do {@code TransactionInterceptor} para ativar {@link DatabaseRole#MODERATION}
 * antes da abertura da transação física e removê-lo deterministicamente em {@code finally}.
 *
 * <p><b>Fail-closed em transação aninhada:</b> se já existir uma transação física ativa na thread atual sob
 * {@link DatabaseRole#APP} (por exemplo, um método público {@code @Transactional} tentando chamar uma operação
 * administrativa), a execução é recusada imediatamente em vez de herdar silenciosamente a conexão {@code rewit_app}.
 */
public class ModerationRoutingMethodInterceptor implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !DatabaseRoleContext.isModerationActive()) {
            throw new IllegalStateException(
                    "Roteamento transacional violado (fail-closed): operação administrativa @ModerationTransactional "
                            + "não pode entrar em uma transação já aberta sob " + DatabaseRoleContext.current() + "."
            );
        }

        DatabaseRoleContext.push(DatabaseRole.MODERATION);
        try {
            return invocation.proceed();
        } finally {
            DatabaseRoleContext.pop();
        }
    }
}
