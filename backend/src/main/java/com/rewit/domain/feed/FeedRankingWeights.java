package com.rewit.domain.feed;

/**
 * Configuração imutável dos pesos do ranking do Feed V2.
 * Valores internos do algoritmo — não vêm de HTTP, banco nem Spring.
 */
public record FeedRankingWeights(
        double social,
        double recency,
        double verified,
        double helpful
) {
    static final double SUM_TOLERANCE = 1.0e-9d;

    public FeedRankingWeights {
        requireValidWeight("social", social);
        requireValidWeight("recency", recency);
        requireValidWeight("verified", verified);
        requireValidWeight("helpful", helpful);
        double sum = social + recency + verified + helpful;
        if (!Double.isFinite(sum) || Math.abs(sum - 1.0d) > SUM_TOLERANCE) {
            throw new IllegalArgumentException("ranking weights must sum to 1.0");
        }
    }

    public static FeedRankingWeights initial() {
        return new FeedRankingWeights(0.40d, 0.30d, 0.20d, 0.10d);
    }

    private static void requireValidWeight(String name, double weight) {
        if (!Double.isFinite(weight)) {
            throw new IllegalArgumentException(name + " weight must be finite");
        }
        if (weight < 0.0d) {
            throw new IllegalArgumentException(name + " weight cannot be negative");
        }
    }
}
