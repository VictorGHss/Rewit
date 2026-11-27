package com.rewit.infrastructure.persistence.routing;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * {@link AbstractRoutingDataSource} que direciona conexões entre {@code rewit_app} (padrão),
 * {@code rewit_media} (ciclo de vida público/automático de mídias de locais, anotado com
 * {@link MediaLifecycleTransactional}) e {@code rewit_mod} (exclusivo dos seis fluxos administrativos
 * anotados com {@link ModerationTransactional}).
 */
public class ModerationRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        return DatabaseRoleContext.current();
    }
}
