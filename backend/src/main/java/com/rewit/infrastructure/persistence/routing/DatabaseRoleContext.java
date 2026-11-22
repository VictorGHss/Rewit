package com.rewit.infrastructure.persistence.routing;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Contexto de thread estritamente local (nunca {@link InheritableThreadLocal}) que controla a role SQL
 * selecionada pelo {@link ModerationRoutingDataSource} antes da aquisição da conexão física.
 *
 * <p>Mantém uma pilha por thread para suportar chamadas administrativas aninhadas com limpeza determinística
 * em {@code finally}, removendo completamente o {@link ThreadLocal} quando a pilha esvazia.
 */
public final class DatabaseRoleContext {

    private static final ThreadLocal<Deque<DatabaseRole>> ROLE_STACK = new ThreadLocal<>();

    private DatabaseRoleContext() {
    }

    /** Role efetiva da thread atual; sempre {@link DatabaseRole#APP} quando o contexto não está ativo. */
    public static DatabaseRole current() {
        Deque<DatabaseRole> stack = ROLE_STACK.get();
        if (stack == null || stack.isEmpty()) {
            return DatabaseRole.APP;
        }
        return stack.peek();
    }

    public static boolean isModerationActive() {
        return current() == DatabaseRole.MODERATION;
    }

    public static boolean isMediaLifecycleActive() {
        return current() == DatabaseRole.MEDIA_LIFECYCLE;
    }

    public static void push(DatabaseRole role) {
        Objects.requireNonNull(role, "role must not be null");
        Deque<DatabaseRole> stack = ROLE_STACK.get();
        if (stack == null) {
            stack = new ArrayDeque<>(2);
            ROLE_STACK.set(stack);
        }
        stack.push(role);
    }

    public static void pop() {
        Deque<DatabaseRole> stack = ROLE_STACK.get();
        if (stack == null) {
            return;
        }
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            ROLE_STACK.remove();
        }
    }

    /** Limpa completamente o contexto da thread atual. */
    public static void clear() {
        ROLE_STACK.remove();
    }
}
