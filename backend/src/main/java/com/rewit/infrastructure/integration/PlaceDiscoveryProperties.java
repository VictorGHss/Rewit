package com.rewit.infrastructure.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Seleção explícita do provedor de descoberta externa de locais ({@code rewit.place-discovery.provider}).
 *
 * <p>Só o adapter do provedor escolhido é registrado (ver {@code @ConditionalOnProperty} nos adapters): não há
 * fallback entre provedores. Valor fora do enum falha na inicialização, no binding desta propriedade. Sem valor,
 * vale {@link Provider#GOOGLE}, o comportamento anterior.
 */
@Component
@ConfigurationProperties(prefix = "rewit.place-discovery")
public class PlaceDiscoveryProperties {

    public static final String PROPERTY = "rewit.place-discovery.provider";

    public enum Provider {
        GOOGLE,
        GEOAPIFY
    }

    private Provider provider = Provider.GOOGLE;

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider != null ? provider : Provider.GOOGLE;
    }
}
