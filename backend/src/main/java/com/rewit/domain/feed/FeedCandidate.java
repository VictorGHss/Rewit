package com.rewit.domain.feed;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Representação interna e factual de um candidato ao Feed V2.
 *
 * <p>Contém somente os sinais necessários ao ranking e à diversidade.
 * Não é DTO HTTP: não expõe coordenadas, perfil, reputação nem identidade pública.
 * {@code authorId} existe apenas para regras internas (diversidade), inclusive em reviews anônimas.
 */
public record FeedCandidate(
        UUID reviewId,
        UUID authorId,
        UUID targetId,
        Instant createdAt,
        boolean isVerifiedOnSite,
        int helpfulCount,
        boolean isDirectFollow
) {
    public FeedCandidate {
        Objects.requireNonNull(reviewId, "reviewId is required");
        Objects.requireNonNull(authorId, "authorId is required");
        Objects.requireNonNull(targetId, "targetId is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        if (helpfulCount < 0) {
            throw new IllegalArgumentException("helpfulCount cannot be negative");
        }
    }
}
