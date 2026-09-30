package com.rewit.domain.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários: FeedV2Diversifier (Step 24.3.1)")
class FeedV2DiversifierUnitTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private static final UUID AUTHOR_A = id("a");
    private static final UUID AUTHOR_B = id("b");
    private static final UUID AUTHOR_C = id("c");
    private static final UUID TARGET_X = id("d");
    private static final UUID TARGET_Y = id("e");
    private static final UUID TARGET_Z = id("f");

    private final FeedV2Diversifier diversifier = new FeedV2Diversifier();

    @Nested
    @DisplayName("Autor")
    class Author {

        @Test
        @DisplayName("A A A B vira A A B A quando possível")
        void threeAThenB() {
            List<RankedFeedCandidate> input = List.of(
                    item("1", AUTHOR_A, TARGET_X),
                    item("2", AUTHOR_A, TARGET_Y),
                    item("3", AUTHOR_A, TARGET_Z),
                    item("4", AUTHOR_B, TARGET_X)
            );
            assertEquals(List.of(id("1"), id("2"), id("4"), id("3")), reviewIds(diversifier.diversify(input)));
        }

        @Test
        @DisplayName("A A B B já válido permanece")
        void twoAThenTwoB() {
            List<RankedFeedCandidate> input = List.of(
                    item("1", AUTHOR_A, TARGET_X),
                    item("2", AUTHOR_A, TARGET_Y),
                    item("3", AUTHOR_B, TARGET_X),
                    item("4", AUTHOR_B, TARGET_Y)
            );
            assertEquals(reviewIds(input), reviewIds(diversifier.diversify(input)));
        }

        @Test
        @DisplayName("A B C já válido permanece")
        void abcUnchanged() {
            List<RankedFeedCandidate> input = List.of(
                    item("1", AUTHOR_A, TARGET_X),
                    item("2", AUTHOR_B, TARGET_Y),
                    item("3", AUTHOR_C, TARGET_Z)
            );
            assertEquals(reviewIds(input), reviewIds(diversifier.diversify(input)));
        }

        @Test
        @DisplayName("Todos A são preservados na ordem original")
        void allSameAuthorPreserved() {
            List<RankedFeedCandidate> input = List.of(
                    item("1", AUTHOR_A, TARGET_X),
                    item("2", AUTHOR_A, TARGET_Y),
                    item("3", AUTHOR_A, TARGET_Z)
            );
            assertEquals(reviewIds(input), reviewIds(diversifier.diversify(input)));
        }

        @Test
        @DisplayName("Alternância já válida permanece")
        void alreadyAlternating() {
            List<RankedFeedCandidate> input = List.of(
                    item("1", AUTHOR_A, TARGET_X),
                    item("2", AUTHOR_B, TARGET_Y),
                    item("3", AUTHOR_A, TARGET_Z),
                    item("4", AUTHOR_B, TARGET_X)
            );
            assertEquals(reviewIds(input), reviewIds(diversifier.diversify(input)));
        }
    }

    @Nested
    @DisplayName("Target")
    class Target {

        @Test
        @DisplayName("X X X Y vira X X Y X quando possível")
        void threeXThenY() {
            List<RankedFeedCandidate> input = List.of(
                    item("1", AUTHOR_A, TARGET_X),
                    item("2", AUTHOR_B, TARGET_X),
                    item("3", AUTHOR_C, TARGET_X),
                    item("4", AUTHOR_A, TARGET_Y)
            );
            assertEquals(List.of(id("1"), id("2"), id("4"), id("3")), reviewIds(diversifier.diversify(input)));
        }

        @Test
        @DisplayName("X X Y Y já válido permanece")
        void twoXThenTwoY() {
            List<RankedFeedCandidate> input = List.of(
                    item("1", AUTHOR_A, TARGET_X),
                    item("2", AUTHOR_B, TARGET_X),
                    item("3", AUTHOR_C, TARGET_Y),
                    item("4", AUTHOR_A, TARGET_Y)
            );
            assertEquals(reviewIds(input), reviewIds(diversifier.diversify(input)));
        }

        @Test
        @DisplayName("Todos X são preservados na ordem original")
        void allSameTargetPreserved() {
            List<RankedFeedCandidate> input = List.of(
                    item("1", AUTHOR_A, TARGET_X),
                    item("2", AUTHOR_B, TARGET_X),
                    item("3", AUTHOR_C, TARGET_X)
            );
            assertEquals(reviewIds(input), reviewIds(diversifier.diversify(input)));
        }
    }

    @Nested
    @DisplayName("Combinação")
    class Combination {

        @Test
        @DisplayName("Mesmo autor e mesmo target: preserva quando não há alternativa")
        void sameAuthorAndTarget() {
            List<RankedFeedCandidate> input = List.of(
                    item("1", AUTHOR_A, TARGET_X),
                    item("2", AUTHOR_A, TARGET_X),
                    item("3", AUTHOR_A, TARGET_X)
            );
            assertEquals(reviewIds(input), reviewIds(diversifier.diversify(input)));
        }

        @Test
        @DisplayName("Autores diferentes com o mesmo target respeitam o limite de target")
        void differentAuthorsSameTarget() {
            List<RankedFeedCandidate> input = List.of(
                    item("1", AUTHOR_A, TARGET_X),
                    item("2", AUTHOR_B, TARGET_X),
                    item("3", AUTHOR_C, TARGET_X),
                    item("4", AUTHOR_A, TARGET_Y)
            );
            assertEquals(List.of(id("1"), id("2"), id("4"), id("3")), reviewIds(diversifier.diversify(input)));
        }

        @Test
        @DisplayName("Mesmo autor com targets diferentes respeita o limite de autor")
        void sameAuthorDifferentTargets() {
            List<RankedFeedCandidate> input = List.of(
                    item("1", AUTHOR_A, TARGET_X),
                    item("2", AUTHOR_A, TARGET_Y),
                    item("3", AUTHOR_A, TARGET_Z),
                    item("4", AUTHOR_B, TARGET_X)
            );
            assertEquals(List.of(id("1"), id("2"), id("4"), id("3")), reviewIds(diversifier.diversify(input)));
        }

        @Test
        @DisplayName("As duas restrições são aplicadas em conjunto")
        void bothConstraintsTogether() {
            List<RankedFeedCandidate> input = List.of(
                    item("1", AUTHOR_A, TARGET_X),
                    item("2", AUTHOR_A, TARGET_X),
                    item("3", AUTHOR_A, TARGET_X),
                    item("4", AUTHOR_B, TARGET_Y)
            );
            assertEquals(List.of(id("1"), id("2"), id("4"), id("3")), reviewIds(diversifier.diversify(input)));
        }
    }

    @Nested
    @DisplayName("Preservação e determinismo")
    class Preservation {

        @Test
        @DisplayName("Antes e depois contêm exatamente o mesmo conjunto de reviewId")
        void preservesAllReviewIds() {
            List<RankedFeedCandidate> input = List.of(
                    item("1", AUTHOR_A, TARGET_X),
                    item("2", AUTHOR_A, TARGET_X),
                    item("3", AUTHOR_A, TARGET_Y),
                    item("4", AUTHOR_B, TARGET_X),
                    item("5", AUTHOR_C, TARGET_Y)
            );
            List<RankedFeedCandidate> output = diversifier.diversify(input);
            assertEquals(input.size(), output.size());
            assertEquals(reviewIdSet(input), reviewIdSet(output));
        }

        @Test
        @DisplayName("Não descarta candidatos mesmo quando a diversidade é impossível")
        void neverDropsCandidates() {
            List<RankedFeedCandidate> input = List.of(
                    item("1", AUTHOR_A, TARGET_X),
                    item("2", AUTHOR_A, TARGET_X),
                    item("3", AUTHOR_A, TARGET_X),
                    item("4", AUTHOR_A, TARGET_X)
            );
            List<RankedFeedCandidate> output = diversifier.diversify(input);
            assertEquals(4, output.size());
            assertEquals(reviewIdSet(input), reviewIdSet(output));
        }

        @Test
        @DisplayName("Mesma entrada produz a mesma saída")
        void deterministic() {
            List<RankedFeedCandidate> input = List.of(
                    item("1", AUTHOR_A, TARGET_X),
                    item("2", AUTHOR_A, TARGET_Y),
                    item("3", AUTHOR_A, TARGET_Z),
                    item("4", AUTHOR_B, TARGET_X)
            );
            assertEquals(
                    reviewIds(diversifier.diversify(input)),
                    reviewIds(diversifier.diversify(List.copyOf(input)))
            );
        }

        @Test
        @DisplayName("Não recalcula score")
        void doesNotRecalculateScore() {
            RankedFeedCandidate first = new RankedFeedCandidate(
                    candidate("1", AUTHOR_A, TARGET_X),
                    new FeedScore(0.91d)
            );
            RankedFeedCandidate second = new RankedFeedCandidate(
                    candidate("2", AUTHOR_B, TARGET_Y),
                    new FeedScore(0.42d)
            );
            List<RankedFeedCandidate> output = diversifier.diversify(List.of(first, second));
            assertEquals(0.91d, output.get(0).score().value());
            assertEquals(0.42d, output.get(1).score().value());
            assertSame(first.score(), output.get(0).score());
        }
    }

    private static Set<UUID> reviewIdSet(List<RankedFeedCandidate> items) {
        return items.stream()
                .map(item -> item.candidate().reviewId())
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static List<UUID> reviewIds(List<RankedFeedCandidate> items) {
        return items.stream().map(item -> item.candidate().reviewId()).toList();
    }

    private static RankedFeedCandidate item(String reviewKey, UUID authorId, UUID targetId) {
        return new RankedFeedCandidate(candidate(reviewKey, authorId, targetId), new FeedScore(0.5d));
    }

    private static FeedCandidate candidate(String reviewKey, UUID authorId, UUID targetId) {
        return new FeedCandidate(id(reviewKey), authorId, targetId, NOW, false, 0, false);
    }

    private static UUID id(String key) {
        return UUID.fromString("00000000-0000-0000-0000-00000000000" + key);
    }
}
