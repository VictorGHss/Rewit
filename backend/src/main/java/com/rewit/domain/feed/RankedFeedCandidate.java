package com.rewit.domain.feed;

import java.util.Objects;

/**
 * Candidato interno já pontuado pelo {@link FeedV2Ranker}.
 * Não hidrata review pública e não é resposta HTTP.
 */
public record RankedFeedCandidate(
        FeedCandidate candidate,
        FeedScore score
) {
    public RankedFeedCandidate {
        Objects.requireNonNull(candidate, "candidate is required");
        Objects.requireNonNull(score, "score is required");
    }
}
