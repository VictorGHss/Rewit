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
 * Marca os métodos transacionais do ciclo de vida público/automático de mídias de locais para
 * executarem sob a identidade PostgreSQL {@code rewit_media} (SEC-F2-03).
 *
 * <p>Interceptado por {@link MediaLifecycleRoutingMethodInterceptor} com precedência estritamente anterior ao
 * {@code TransactionInterceptor} do Spring, garantindo que o contexto {@link DatabaseRole#MEDIA_LIFECYCLE} esteja
 * ativo antes da aquisição da conexão física e falhando de forma fechada se já houver uma transação ativa
 * aberta sob outra role ({@code rewit_app} ou {@code rewit_mod}).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Transactional
public @interface MediaLifecycleTransactional {

    @AliasFor(annotation = Transactional.class, attribute = "propagation")
    Propagation propagation() default Propagation.REQUIRED;
}
