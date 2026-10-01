package com.rewit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Ponto de entrada principal da aplicação backend da plataforma Rewit.
 * Inicializa o contexto do Spring Boot, auto-configuração e listeners de ciclo de vida.
 *
 * <p>{@code @EnableScheduling} vive exclusivamente aqui (localização central,
 * Step 27.2): hoje habilita o poller do dispatcher do outbox; futuros
 * agendadores reutilizam esta única habilitação.
 */
@EnableScheduling
@SpringBootApplication
public class RewitApplication {

    public static void main(String[] args) {
        SpringApplication.run(RewitApplication.class, args);
    }
}
