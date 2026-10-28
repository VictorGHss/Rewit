package com.rewit.domain.feed;

/**
 * Configuração imutável dos pesos do ranking do Feed V2 com personalização por interesses (V40).
 * Valores internos do algoritmo — não vêm de HTTP, banco nem Spring.
 */
public record FeedRankingWeights(
        double social,
        double interest,
        double recency,
        double verified,
        double helpful
) {
    static final double SUM_TOLERANCE = 1.0e-9d;

    public FeedRankingWeights {
        requireValidWeight("social", social);
        requireValidWeight("interest", interest);
        requireValidWeight("recency", recency);
        requireValidWeight("verified", verified);
        requireValidWeight("helpful", helpful);
        double sum = social + interest + recency + verified + helpful;
        if (!Double.isFinite(sum) || Math.abs(sum - 1.0d) > SUM_TOLERANCE) {
            throw new IllegalArgumentException("ranking weights must sum to 1.0");
        }
    }

    /**
     * Pesos de produção do Feed V2 personalizado por interesses (V40):
     * Conexão social direta: 0,35
     * Afinidade por interesses: 0,20
     * Recência temporal: 0,25
     * Verificação presencial no local: 0,10
     * Votos úteis da comunidade: 0,10
     */
    public static FeedRankingWeights initial() {
        return new FeedRankingWeights(0.35d, 0.20d, 0.25d, 0.10d, 0.10d);
    }

    /**
     * Construtor de conveniência/compatibilidade sem peso de interesses (afinidade zero).
     */
    public FeedRankingWeights(double social, double recency, double verified, double helpful) {
        this(social, 0.0d, recency, verified, helpful);
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
