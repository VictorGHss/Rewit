package com.rewit.domain.feed;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Diversificador puro do Feed V2.
 * Reorganiza candidatos já rankeados sem recalcular score e sem descartar reviews.
 */
public final class FeedV2Diversifier {

    static final int MAX_CONSECUTIVE = 2;

    public List<RankedFeedCandidate> diversify(List<RankedFeedCandidate> ranked) {
        Objects.requireNonNull(ranked, "ranked is required");
        if (ranked.size() <= MAX_CONSECUTIVE) {
            validate(ranked);
            return List.copyOf(ranked);
        }

        List<RankedFeedCandidate> remaining = new ArrayList<>(ranked.size());
        for (RankedFeedCandidate item : ranked) {
            remaining.add(requireItem(item));
        }

        List<RankedFeedCandidate> result = new ArrayList<>(ranked.size());
        while (!remaining.isEmpty()) {
            int pick = indexOfFirstValid(result, remaining);
            if (pick < 0) {
                pick = 0;
            }
            result.add(remaining.remove(pick));
        }
        return List.copyOf(result);
    }

    private static int indexOfFirstValid(List<RankedFeedCandidate> result, List<RankedFeedCandidate> remaining) {
        for (int i = 0; i < remaining.size(); i++) {
            if (fits(result, remaining.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean fits(List<RankedFeedCandidate> result, RankedFeedCandidate next) {
        return !breaksRun(result, next, true) && !breaksRun(result, next, false);
    }

    private static boolean breaksRun(List<RankedFeedCandidate> result, RankedFeedCandidate next, boolean author) {
        if (result.size() < MAX_CONSECUTIVE) {
            return false;
        }
        UUID identity = identity(next, author);
        for (int offset = 1; offset <= MAX_CONSECUTIVE; offset++) {
            RankedFeedCandidate previous = result.get(result.size() - offset);
            if (!identity(previous, author).equals(identity)) {
                return false;
            }
        }
        return true;
    }

    private static UUID identity(RankedFeedCandidate ranked, boolean author) {
        FeedCandidate candidate = ranked.candidate();
        return author ? candidate.authorId() : candidate.targetId();
    }

    private static void validate(List<RankedFeedCandidate> ranked) {
        for (RankedFeedCandidate item : ranked) {
            requireItem(item);
        }
    }

    private static RankedFeedCandidate requireItem(RankedFeedCandidate item) {
        return Objects.requireNonNull(item, "ranked candidate is required");
    }
}
