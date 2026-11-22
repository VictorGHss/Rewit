package com.rewit.infrastructure.persistence.routing;

import org.springframework.core.annotation.AliasFor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca exclusivamente os métodos transacionais dos seis módulos administrativos de moderação para
 * executarem sob a identidade PostgreSQL {@code rewit_mod} (S3-A13-F2).
 *
 * <p>Interceptado por {@link ModerationRoutingMethodInterceptor} com precedência estritamente anterior ao
 * {@code TransactionInterceptor} do Spring, garantindo que o contexto {@link DatabaseRole#MODERATION} esteja
 * ativo antes da aquisição da conexão física e falhando de forma fechada se já houver uma transação ativa
 * aberta sob {@code rewit_app}.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Transactional
public @interface ModerationTransactional {

    @AliasFor(annotation = Transactional.class, attribute = "propagation")
    Propagation propagation() default Propagation.REQUIRED;
}
