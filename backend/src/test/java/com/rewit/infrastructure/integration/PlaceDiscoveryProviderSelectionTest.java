package com.rewit.infrastructure.integration;

import com.rewit.application.port.PlaceDiscoveryPort;
import com.rewit.application.service.PlaceDiscoveryService;
import com.rewit.infrastructure.integration.geoapify.GeoapifyPlacesAdapter;
import com.rewit.infrastructure.integration.geoapify.GeoapifyProperties;
import com.rewit.infrastructure.integration.google.GooglePlacesAdapter;
import com.rewit.infrastructure.integration.google.GooglePlacesProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seleção explícita do provedor de descoberta: um único adapter registrado por configuração, sem injeção ambígua
 * nem fallback, e o padrão de cada perfil.
 */
@DisplayName("Descoberta de locais: seleção do provedor por rewit.place-discovery.provider")
class PlaceDiscoveryProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PlaceDiscoveryProperties.class, GooglePlacesProperties.class, GeoapifyProperties.class,
                    GooglePlacesAdapter.class, GeoapifyPlacesAdapter.class, PlaceDiscoveryService.class);

    @Test
    @DisplayName("Sem propriedade: só o Google é registrado (comportamento anterior)")
    void defaultsToGoogle() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PlaceDiscoveryPort.class);
            assertThat(context).hasSingleBean(GooglePlacesAdapter.class);
            assertThat(context).doesNotHaveBean(GeoapifyPlacesAdapter.class);
            assertThat(context.getBean(PlaceDiscoveryService.class).getActiveProvider()).isEqualTo("GOOGLE");
        });
    }

    @ParameterizedTest
    @CsvSource({"GEOAPIFY,GEOAPIFY", "geoapify,GEOAPIFY", "GOOGLE,GOOGLE", "google,GOOGLE"})
    @DisplayName("Valor configurado registra exatamente um adapter, sem diferenciar maiúsculas")
    void selectsConfiguredProvider(String configured, String expected) {
        runner.withPropertyValues(PlaceDiscoveryProperties.PROPERTY + "=" + configured).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PlaceDiscoveryPort.class);
            assertThat(context.getBean(PlaceDiscoveryPort.class).getProviderName()).isEqualTo(expected);
            assertThat(context.getBean(PlaceDiscoveryService.class).getActiveProvider()).isEqualTo(expected);
        });
    }

    @Test
    @DisplayName("Geoapify sem chave sobe normalmente: o erro controlado aparece só na chamada")
    void geoapifyWithoutKeyStillStarts() {
        runner.withPropertyValues(PlaceDiscoveryProperties.PROPERTY + "=GEOAPIFY", "rewit.geoapify.api-key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GeoapifyProperties.class).isConfigured()).isFalse();
                });
    }

    @Test
    @DisplayName("Provedor desconhecido falha na inicialização com erro de binding da propriedade")
    void unknownProviderFailsFast() {
        runner.withPropertyValues(PlaceDiscoveryProperties.PROPERTY + "=BING").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("rewit.place-discovery");
        });
    }

    @ParameterizedTest
    @CsvSource({"application.yml,GOOGLE", "application-local.yml,GOOGLE", "application-external-test.yml,GEOAPIFY"})
    @DisplayName("Padrão por perfil: Google em application e local; Geoapify no external-test")
    void profileDefaults(String file, String expected) throws IOException {
        // Os arquivos de produção: no classpath de teste, application.yml e application-local.yml são sombreados
        PropertySource<?> source = new YamlPropertySourceLoader()
                .load(file, new FileSystemResource("src/main/resources/" + file)).getFirst();
        assertThat(String.valueOf(source.getProperty(PlaceDiscoveryProperties.PROPERTY)))
                .isEqualTo("${PLACE_DISCOVERY_PROVIDER:" + expected + "}");
        assertThat(String.valueOf(source.getProperty("rewit.geoapify.api-key"))).isEqualTo("${GEOAPIFY_API_KEY:}");
    }
}
