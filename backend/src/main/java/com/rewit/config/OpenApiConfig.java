package com.rewit.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração da documentação OpenAPI 3 / Swagger da API do Rewit.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Rewit Platform API")
                        .version("0.1.0")
                        .description("API RESTful central da rede social geográfica de avaliações Rewit.")
                        .contact(new Contact()
                                .name("Equipe Rewit")
                                .url("https://github.com/VictorGHss/Rewit"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://rewit.app/terms")));
    }
}
