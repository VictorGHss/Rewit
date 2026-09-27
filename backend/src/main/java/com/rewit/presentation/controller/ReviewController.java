package com.rewit.presentation.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller REST reservado para operações com publicações de avaliação.
 * Endpoints funcionais de mutação serão expostos nos prompts de negócio dedicados.
 */
@RestController
@RequestMapping("/api/v1/reviews")
@Tag(name = "Reviews", description = "Operações com publicações de avaliação multi-alvo (Scaffold)")
public class ReviewController {

    // Endpoints funcionais de escrita e consulta serão implementados nos prompts dedicados
}
