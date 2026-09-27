package com.rewit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ponto de entrada principal da aplicação backend da plataforma Rewit.
 * Inicializa o contexto do Spring Boot, auto-configuração e listeners de ciclo de vida.
 */
@SpringBootApplication
public class RewitApplication {

    public static void main(String[] args) {
        SpringApplication.run(RewitApplication.class, args);
    }
}
