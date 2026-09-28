package com.rewit.presentation.dto.social;

/**
 * Resposta indicando o estado do relacionamento de follow entre o usuário autenticado e o usuário consultado.
 */
public record FollowStatusResponse(
        boolean following
) {}
