package com.rewit.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Item individual classificado em um Ranking V1.
 *
 * @param rankPosition posição no ranking computada via DENSE_RANK() (empate real)
 * @param userId identificador imutável da conta
 * @param handle nome público de usuário (@handle)
 * @param displayName nome de exibição no perfil
 * @param avatarUrl URL da foto de perfil (opcional)
 * @param score pontuação acumulada na métrica específica do período
 * @param lastContributedAt timestamp do evento mais recente considerado na agregação
 */
public record RankingItem(
        int rankPosition,
        UUID userId,
        String handle,
        String displayName,
        String avatarUrl,
        long score,
        Instant lastContributedAt
) {
    public RankingItem {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(handle, "handle must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
    }
}

