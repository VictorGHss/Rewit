package com.rewit.application.service;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.ReviewDetailView;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.ReviewTargetRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários de Aplicação - ReviewService (Step 10.0)")
class ReviewServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ReviewTargetRepository reviewTargetRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RateableTargetRepository rateableTargetRepository;

    @Mock
    private PlaceRepository placeRepository;

    @InjectMocks
    private ReviewService reviewService;

    private User activeUser;
    private UUID authorUserId;

    @BeforeEach
    void setUp() {
        authorUserId = UUID.randomUUID();
        activeUser = new User(authorUserId, "user@rewit.com", "hash123", AuthProvider.LOCAL, null);
    }

    @Test
    @DisplayName("1. Autor inexistente rejeitado antes da persistência")
    void shouldRejectWhenAuthorNotFound() {
        when(userRepository.findById(authorUserId)).thenReturn(Optional.empty());

        CreateReviewCommand cmd = new CreateReviewCommand(
                authorUserId,
                null,
                "Texto",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(UUID.randomUUID(), new BigDecimal("4.0"), "Bom"))
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> reviewService.createReview(cmd));
        assertEquals("USER_NOT_FOUND", ex.getErrorCode());

        verifyNoInteractions(reviewRepository, reviewTargetRepository);
    }

    @Test
    @DisplayName("2. Autor soft-deleted ou inativo rejeitado")
    void shouldRejectWhenAuthorInactiveOrDeleted() {
        activeUser.softDelete();
        when(userRepository.findById(authorUserId)).thenReturn(Optional.of(activeUser));

        CreateReviewCommand cmd = new CreateReviewCommand(
                authorUserId,
                null,
                "Texto",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(UUID.randomUUID(), new BigDecimal("4.0"), "Bom"))
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> reviewService.createReview(cmd));
        assertEquals("USER_INACTIVE", ex.getErrorCode());

        verifyNoInteractions(reviewRepository, reviewTargetRepository);
    }

    @Test
    @DisplayName("3. Target inexistente rejeitado")
    void shouldRejectWhenTargetDoesNotExist() {
        when(userRepository.findById(authorUserId)).thenReturn(Optional.of(activeUser));

        UUID missingTargetId = UUID.randomUUID();
        when(rateableTargetRepository.existsById(missingTargetId)).thenReturn(false);

        CreateReviewCommand cmd = new CreateReviewCommand(
                authorUserId,
                null,
                "Texto",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(missingTargetId, new BigDecimal("4.0"), "Bom"))
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> reviewService.createReview(cmd));
        assertEquals("RATEABLE_TARGET_NOT_FOUND", ex.getErrorCode());

        verifyNoInteractions(reviewRepository, reviewTargetRepository);
    }

    @Test
    @DisplayName("4. Múltiplos targets válidos persistidos com sucesso")
    void shouldPersistReviewWithMultipleValidTargets() {
        when(userRepository.findById(authorUserId)).thenReturn(Optional.of(activeUser));

        UUID target1 = UUID.randomUUID();
        UUID target2 = UUID.randomUUID();
        when(rateableTargetRepository.existsById(target1)).thenReturn(true);
        when(rateableTargetRepository.existsById(target2)).thenReturn(true);

        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reviewTargetRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        CreateReviewCommand cmd = new CreateReviewCommand(
                authorUserId,
                null,
                "Experiência completa",
                false,
                "PUBLIC",
                List.of(
                        new CreateReviewTargetCommand(target1, new BigDecimal("4.5"), "Lugar"),
                        new CreateReviewTargetCommand(target2, new BigDecimal("5.0"), "Café especial")
                )
        );

        ReviewDetailView result = reviewService.createReview(cmd);

        assertNotNull(result);
        assertEquals(authorUserId, result.userId());
        assertEquals("ACTIVE", result.status());
        assertEquals("PUBLIC", result.visibility());
        assertEquals(2, result.targets().size());

        verify(reviewRepository, times(1)).save(any(Review.class));
        verify(reviewTargetRepository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("5. Um target inválido no meio da lista rejeita a operação inteira")
    void shouldRejectWhenOneTargetInListIsInvalid() {
        when(userRepository.findById(authorUserId)).thenReturn(Optional.of(activeUser));

        UUID targetValid1 = UUID.randomUUID();
        UUID targetMissing = UUID.randomUUID();
        UUID targetValid2 = UUID.randomUUID();

        when(rateableTargetRepository.existsById(targetValid1)).thenReturn(true);
        when(rateableTargetRepository.existsById(targetMissing)).thenReturn(false);

        CreateReviewCommand cmd = new CreateReviewCommand(
                authorUserId,
                null,
                "Texto",
                false,
                "PUBLIC",
                List.of(
                        new CreateReviewTargetCommand(targetValid1, new BigDecimal("4.0"), "Ok"),
                        new CreateReviewTargetCommand(targetMissing, new BigDecimal("3.0"), "Inexistente"),
                        new CreateReviewTargetCommand(targetValid2, new BigDecimal("5.0"), "Top")
                )
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> reviewService.createReview(cmd));
        assertEquals("RATEABLE_TARGET_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    @DisplayName("6. Nenhuma persistência parcial quando um target falha")
    void shouldNotPersistAnyEntityWhenTargetValidationFails() {
        when(userRepository.findById(authorUserId)).thenReturn(Optional.of(activeUser));

        UUID targetValid = UUID.randomUUID();
        UUID targetInvalidRating = UUID.randomUUID();

        CreateReviewCommand cmd = new CreateReviewCommand(
                authorUserId,
                null,
                "Texto",
                false,
                "PUBLIC",
                List.of(
                        new CreateReviewTargetCommand(targetValid, new BigDecimal("4.0"), "Ok"),
                        new CreateReviewTargetCommand(targetInvalidRating, new BigDecimal("5.5"), "Nota acima de 5.0")
                )
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> reviewService.createReview(cmd));
        assertEquals("INVALID_RATING_RANGE", ex.getErrorCode());

        verify(reviewRepository, never()).save(any());
        verify(reviewTargetRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("7. ContextPlace inexistente rejeitado")
    void shouldRejectWhenContextPlaceNotFound() {
        when(userRepository.findById(authorUserId)).thenReturn(Optional.of(activeUser));

        UUID missingPlaceId = UUID.randomUUID();
        when(placeRepository.findById(missingPlaceId)).thenReturn(Optional.empty());

        CreateReviewCommand cmd = new CreateReviewCommand(
                authorUserId,
                missingPlaceId,
                "Texto",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(UUID.randomUUID(), new BigDecimal("4.0"), "Bom"))
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> reviewService.createReview(cmd));
        assertEquals("PLACE_NOT_FOUND", ex.getErrorCode());

        verifyNoInteractions(reviewRepository, reviewTargetRepository);
    }

    @Test
    @DisplayName("8. Propagação correta de visibility")
    void shouldPropagateVisibilityCorrectly() {
        when(userRepository.findById(authorUserId)).thenReturn(Optional.of(activeUser));

        UUID targetId = UUID.randomUUID();
        when(rateableTargetRepository.existsById(targetId)).thenReturn(true);
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reviewTargetRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        CreateReviewCommand cmd = new CreateReviewCommand(
                authorUserId,
                null,
                "Apenas para seguidores",
                false,
                "FOLLOWERS",
                List.of(new CreateReviewTargetCommand(targetId, new BigDecimal("5.0"), "Excelente"))
        );

        ReviewDetailView result = reviewService.createReview(cmd);

        assertEquals("FOLLOWERS", result.visibility());

        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(captor.capture());
        assertEquals("FOLLOWERS", captor.getValue().getVisibility());
    }

    @Test
    @DisplayName("9. Propagação correta de anonymous preservando autor")
    void shouldPropagateAnonymousPreservingInternalAuthor() {
        when(userRepository.findById(authorUserId)).thenReturn(Optional.of(activeUser));

        UUID targetId = UUID.randomUUID();
        when(rateableTargetRepository.existsById(targetId)).thenReturn(true);
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reviewTargetRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        CreateReviewCommand cmd = new CreateReviewCommand(
                authorUserId,
                null,
                "Avaliação anônima",
                true,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(targetId, new BigDecimal("4.0"), "Bom"))
        );

        ReviewDetailView result = reviewService.createReview(cmd);

        assertTrue(result.isAnonymous());
        assertEquals(authorUserId, result.userId());

        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(captor.capture());
        assertTrue(captor.getValue().isAnonymous());
        assertEquals(authorUserId, captor.getValue().getUserId());
    }

    @Test
    @DisplayName("10. Atomicidade lógica do caso de uso (rejeição de duplicidade no comando)")
    void shouldEnforceLogicalAtomicityWhenDuplicateTargetsInCommand() {
        when(userRepository.findById(authorUserId)).thenReturn(Optional.of(activeUser));

        UUID duplicateTargetId = UUID.randomUUID();

        CreateReviewCommand cmd = new CreateReviewCommand(
                authorUserId,
                null,
                "Tentativa de duplicar alvo",
                false,
                "PUBLIC",
                List.of(
                        new CreateReviewTargetCommand(duplicateTargetId, new BigDecimal("4.0"), "Primeira"),
                        new CreateReviewTargetCommand(duplicateTargetId, new BigDecimal("5.0"), "Segunda")
                )
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> reviewService.createReview(cmd));
        assertEquals("DUPLICATE_REVIEW_TARGET", ex.getErrorCode());

        verifyNoInteractions(reviewRepository, reviewTargetRepository);
    }
}
