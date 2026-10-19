package com.rewit.infrastructure.observability;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração de segurança e higienização de métricas Micrometer / Prometheus (ADR-012).
 * Garante que métricas do sistema de arquivos (como disk.free e disk.total) não exponham caminhos
 * absolutos do host ou identificadores de alta cardinalidade contidos em diretórios de execução.
 */
@Configuration
public class ObservabilityConfiguration {

    @Bean
    public MeterFilter diskPathSanitizingMeterFilter() {
        return MeterFilter.replaceTagValues("path", path -> "/");
    }
}

