package com.rewit.domain.feed;

import com.rewit.domain.enums.InterestCategory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Representação interna e factual de um candidato ao Feed V2.
 *
 * <p>Contém somente os sinais necessários ao ranking e à diversidade.
 * Não é DTO HTTP: não expõe coordenadas, perfil, reputação nem identidade pública.
 * {@code authorId} existe apenas para regras internas (diversidade); em review anônima é uma chave própria da
 * review, nunca o autor real, para que a ordem do feed não ligue a anônima às reviews identificadas do autor.
 * {@code category} representa a categoria canônica do alvo principal para fins de afinidade no ranking (V40).
 */
public record FeedCandidate(
        UUID reviewId,
        UUID authorId,
        UUID targetId,
        InterestCategory category,
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

    /**
     * Construtor de conveniência para compatibilidade com instâncias sem categoria mapeada.
     */
    public FeedCandidate(
            UUID reviewId,
            UUID authorId,
            UUID targetId,
            Instant createdAt,
            boolean isVerifiedOnSite,
            int helpfulCount,
            boolean isDirectFollow
    ) {
        this(reviewId, authorId, targetId, null, createdAt, isVerifiedOnSite, helpfulCount, isDirectFollow);
    }
}
