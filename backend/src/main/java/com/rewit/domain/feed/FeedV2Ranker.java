package com.rewit.domain.feed;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Ranker puro e determinístico do Feed V2.
 * Não consulta relógio do sistema, banco, grafo social nem HTTP.
 */
public final class FeedV2Ranker {

    public static final Duration RECENCY_WINDOW = Duration.ofDays(14);
    public static final int HELPFUL_CAP = 20;

    private static final Comparator<RankedFeedCandidate> ORDER = Comparator
            .comparing(RankedFeedCandidate::score)
            .reversed()
            .thenComparing(ranked -> ranked.candidate().createdAt(), Comparator.reverseOrder())
            .thenComparing(ranked -> ranked.candidate().reviewId());

    private final FeedRankingWeights weights;

    public FeedV2Ranker() {
        this(FeedRankingWeights.initial());
    }

    public FeedV2Ranker(FeedRankingWeights weights) {
        this.weights = Objects.requireNonNull(weights, "weights is required");
    }

    public List<RankedFeedCandidate> rank(List<FeedCandidate> candidates, Instant referenceTime) {
        Objects.requireNonNull(candidates, "candidates is required");
        Objects.requireNonNull(referenceTime, "referenceTime is required");

        List<RankedFeedCandidate> ranked = new ArrayList<>(candidates.size());
        for (FeedCandidate candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate is required");
            ranked.add(new RankedFeedCandidate(candidate, score(candidate, referenceTime)));
        }
        ranked.sort(ORDER);
        return List.copyOf(ranked);
    }

    FeedScore score(FeedCandidate candidate, Instant referenceTime) {
        double social = candidate.isDirectFollow() ? 1.0d : 0.0d;
        double recency = recencySignal(candidate.createdAt(), referenceTime);
        double verified = candidate.isVerifiedOnSite() ? 1.0d : 0.0d;
        double helpful = helpfulSignal(candidate.helpfulCount());

        double raw = (weights.social() * social)
                + (weights.recency() * recency)
                + (weights.verified() * verified)
                + (weights.helpful() * helpful);
        return new FeedScore(clampUnit(raw));
    }

    static double recencySignal(Instant createdAt, Instant referenceTime) {
        if (createdAt.isAfter(referenceTime)) {
            return 1.0d;
        }
        double windowMillis = RECENCY_WINDOW.toMillis();
        double deltaMillis = millisBetween(createdAt, referenceTime);
        if (deltaMillis <= 0.0d) {
            return 1.0d;
        }
        return clampUnit(1.0d - (deltaMillis / windowMillis));
    }

    static double helpfulSignal(int helpfulCount) {
        if (helpfulCount >= HELPFUL_CAP) {
            return 1.0d;
        }
        double numerator = Math.log10(1.0d + helpfulCount);
        double denominator = Math.log10(1.0d + HELPFUL_CAP);
        return clampUnit(numerator / denominator);
    }

    private static double millisBetween(Instant start, Instant end) {
        try {
            return (double) Duration.between(start, end).toMillis();
        } catch (ArithmeticException overflow) {
            return Double.MAX_VALUE;
        }
    }

    private static double clampUnit(double value) {
        if (!Double.isFinite(value) || value <= 0.0d) {
            return 0.0d;
        }
        if (value >= 1.0d) {
            return 1.0d;
        }
        return value;
    }
}
