package com.rewit.application.service;

import com.rewit.domain.model.UserReputation;
import com.rewit.infrastructure.persistence.repository.ReviewJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewReactionJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Testes unitários do ReputationCalculator (Step 23.0).
 * Sem banco real, sem Spring — apenas Mockito.
 */
@DisplayName("Testes Unitários: ReputationCalculator (Step 23.0)")
class ReputationCalculatorUnitTest {

    @Mock
    private ReviewJpaRepository reviewJpaRepository;

    @Mock
    private ReviewReactionJpaRepository reactionJpaRepository;

    @InjectMocks
    private ReputationCalculator calculator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("Usuário sem atividade: todos os sinais devem ser zero")
    void shouldReturnZeroSignalsForInactiveUser() {
        UUID userId = UUID.randomUUID();
        when(reviewJpaRepository.countActiveNonAnonByUserId(userId)).thenReturn(0L);
        when(reviewJpaRepository.countActiveNonAnonVerifiedByUserId(userId)).thenReturn(0L);
        when(reviewJpaRepository.countDistinctTargetsByUserIdActiveNonAnon(userId)).thenReturn(0L);
        when(reactionJpaRepository.countHelpfulVotesReceivedByUserIdNonAnon(userId)).thenReturn(0L);

        UserReputation rep = calculator.calculate(userId);

        assertEquals(0, rep.getActiveReviews());
        assertEquals(0, rep.getVerifiedReviews());
        assertEquals(0, rep.getHelpfulVotesReceived());
        assertEquals(0, rep.getDistinctTargetsReviewed());
    }

    @Test
    @DisplayName("Usuário com reviews ativas: sinais calculados corretamente")
    void shouldCalculateSignalsForActiveUser() {
        UUID userId = UUID.randomUUID();
        when(reviewJpaRepository.countActiveNonAnonByUserId(userId)).thenReturn(10L);
        when(reviewJpaRepository.countActiveNonAnonVerifiedByUserId(userId)).thenReturn(4L);
        when(reviewJpaRepository.countDistinctTargetsByUserIdActiveNonAnon(userId)).thenReturn(7L);
        when(reactionJpaRepository.countHelpfulVotesReceivedByUserIdNonAnon(userId)).thenReturn(25L);

        UserReputation rep = calculator.calculate(userId);

        assertEquals(10, rep.getActiveReviews());
        assertEquals(4,  rep.getVerifiedReviews());
        assertEquals(7,  rep.getDistinctTargetsReviewed());
        assertEquals(25, rep.getHelpfulVotesReceived());
    }

    @Test
    @DisplayName("version deve sempre ser CURRENT_VERSION (1)")
    void shouldAlwaysUseCurrentVersion() {
        UUID userId = UUID.randomUUID();
        when(reviewJpaRepository.countActiveNonAnonByUserId(userId)).thenReturn(5L);
        when(reviewJpaRepository.countActiveNonAnonVerifiedByUserId(userId)).thenReturn(2L);
        when(reviewJpaRepository.countDistinctTargetsByUserIdActiveNonAnon(userId)).thenReturn(3L);
        when(reactionJpaRepository.countHelpfulVotesReceivedByUserIdNonAnon(userId)).thenReturn(10L);

        UserReputation rep = calculator.calculate(userId);

        assertEquals(UserReputation.CURRENT_VERSION, rep.getVersion());
        assertEquals(1, rep.getVersion());
    }

    @Test
    @DisplayName("verifiedReviews nao pode exceder activeReviews — imutavel no dominio")
    void verifiedReviewsCannotExceedActiveReviews() {
        UUID userId = UUID.randomUUID();
        when(reviewJpaRepository.countActiveNonAnonByUserId(userId)).thenReturn(3L);
        when(reviewJpaRepository.countActiveNonAnonVerifiedByUserId(userId)).thenReturn(3L);
        when(reviewJpaRepository.countDistinctTargetsByUserIdActiveNonAnon(userId)).thenReturn(2L);
        when(reactionJpaRepository.countHelpfulVotesReceivedByUserIdNonAnon(userId)).thenReturn(8L);

        UserReputation rep = calculator.calculate(userId);

        assertTrue(rep.getVerifiedReviews() <= rep.getActiveReviews());
    }

    @Test
    @DisplayName("Recálculo repetido com mesmos dados produz mesmo resultado (determinismo)")
    void shouldBeIdempotentOnSameData() {
        UUID userId = UUID.randomUUID();
        when(reviewJpaRepository.countActiveNonAnonByUserId(userId)).thenReturn(5L);
        when(reviewJpaRepository.countActiveNonAnonVerifiedByUserId(userId)).thenReturn(2L);
        when(reviewJpaRepository.countDistinctTargetsByUserIdActiveNonAnon(userId)).thenReturn(4L);
        when(reactionJpaRepository.countHelpfulVotesReceivedByUserIdNonAnon(userId)).thenReturn(15L);

        UserReputation rep1 = calculator.calculate(userId);
        UserReputation rep2 = calculator.calculate(userId);

        assertEquals(rep1.getActiveReviews(),           rep2.getActiveReviews());
        assertEquals(rep1.getVerifiedReviews(),         rep2.getVerifiedReviews());
        assertEquals(rep1.getHelpfulVotesReceived(),    rep2.getHelpfulVotesReceived());
        assertEquals(rep1.getDistinctTargetsReviewed(), rep2.getDistinctTargetsReviewed());
        assertEquals(rep1.getVersion(),                 rep2.getVersion());
    }

    @Test
    @DisplayName("userId null deve lancar NullPointerException")
    void shouldThrowOnNullUserId() {
        assertThrows(NullPointerException.class, () -> calculator.calculate(null));
    }

    @Test
    @DisplayName("Exatamente 3 queries sao executadas (sem N+1)")
    void shouldExecuteExactlyThreeQueries() {
        UUID userId = UUID.randomUUID();
        when(reviewJpaRepository.countActiveNonAnonByUserId(userId)).thenReturn(2L);
        when(reviewJpaRepository.countActiveNonAnonVerifiedByUserId(userId)).thenReturn(1L);
        when(reviewJpaRepository.countDistinctTargetsByUserIdActiveNonAnon(userId)).thenReturn(1L);
        when(reactionJpaRepository.countHelpfulVotesReceivedByUserIdNonAnon(userId)).thenReturn(5L);

        calculator.calculate(userId);

        // 2 queries em reviewJpaRepository + 1 em reactionJpaRepository = 3 total
        verify(reviewJpaRepository, times(1)).countActiveNonAnonByUserId(userId);
        verify(reviewJpaRepository, times(1)).countActiveNonAnonVerifiedByUserId(userId);
        verify(reviewJpaRepository, times(1)).countDistinctTargetsByUserIdActiveNonAnon(userId);
        verify(reactionJpaRepository, times(1)).countHelpfulVotesReceivedByUserIdNonAnon(userId);
        verifyNoMoreInteractions(reviewJpaRepository, reactionJpaRepository);
    }

    @Test
    @DisplayName("calculatedAt nao deve ser null")
    void shouldAlwaysHaveCalculatedAt() {
        UUID userId = UUID.randomUUID();
        when(reviewJpaRepository.countActiveNonAnonByUserId(userId)).thenReturn(0L);
        when(reviewJpaRepository.countActiveNonAnonVerifiedByUserId(userId)).thenReturn(0L);
        when(reviewJpaRepository.countDistinctTargetsByUserIdActiveNonAnon(userId)).thenReturn(0L);
        when(reactionJpaRepository.countHelpfulVotesReceivedByUserIdNonAnon(userId)).thenReturn(0L);

        UserReputation rep = calculator.calculate(userId);

        assertNotNull(rep.getCalculatedAt());
    }

    @Test
    @DisplayName("userId deve ser preservado no resultado")
    void shouldPreserveUserId() {
        UUID userId = UUID.randomUUID();
        when(reviewJpaRepository.countActiveNonAnonByUserId(userId)).thenReturn(1L);
        when(reviewJpaRepository.countActiveNonAnonVerifiedByUserId(userId)).thenReturn(0L);
        when(reviewJpaRepository.countDistinctTargetsByUserIdActiveNonAnon(userId)).thenReturn(1L);
        when(reactionJpaRepository.countHelpfulVotesReceivedByUserIdNonAnon(userId)).thenReturn(2L);

        UserReputation rep = calculator.calculate(userId);

        assertEquals(userId, rep.getUserId());
    }

    @Test
    @DisplayName("Mudanca nos dados altera o snapshot corretamente")
    void shouldReflectDataChangesInSubsequentCalculation() {
        UUID userId = UUID.randomUUID();

        // Antes: 5 reviews
        when(reviewJpaRepository.countActiveNonAnonByUserId(userId)).thenReturn(5L);
        when(reviewJpaRepository.countActiveNonAnonVerifiedByUserId(userId)).thenReturn(2L);
        when(reviewJpaRepository.countDistinctTargetsByUserIdActiveNonAnon(userId)).thenReturn(3L);
        when(reactionJpaRepository.countHelpfulVotesReceivedByUserIdNonAnon(userId)).thenReturn(10L);
        UserReputation before = calculator.calculate(userId);

        // Depois: 8 reviews
        when(reviewJpaRepository.countActiveNonAnonByUserId(userId)).thenReturn(8L);
        when(reviewJpaRepository.countActiveNonAnonVerifiedByUserId(userId)).thenReturn(5L);
        when(reviewJpaRepository.countDistinctTargetsByUserIdActiveNonAnon(userId)).thenReturn(6L);
        when(reactionJpaRepository.countHelpfulVotesReceivedByUserIdNonAnon(userId)).thenReturn(18L);
        UserReputation after = calculator.calculate(userId);

        assertNotEquals(before.getActiveReviews(),           after.getActiveReviews());
        assertNotEquals(before.getVerifiedReviews(),         after.getVerifiedReviews());
        assertNotEquals(before.getHelpfulVotesReceived(),    after.getHelpfulVotesReceived());
        assertNotEquals(before.getDistinctTargetsReviewed(), after.getDistinctTargetsReviewed());
    }

    @Test
    @DisplayName("Nao ha score numerico no resultado (ausencia intencional — ADR-008)")
    void shouldNotExposeNumericScore() {
        UUID userId = UUID.randomUUID();
        when(reviewJpaRepository.countActiveNonAnonByUserId(userId)).thenReturn(20L);
        when(reviewJpaRepository.countActiveNonAnonVerifiedByUserId(userId)).thenReturn(10L);
        when(reviewJpaRepository.countDistinctTargetsByUserIdActiveNonAnon(userId)).thenReturn(8L);
        when(reactionJpaRepository.countHelpfulVotesReceivedByUserIdNonAnon(userId)).thenReturn(50L);

        UserReputation rep = calculator.calculate(userId);

        // UserReputation nao tem metodo getScore() — apenas sinais
        // Este teste verifica que nao existe tal campo usando reflexao
        boolean hasScoreField = false;
        for (java.lang.reflect.Field f : rep.getClass().getDeclaredFields()) {
            if (f.getName().equalsIgnoreCase("score")) {
                hasScoreField = true;
                break;
            }
        }
        assertFalse(hasScoreField, "UserReputation nao deve ter campo 'score' sem especificacao formal");
    }

    @Test
    @DisplayName("Determinismo: mesmas entradas agregadas produzem exatamente os mesmos sinais e versao")
    void shouldProduceStrictlyDeterministicOutputForIdenticalInputs() {
        UUID userId = UUID.randomUUID();
        when(reviewJpaRepository.countActiveNonAnonByUserId(userId)).thenReturn(7L);
        when(reviewJpaRepository.countActiveNonAnonVerifiedByUserId(userId)).thenReturn(4L);
        when(reviewJpaRepository.countDistinctTargetsByUserIdActiveNonAnon(userId)).thenReturn(5L);
        when(reactionJpaRepository.countHelpfulVotesReceivedByUserIdNonAnon(userId)).thenReturn(15L);

        UserReputation run1 = calculator.calculate(userId);
        UserReputation run2 = calculator.calculate(userId);
        UserReputation run3 = calculator.calculate(userId);

        assertEquals(run1.getVersion(), run2.getVersion());
        assertEquals(run1.getActiveReviews(), run2.getActiveReviews());
        assertEquals(run1.getVerifiedReviews(), run2.getVerifiedReviews());
        assertEquals(run1.getHelpfulVotesReceived(), run2.getHelpfulVotesReceived());
        assertEquals(run1.getDistinctTargetsReviewed(), run2.getDistinctTargetsReviewed());

        assertEquals(run2.getActiveReviews(), run3.getActiveReviews());
        assertEquals(run2.getVerifiedReviews(), run3.getVerifiedReviews());
        assertEquals(run2.getHelpfulVotesReceived(), run3.getHelpfulVotesReceived());
        assertEquals(run2.getDistinctTargetsReviewed(), run3.getDistinctTargetsReviewed());
        assertEquals(1, run1.getVersion());
    }
}
