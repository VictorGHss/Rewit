package com.rewit.domain.feed;

/**
 * Value Object imutável do score do Feed V2.
 * Sempre finito, sem null, e no intervalo fechado {@code [0.0, 1.0]}.
 */
public record FeedScore(double value) implements Comparable<FeedScore> {

    public FeedScore {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("score must be finite");
        }
        if (value < 0.0d || value > 1.0d) {
            throw new IllegalArgumentException("score must be between 0.0 and 1.0");
        }
    }

    @Override
    public int compareTo(FeedScore other) {
        return Double.compare(this.value, other.value);
    }
}
