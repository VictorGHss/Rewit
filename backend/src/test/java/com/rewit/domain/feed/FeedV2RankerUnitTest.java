package com.rewit.domain.feed;

import com.rewit.domain.enums.InterestCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários: FeedV2Ranker com Afinidade por Interesses (Step 24.3.1 + V40)")
class FeedV2RankerUnitTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private static final double EPS = 1.0e-12d;

    private final FeedV2Ranker ranker = new FeedV2Ranker();

    @Nested
    @DisplayName("Pesos")
    class Weights {

        @Test
        @DisplayName("Combinação inicial soma 1.0 e é aceita")
        void initialWeightsAreValid() {
            FeedRankingWeights weights = FeedRankingWeights.initial();
            assertEquals(0.35d, weights.social(), EPS);
            assertEquals(0.20d, weights.interest(), EPS);
            assertEquals(0.25d, weights.recency(), EPS);
            assertEquals(0.10d, weights.verified(), EPS);
            assertEquals(0.10d, weights.helpful(), EPS);
            assertDoesNotThrow(() -> new FeedV2Ranker(weights));
        }

        @Test
        @DisplayName("Peso zero é permitido quando a soma permanece 1.0")
        void zeroWeightIsAllowed() {
            FeedRankingWeights onlyRecency = new FeedRankingWeights(0.0d, 0.0d, 1.0d, 0.0d, 0.0d);
            FeedV2Ranker recencyOnly = new FeedV2Ranker(onlyRecency);

            FeedCandidate recent = candidate("00000000-0000-0000-0000-00000000000a", NOW, false, 0, false);
            FeedCandidate older = candidate("00000000-0000-0000-0000-00000000000b", NOW.minus(Duration.ofDays(7)), true, 20, true);

            List<RankedFeedCandidate> ranked = recencyOnly.rank(List.of(older, recent), NOW);
            assertEquals(recent.reviewId(), ranked.get(0).candidate().reviewId());
            assertEquals(1.0d, ranked.get(0).score().value(), EPS);
            assertEquals(0.5d, ranked.get(1).score().value(), EPS);
        }

        @Test
        @DisplayName("Soma inválida é rejeitada")
        void invalidSumIsRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new FeedRankingWeights(0.3d, 0.3d, 0.3d, 0.3d, 0.3d));
            assertThrows(IllegalArgumentException.class,
                    () -> new FeedRankingWeights(0.2d, 0.2d, 0.2d, 0.2d, 0.1d));
        }

        @Test
        @DisplayName("Peso negativo é rejeitado")
        void negativeWeightIsRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new FeedRankingWeights(-0.10d, 0.20d, 0.50d, 0.20d, 0.20d));
        }

        @Test
        @DisplayName("Peso não finito é rejeitado")
        void nonFiniteWeightIsRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new FeedRankingWeights(Double.NaN, 0.2d, 0.5d, 0.2d, 0.1d));
            assertThrows(IllegalArgumentException.class,
                    () -> new FeedRankingWeights(Double.POSITIVE_INFINITY, 0.0d, 0.0d, 0.0d, 0.0d));
        }
    }

    @Nested
    @DisplayName("Afinidade por Interesses")
    class Affinity {

        @Test
        @DisplayName("Candidato com categoria nos interesses do usuário recebe sinal 1.0")
        void matchingInterestYieldsFullSignal() {
            FeedCandidate candidate = candidateWithCategory("00000000-0000-0000-0000-000000000051", InterestCategory.RESTAURANT);
            Set<InterestCategory> userInterests = Set.of(InterestCategory.RESTAURANT, InterestCategory.CAFE);

            assertEquals(1.0d, FeedV2Ranker.interestSignal(candidate.category(), userInterests), EPS);
            assertEquals(0.20d * 1.0d, ranker.weights().interest() * FeedV2Ranker.interestSignal(candidate.category(), userInterests), EPS);
        }

        @Test
        @DisplayName("Candidato com categoria fora dos interesses do usuário recebe sinal 0.0")
        void nonMatchingInterestYieldsZeroSignal() {
            FeedCandidate candidate = candidateWithCategory("00000000-0000-0000-0000-000000000052", InterestCategory.TECHNOLOGY);
            Set<InterestCategory> userInterests = Set.of(InterestCategory.RESTAURANT, InterestCategory.CAFE);

            assertEquals(0.0d, FeedV2Ranker.interestSignal(candidate.category(), userInterests), EPS);
        }

        @Test
        @DisplayName("Candidato sem categoria (null) recebe sinal 0.0 (afinidade neutra)")
        void nullCategoryYieldsZeroSignal() {
            FeedCandidate candidate = candidate("00000000-0000-0000-0000-000000000053", NOW, false, 0, false);
            Set<InterestCategory> userInterests = Set.of(InterestCategory.RESTAURANT);

            assertNull(candidate.category());
            assertEquals(0.0d, FeedV2Ranker.interestSignal(candidate.category(), userInterests), EPS);
        }

        @Test
        @DisplayName("Usuário sem interesses (vazio ou nulo) recebe sinal 0.0 sem penalização")
        void emptyUserInterestsYieldsZeroSignal() {
            FeedCandidate candidate = candidateWithCategory("00000000-0000-0000-0000-000000000054", InterestCategory.CAFE);

            assertEquals(0.0d, FeedV2Ranker.interestSignal(candidate.category(), Set.of()), EPS);
            assertEquals(0.0d, FeedV2Ranker.interestSignal(candidate.category(), null), EPS);
        }
    }

    @Nested
    @DisplayName("Recência")
    class Recency {

        @Test
        @DisplayName("Exatamente agora → 1.0")
        void nowIsOne() {
            assertEquals(1.0d, FeedV2Ranker.recencySignal(NOW, NOW), EPS);
        }

        @Test
        @DisplayName("Metade da janela → 0.5")
        void halfWindowIsHalf() {
            Instant created = NOW.minus(FeedV2Ranker.RECENCY_WINDOW.dividedBy(2));
            assertEquals(0.5d, FeedV2Ranker.recencySignal(created, NOW), EPS);
        }

        @Test
        @DisplayName("Exatamente no limite da janela → 0.0")
        void exactlyAtWindowIsZero() {
            Instant created = NOW.minus(FeedV2Ranker.RECENCY_WINDOW);
            assertEquals(0.0d, FeedV2Ranker.recencySignal(created, NOW), EPS);
        }

        @Test
        @DisplayName("Além da janela → 0.0")
        void beyondWindowIsZero() {
            Instant created = NOW.minus(FeedV2Ranker.RECENCY_WINDOW).minus(Duration.ofDays(3));
            assertEquals(0.0d, FeedV2Ranker.recencySignal(created, NOW), EPS);
        }

        @Test
        @DisplayName("Timestamp futuro não gera score absurdo e permanece em 1.0")
        void futureTimestampIsClamped() {
            Instant future = NOW.plus(Duration.ofDays(30));
            assertEquals(1.0d, FeedV2Ranker.recencySignal(future, NOW), EPS);
            assertTrue(FeedV2Ranker.recencySignal(future, NOW) <= 1.0d);
        }
    }

    @Nested
    @DisplayName("Helpful")
    class Helpful {

        @Test
        @DisplayName("0 → 0.0")
        void zeroHelpful() {
            assertEquals(0.0d, FeedV2Ranker.helpfulSignal(0), EPS);
        }

        @Test
        @DisplayName("1 usa saturação logarítmica")
        void oneHelpful() {
            double expected = Math.log10(2.0d) / Math.log10(21.0d);
            assertEquals(expected, FeedV2Ranker.helpfulSignal(1), EPS);
        }

        @Test
        @DisplayName("20 satura em 1.0")
        void capHelpful() {
            assertEquals(1.0d, FeedV2Ranker.helpfulSignal(20), EPS);
        }

        @Test
        @DisplayName("> 20 permanece em 1.0")
        void aboveCapHelpful() {
            assertEquals(1.0d, FeedV2Ranker.helpfulSignal(21), EPS);
            assertEquals(1.0d, FeedV2Ranker.helpfulSignal(10_000), EPS);
        }

        @Test
        @DisplayName("Entrada negativa é rejeitada no candidato")
        void negativeHelpfulRejected() {
            assertThrows(IllegalArgumentException.class, () ->
                    new FeedCandidate(id("1"), id("a"), id("c"), null, NOW, false, -1, false));
        }
    }

    @Nested
    @DisplayName("Verified")
    class Verified {

        @Test
        @DisplayName("true → 1.0 e false → 0.0 no sinal ponderado")
        void verifiedSignal() {
            FeedCandidate verified = candidate("00000000-0000-0000-0000-000000000011", NOW.minus(FeedV2Ranker.RECENCY_WINDOW), true, 0, false);
            FeedCandidate unverified = candidate("00000000-0000-0000-0000-000000000012", NOW.minus(FeedV2Ranker.RECENCY_WINDOW), false, 0, false);

            assertEquals(0.10d, ranker.score(verified, NOW).value(), EPS);
            assertEquals(0.00d, ranker.score(unverified, NOW).value(), EPS);
        }
    }

    @Nested
    @DisplayName("Social")
    class Social {

        @Test
        @DisplayName("true → 1.0 e false → 0.0 no sinal ponderado")
        void socialSignal() {
            FeedCandidate follow = candidate("00000000-0000-0000-0000-000000000021", NOW.minus(FeedV2Ranker.RECENCY_WINDOW), false, 0, true);
            FeedCandidate other = candidate("00000000-0000-0000-0000-000000000022", NOW.minus(FeedV2Ranker.RECENCY_WINDOW), false, 0, false);

            assertEquals(0.35d, ranker.score(follow, NOW).value(), EPS);
            assertEquals(0.00d, ranker.score(other, NOW).value(), EPS);
        }
    }

    @Nested
    @DisplayName("Score")
    class Score {

        @Test
        @DisplayName("Combinação conhecida produz o resultado esperado com e sem afinidade")
        void knownCombination() {
            Instant created = NOW.minus(Duration.ofDays(7));
            FeedCandidate candidate = new FeedCandidate(
                    id("00000000-0000-0000-0000-000000000031"),
                    id("00000000-0000-0000-0000-0000000000aa"),
                    id("00000000-0000-0000-0000-0000000000dd"),
                    InterestCategory.RESTAURANT,
                    created,
                    true,
                    20,
                    true
            );

            // Sem afinidade (ou usuário sem interesses):
            // 0.35 * 1.0 (social) + 0.20 * 0.0 (interest) + 0.25 * 0.5 (recency) + 0.10 * 1.0 (verified) + 0.10 * 1.0 (helpful)
            double expectedWithoutInterest = 0.35d * 1.0d + 0.20d * 0.0d + 0.25d * 0.5d + 0.10d * 1.0d + 0.10d * 1.0d;
            assertEquals(expectedWithoutInterest, ranker.score(candidate, NOW).value(), EPS);
            assertEquals(0.675d, expectedWithoutInterest, EPS);

            // Com afinidade (usuário com interesse em RESTAURANT):
            // 0.675 + 0.20 * 1.0 = 0.875
            double expectedWithInterest = expectedWithoutInterest + 0.20d * 1.0d;
            assertEquals(expectedWithInterest, ranker.score(candidate, NOW, Set.of(InterestCategory.RESTAURANT)).value(), EPS);
            assertEquals(0.875d, expectedWithInterest, EPS);
        }

        @Test
        @DisplayName("FeedScore rejeita valores inválidos")
        void feedScoreValidation() {
            assertThrows(IllegalArgumentException.class, () -> new FeedScore(Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> new FeedScore(Double.NEGATIVE_INFINITY));
            assertThrows(IllegalArgumentException.class, () -> new FeedScore(-0.01d));
            assertThrows(IllegalArgumentException.class, () -> new FeedScore(1.01d));
            assertEquals(0.0d, new FeedScore(0.0d).value(), EPS);
            assertEquals(1.0d, new FeedScore(1.0d).value(), EPS);
        }
    }

    @Nested
    @DisplayName("Determinismo e ordenação")
    class Ordering {

        @Test
        @DisplayName("Usuário interessado em Gastronomia prioriza restaurante sobre tecnologia")
        void foodInterestPrioritizesRestaurantOverTech() {
            Instant time = NOW.minus(Duration.ofHours(1));
            FeedCandidate restaurant = new FeedCandidate(
                    id("00000000-0000-0000-0000-000000000001"), id("00000000-0000-0000-0000-0000000000a1"),
                    id("00000000-0000-0000-0000-0000000000d1"), InterestCategory.RESTAURANT, time, false, 0, true);

            FeedCandidate tech = new FeedCandidate(
                    id("00000000-0000-0000-0000-000000000002"), id("00000000-0000-0000-0000-0000000000a2"),
                    id("00000000-0000-0000-0000-0000000000d2"), InterestCategory.TECHNOLOGY, time, false, 0, true);

            List<RankedFeedCandidate> ranked = ranker.rank(List.of(tech, restaurant), NOW, Set.of(InterestCategory.RESTAURANT));
            assertEquals(restaurant.reviewId(), ranked.get(0).candidate().reviewId());
            assertEquals(tech.reviewId(), ranked.get(1).candidate().reviewId());
            assertTrue(ranked.get(0).score().value() > ranked.get(1).score().value());
        }

        @Test
        @DisplayName("Usuário interessado em Tecnologia prioriza tecnologia sobre restaurante diante dos mesmos candidatos")
        void techInterestPrioritizesTechOverRestaurant() {
            Instant time = NOW.minus(Duration.ofHours(1));
            FeedCandidate restaurant = new FeedCandidate(
                    id("00000000-0000-0000-0000-000000000001"), id("00000000-0000-0000-0000-0000000000a1"),
                    id("00000000-0000-0000-0000-0000000000d1"), InterestCategory.RESTAURANT, time, false, 0, true);

            FeedCandidate tech = new FeedCandidate(
                    id("00000000-0000-0000-0000-000000000002"), id("00000000-0000-0000-0000-0000000000a2"),
                    id("00000000-0000-0000-0000-0000000000d2"), InterestCategory.TECHNOLOGY, time, false, 0, true);

            List<RankedFeedCandidate> ranked = ranker.rank(List.of(restaurant, tech), NOW, Set.of(InterestCategory.TECHNOLOGY));
            assertEquals(tech.reviewId(), ranked.get(0).candidate().reviewId());
            assertEquals(restaurant.reviewId(), ranked.get(1).candidate().reviewId());
            assertTrue(ranked.get(0).score().value() > ranked.get(1).score().value());
        }

        @Test
        @DisplayName("Usuário sem interesses mantém a ordem factual pura por desempate e recência")
        void userWithoutInterestsPreservesFactualOrder() {
            Instant time1 = NOW.minus(Duration.ofHours(1));
            Instant time2 = NOW.minus(Duration.ofHours(2));
            FeedCandidate newer = new FeedCandidate(
                    id("00000000-0000-0000-0000-000000000001"), id("00000000-0000-0000-0000-0000000000a1"),
                    id("00000000-0000-0000-0000-0000000000d1"), InterestCategory.RESTAURANT, time1, false, 0, true);

            FeedCandidate older = new FeedCandidate(
                    id("00000000-0000-0000-0000-000000000002"), id("00000000-0000-0000-0000-0000000000a2"),
                    id("00000000-0000-0000-0000-0000000000d2"), InterestCategory.RESTAURANT, time2, false, 0, true);

            List<RankedFeedCandidate> ranked = ranker.rank(List.of(older, newer), NOW, Set.of());
            assertEquals(newer.reviewId(), ranked.get(0).candidate().reviewId());
            assertEquals(older.reviewId(), ranked.get(1).candidate().reviewId());
        }

        @Test
        @DisplayName("Empate de score e createdAt desempata por reviewId ASC")
        void tieBreaksByReviewIdAsc() {
            Instant created = NOW.minus(Duration.ofHours(2));
            FeedCandidate laterId = candidate("00000000-0000-0000-0000-0000000000bb", created, false, 0, false);
            FeedCandidate earlierId = candidate("00000000-0000-0000-0000-0000000000aa", created, false, 0, false);

            List<RankedFeedCandidate> ranked = ranker.rank(List.of(laterId, earlierId), NOW);
            assertEquals(earlierId.reviewId(), ranked.get(0).candidate().reviewId());
            assertEquals(laterId.reviewId(), ranked.get(1).candidate().reviewId());
            assertEquals(ranked.get(0).score().value(), ranked.get(1).score().value(), EPS);
        }

        @Test
        @DisplayName("A ordem de entrada não é usada como desempate")
        void inputOrderIsNotTieBreak() {
            Instant created = NOW;
            FeedCandidate a = candidate("00000000-0000-0000-0000-0000000000cc", created, false, 0, false);
            FeedCandidate b = candidate("00000000-0000-0000-0000-0000000000aa", created, false, 0, false);
            FeedCandidate c = candidate("00000000-0000-0000-0000-0000000000bb", created, false, 0, false);

            List<UUID> fromAbc = ids(ranker.rank(List.of(a, b, c), NOW));
            List<UUID> fromCba = ids(ranker.rank(List.of(c, b, a), NOW));
            assertEquals(List.of(b.reviewId(), c.reviewId(), a.reviewId()), fromAbc);
            assertEquals(fromAbc, fromCba);
        }

        @Test
        @DisplayName("referenceTime deve ser explícito")
        void referenceTimeIsRequired() {
            assertThrows(NullPointerException.class, () -> ranker.rank(List.of(), null));
        }
    }

    private static List<UUID> ids(List<RankedFeedCandidate> ranked) {
        return ranked.stream().map(item -> item.candidate().reviewId()).toList();
    }

    private static FeedCandidate candidateWithCategory(String reviewId, InterestCategory category) {
        return new FeedCandidate(
                id(reviewId),
                id("00000000-0000-0000-0000-0000000000a1"),
                id("00000000-0000-0000-0000-0000000000d1"),
                category,
                NOW,
                false,
                0,
                false
        );
    }

    private static FeedCandidate candidate(
            String reviewId,
            Instant createdAt,
            boolean verified,
            int helpful,
            boolean follow
    ) {
        return new FeedCandidate(
                id(reviewId),
                id("00000000-0000-0000-0000-0000000000a1"),
                id("00000000-0000-0000-0000-0000000000d1"),
                null,
                createdAt,
                verified,
                helpful,
                follow
        );
    }

    private static UUID id(String value) {
        if (value.length() == 1) {
            return UUID.fromString("00000000-0000-0000-0000-00000000000" + value);
        }
        return UUID.fromString(value);
    }
}
