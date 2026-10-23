package com.rewit.infrastructure.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuração de segurança e higienização de métricas Micrometer / Prometheus (ADR-012).
 * Garante que métricas do sistema de arquivos (especificamente disk.free e disk.total) não exponham caminhos
 * absolutos do host ou identificadores de alta cardinalidade contidos em diretórios de execução.
 */
@Configuration
public class ObservabilityConfiguration {

    @Bean
    public MeterFilter diskPathSanitizingMeterFilter() {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                String name = id.getName();
                if ("disk.free".equals(name) || "disk.total".equals(name)) {
                    List<Tag> sanitizedTags = new ArrayList<>();
                    for (Tag tag : id.getTagsAsIterable()) {
                        if ("path".equals(tag.getKey())) {
                            sanitizedTags.add(Tag.of("path", "/"));
                        } else {
                            sanitizedTags.add(tag);
                        }
                    }
                    return id.replaceTags(sanitizedTags);
                }
                return id;
            }
        };
    }
}
