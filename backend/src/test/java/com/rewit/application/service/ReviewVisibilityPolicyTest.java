package com.rewit.application.service;

import com.rewit.application.port.UserFollowRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Review;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Testes Unitários: ReviewVisibilityPolicy e anonimato")
class ReviewVisibilityPolicyTest {

    private final UUID authorId = UUID.randomUUID();
    private final UUID followerId = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();

    private UserFollowRepository userFollowRepository;
    private ReviewVisibilityPolicy policy;

    @BeforeEach
    void setUp() {
        userFollowRepository = mock(UserFollowRepository.class);
        policy = new ReviewVisibilityPolicy(userFollowRepository);
    }

    private Review review(boolean anonymous, String visibility) {
        Instant now = Instant.now();
        return new Review(UUID.randomUUID(), authorId, null, "Texto", anonymous, false,
                ReviewStatus.ACTIVE, visibility, null, null, null, now, now);
    }

    private void followAuthor() {
        when(userFollowRepository.isFollowing(followerId, authorId)).thenReturn(true);
        when(userFollowRepository.isFollowing(strangerId, authorId)).thenReturn(false);
    }

    @Test
    @DisplayName("Anônima FOLLOWERS antiga: seguidor e não seguidor recebem a mesma negação, sem consulta ao grafo")
    void legacyAnonymousFollowersDeniesFollowerAndStrangerAlike() {
        Review legacy = review(true, "FOLLOWERS");

        BusinessException asFollower = assertThrows(BusinessException.class, () -> policy.validateCanAccess(legacy, followerId));
        BusinessException asStranger = assertThrows(BusinessException.class, () -> policy.validateCanAccess(legacy, strangerId));

        assertEquals(HttpStatus.FORBIDDEN, asFollower.getStatus());
        assertEquals(asStranger.getStatus(), asFollower.getStatus());
        assertEquals(asStranger.getErrorCode(), asFollower.getErrorCode());
        assertEquals(asStranger.getMessage(), asFollower.getMessage());
        // Sem consultar quem segue quem, a resposta não tem como depender do autor real
        verifyNoInteractions(userFollowRepository);
    }

    @Test
    @DisplayName("Anônima FOLLOWERS antiga responde como uma PRIVATE")
    void legacyAnonymousFollowersAnswersLikePrivate() {
        BusinessException legacy = assertThrows(BusinessException.class,
                () -> policy.validateCanAccess(review(true, "FOLLOWERS"), strangerId));
        BusinessException privateReview = assertThrows(BusinessException.class,
                () -> policy.validateCanAccess(review(false, "PRIVATE"), strangerId));

        assertEquals(privateReview.getStatus(), legacy.getStatus());
        assertEquals(privateReview.getErrorCode(), legacy.getErrorCode());
        assertEquals(privateReview.getMessage(), legacy.getMessage());
    }

    @Test
    @DisplayName("O autor continua com acesso à própria anônima FOLLOWERS antiga")
    void authorKeepsAccessToLegacyAnonymousFollowers() {
        Review legacy = review(true, "FOLLOWERS");
        assertDoesNotThrow(() -> policy.validateCanAccess(legacy, authorId));
        assertDoesNotThrow(() -> policy.validateCanRead(legacy, authorId));
    }

    @Test
    @DisplayName("FOLLOWERS identificada: seguidor acessa e não seguidor recebe 403")
    void identifiedFollowersKeepsFollowerAuthorization() {
        followAuthor();
        Review identified = review(false, "FOLLOWERS");

        assertDoesNotThrow(() -> policy.validateCanAccess(identified, followerId));
        BusinessException asStranger = assertThrows(BusinessException.class, () -> policy.validateCanAccess(identified, strangerId));
        assertEquals(HttpStatus.FORBIDDEN, asStranger.getStatus());
    }

    @Test
    @DisplayName("Anônima PUBLIC continua acessível a qualquer autenticado")
    void anonymousPublicStaysPublic() {
        when(userFollowRepository.isFollowing(any(), any())).thenReturn(false);
        assertDoesNotThrow(() -> policy.validateCanAccess(review(true, "PUBLIC"), strangerId));
    }

    @Test
    @DisplayName("effectiveVisibility: só a combinação anônima FOLLOWERS muda de valor")
    void effectiveVisibilityMapsOnlyTheContradictoryCombination() {
        assertEquals("PRIVATE", ReviewVisibilityPolicy.effectiveVisibility(review(true, "FOLLOWERS")));
        assertEquals("FOLLOWERS", ReviewVisibilityPolicy.effectiveVisibility(review(false, "FOLLOWERS")));
        assertEquals("PUBLIC", ReviewVisibilityPolicy.effectiveVisibility(review(true, "PUBLIC")));
        assertEquals("PRIVATE", ReviewVisibilityPolicy.effectiveVisibility(review(true, "PRIVATE")));
    }
}
