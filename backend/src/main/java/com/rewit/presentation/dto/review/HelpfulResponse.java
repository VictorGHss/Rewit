package com.rewit.presentation.dto.review;

/**
 * Resposta indicando o resultado de uma operação de Helpful em uma avaliação (Step 16.0).
 */
public record HelpfulResponse(
        boolean helpful,
        long helpfulCount
) {}
