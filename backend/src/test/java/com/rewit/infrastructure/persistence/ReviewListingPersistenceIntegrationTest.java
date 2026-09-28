package com.rewit.infrastructure.persistence;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.ReviewDetailView;
import com.rewit.application.dto.ReviewDto.ReviewPublicView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.ReviewService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.persistence.entity.ReviewJpaEntity;
import com.rewit.infrastructure.persistence.repository.ReviewJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração de Persistência no PostgreSQL Real - Listagem e Paginação de Reviews (Step 14.0)")
class ReviewListingPersistenceIntegrationTest {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    @Autowired
    private ReviewJpaRepository reviewJpaRepository;

    @Autowired
    private ProfileRepository profileRepository;

    private User createActiveUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User(null, "user-" + suffix + "@rewit.com", "hash123", AuthProvider.LOCAL, null);
        User savedUser = userRepository.save(user);

        Profile profile = new Profile(UUID.randomUUID(), savedUser.getId(), "handle_" + suffix, "Nome " + suffix, "Bio", null);
        profileRepository.save(profile);

        return savedUser;
    }

    private RateableTarget createRateableTarget(TargetType type) {
        RateableTarget target = new RateableTarget(UUID.randomUUID(), type);
        return rateableTargetRepository.save(target);
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
                null,
                "Restaurante Teste " + suffix,
                "restaurante-teste-" + suffix,
                "RESTAURANTE",
                "Descrição",
                "Rua Teste, 123",
                "123",
                "Centro",
                "Curitiba",
                "PR",
                "BR",
                -25.4284,
                -49.2733,
                50,
                "USER",
                false,
                null,
                "ACTIVE"
        );
        return placeRepository.save(place);
    }

    @Test
    @DisplayName("1. Target inexistente retorna 404")
    void shouldReturn404WhenTargetDoesNotExist() {
        UUID nonexistentTargetId = UUID.randomUUID();
        User requester = createActiveUser();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewService.findReviewsByTarget(nonexistentTargetId, 0, 10, "newest", false, requester.getId())
        );

        assertEquals("RATEABLE_TARGET_NOT_FOUND", ex.getErrorCode());
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    @DisplayName("2. Target existente sem reviews retorna lista vazia 200")
    void shouldReturnEmptyListWhenTargetHasNoReviews() {
        RateableTarget target = createRateableTarget(TargetType.PLACE);
        User requester = createActiveUser();

        PageResult<ReviewPublicView> result = reviewService.findReviewsByTarget(
                target.getId(), 0, 10, "newest", false, requester.getId()
        );

        assertNotNull(result);
        assertTrue(result.content().isEmpty());
        assertEquals(0, result.totalElements());
        assertEquals(0, result.totalPages());
        assertTrue(result.isLast());
    }

    @Test
    @DisplayName("3. Múltiplas páginas sem duplicação e contagem exata")
    void shouldPaginateReviewsCorrectlyWithoutDuplication() {
        RateableTarget target = createRateableTarget(TargetType.PLACE);
        User author = createActiveUser();
        User requester = createActiveUser();

        // Cria 5 reviews para o mesmo alvo
        for (int i = 1; i <= 5; i++) {
            CreateReviewCommand cmd = new CreateReviewCommand(
                    author.getId(),
                    null,
                    "Review número " + i,
                    false,
                    "PUBLIC",
                    List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("4.0"), "Nota " + i))
            );
            reviewService.createReview(cmd);
        }

        // Página 0 com size 2
        PageResult<ReviewPublicView> page0 = reviewService.findReviewsByTarget(
                target.getId(), 0, 2, "newest", false, requester.getId()
        );
        assertEquals(2, page0.content().size());
        assertEquals(5, page0.totalElements());
        assertEquals(3, page0.totalPages());
        assertEquals(0, page0.pageNumber());
        assertFalse(page0.isLast());

        // Página 1 com size 2
        PageResult<ReviewPublicView> page1 = reviewService.findReviewsByTarget(
                target.getId(), 1, 2, "newest", false, requester.getId()
        );
        assertEquals(2, page1.content().size());
        assertEquals(1, page1.pageNumber());
        assertFalse(page1.isLast());

        // Página 2 com size 2 (último elemento)
        PageResult<ReviewPublicView> page2 = reviewService.findReviewsByTarget(
                target.getId(), 2, 2, "newest", false, requester.getId()
        );
        assertEquals(1, page2.content().size());
        assertEquals(2, page2.pageNumber());
        assertTrue(page2.isLast());

        // Verifica que nenhum ID da página 0 está na página 1 ou página 2
        List<UUID> idsPage0 = page0.content().stream().map(v -> v.id()).toList();
        List<UUID> idsPage1 = page1.content().stream().map(v -> v.id()).toList();
        List<UUID> idsPage2 = page2.content().stream().map(v -> v.id()).toList();

        for (UUID id : idsPage0) {
            assertFalse(idsPage1.contains(id), "Duplicação detectada entre página 0 e 1: " + id);
            assertFalse(idsPage2.contains(id), "Duplicação detectada entre página 0 e 2: " + id);
        }
        for (UUID id : idsPage1) {
            assertFalse(idsPage2.contains(id), "Duplicação detectada entre página 1 e 2: " + id);
        }
    }

    @Test
    @DisplayName("4. Cenário Multi-target: Ordenação por rating respeita a nota específica de cada alvo")
    void shouldSortCorrectlyBySpecificTargetRatingInMultiTargetScenario() {
        RateableTarget targetX = createRateableTarget(TargetType.PLACE);
        RateableTarget targetY = createRateableTarget(TargetType.PRODUCT);
        User author1 = createActiveUser();
        User author2 = createActiveUser();
        User requester = createActiveUser();

        // Review A: Target X = 5.0, Target Y = 2.0
        CreateReviewCommand cmdA = new CreateReviewCommand(
                author1.getId(),
                null,
                "Review A",
                false,
                "PUBLIC",
                List.of(
                        new CreateReviewTargetCommand(targetX.getId(), new BigDecimal("5.0"), "Target X excelente"),
                        new CreateReviewTargetCommand(targetY.getId(), new BigDecimal("2.0"), "Target Y ruim")
                )
        );
        ReviewDetailView reviewA = reviewService.createReview(cmdA);

        // Review B: Target X = 3.0, Target Y = 5.0
        CreateReviewCommand cmdB = new CreateReviewCommand(
                author2.getId(),
                null,
                "Review B",
                false,
                "PUBLIC",
                List.of(
                        new CreateReviewTargetCommand(targetX.getId(), new BigDecimal("3.0"), "Target X regular"),
                        new CreateReviewTargetCommand(targetY.getId(), new BigDecimal("5.0"), "Target Y excelente")
                )
        );
        ReviewDetailView reviewB = reviewService.createReview(cmdB);

        // Consultando Target X com rating_desc -> Review A (5.0) ANTES de Review B (3.0)
        PageResult<ReviewPublicView> targetXDesc = reviewService.findReviewsByTarget(
                targetX.getId(), 0, 10, "rating_desc", false, requester.getId()
        );
        assertEquals(2, targetXDesc.content().size());
        assertEquals(reviewA.id(), targetXDesc.content().get(0).id());
        assertEquals(reviewB.id(), targetXDesc.content().get(1).id());
        assertEquals(new BigDecimal("5.0"), targetXDesc.content().get(0).targets().getFirst().rating());
        assertEquals(new BigDecimal("3.0"), targetXDesc.content().get(1).targets().getFirst().rating());

        // Consultando Target Y com rating_desc -> Review B (5.0) ANTES de Review A (2.0)
        PageResult<ReviewPublicView> targetYDesc = reviewService.findReviewsByTarget(
                targetY.getId(), 0, 10, "rating_desc", false, requester.getId()
        );
        assertEquals(2, targetYDesc.content().size());
        assertEquals(reviewB.id(), targetYDesc.content().get(0).id());
        assertEquals(reviewA.id(), targetYDesc.content().get(1).id());
        assertEquals(new BigDecimal("5.0"), targetYDesc.content().get(0).targets().getFirst().rating());
        assertEquals(new BigDecimal("2.0"), targetYDesc.content().get(1).targets().getFirst().rating());

        // Consultando Target X com rating_asc -> Review B (3.0) ANTES de Review A (5.0)
        PageResult<ReviewPublicView> targetXAsc = reviewService.findReviewsByTarget(
                targetX.getId(), 0, 10, "rating_asc", false, requester.getId()
        );
        assertEquals(2, targetXAsc.content().size());
        assertEquals(reviewB.id(), targetXAsc.content().get(0).id());
        assertEquals(reviewA.id(), targetXAsc.content().get(1).id());
    }

    @Test
    @DisplayName("5. Filtro verifiedOnly=true retorna apenas reviews verificadas no local")
    void shouldFilterByVerifiedOnly() {
        Place place = createPlace();
        RateableTarget target = rateableTargetRepository.findById(place.getId()).orElseThrow();
        User author = createActiveUser();
        User requester = createActiveUser();

        // Review 1: Não verificada (sem coordenadas)
        CreateReviewCommand cmdUnverified = new CreateReviewCommand(
                author.getId(),
                place.getId(),
                "Não verificado",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("4.0"), null))
        );
        reviewService.createReview(cmdUnverified);

        // Review 2: Verificada (com coordenadas dentro do raio do place)
        CreateReviewCommand cmdVerified = new CreateReviewCommand(
                author.getId(),
                place.getId(),
                "Verificado no local",
                false,
                "PUBLIC",
                -25.4284,
                -49.2733,
                5.0,
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("5.0"), null))
        );
        reviewService.createReview(cmdVerified);

        // verifiedOnly = false -> retorna 2 reviews
        PageResult<ReviewPublicView> all = reviewService.findReviewsByTarget(
                target.getId(), 0, 10, "newest", false, requester.getId()
        );
        assertEquals(2, all.content().size());

        // verifiedOnly = true -> retorna apenas 1 review (a verificada)
        PageResult<ReviewPublicView> verifiedOnly = reviewService.findReviewsByTarget(
                target.getId(), 0, 10, "newest", true, requester.getId()
        );
        assertEquals(1, verifiedOnly.content().size());
        assertTrue(verifiedOnly.content().getFirst().isVerifiedOnSite());
        assertEquals("Verificado no local", verifiedOnly.content().getFirst().experienceText());
    }

    @Test
    @DisplayName("6. Moderação: Reviews UNDER_REVIEW e REMOVED são excluídas da visão pública")
    void shouldExcludeUnderReviewAndRemovedFromPublicView() {
        RateableTarget target = createRateableTarget(TargetType.SERVICE);
        User author = createActiveUser();
        User requester = createActiveUser();

        // Review ACTIVE
        CreateReviewCommand cmdActive = new CreateReviewCommand(
                author.getId(), null, "Review ativa", false, "PUBLIC",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("4.0"), null))
        );
        ReviewDetailView activeReview = reviewService.createReview(cmdActive);

        // Review UNDER_REVIEW
        CreateReviewCommand cmdUnderReview = new CreateReviewCommand(
                author.getId(), null, "Review sob suspeita", false, "PUBLIC",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("1.0"), null))
        );
        ReviewDetailView underReview = reviewService.createReview(cmdUnderReview);
        ReviewJpaEntity entityUnderReview = reviewJpaRepository.findById(underReview.id()).orElseThrow();
        entityUnderReview.setStatus(ReviewStatus.UNDER_REVIEW.name());
        reviewJpaRepository.saveAndFlush(entityUnderReview);

        // Review REMOVED
        CreateReviewCommand cmdRemoved = new CreateReviewCommand(
                author.getId(), null, "Review removida", false, "PUBLIC",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("1.0"), null))
        );
        ReviewDetailView removed = reviewService.createReview(cmdRemoved);
        ReviewJpaEntity entityRemoved = reviewJpaRepository.findById(removed.id()).orElseThrow();
        entityRemoved.setStatus(ReviewStatus.REMOVED.name());
        reviewJpaRepository.saveAndFlush(entityRemoved);

        // Consulta pública por terceiro
        PageResult<ReviewPublicView> result = reviewService.findReviewsByTarget(
                target.getId(), 0, 10, "newest", false, requester.getId()
        );

        assertEquals(1, result.content().size());
        assertEquals(activeReview.id(), result.content().getFirst().id());
        assertEquals("Review ativa", result.content().getFirst().experienceText());
    }

    @Test
    @DisplayName("7. Isolamento de usuário em /me/reviews: Usuário A vê apenas suas reviews, incluindo PRIVATE")
    void shouldIsolateUserReviewsInMeReviews() {
        RateableTarget target = createRateableTarget(TargetType.EVENT);
        User userA = createActiveUser();
        User userB = createActiveUser();

        // User A cria uma PUBLIC e uma PRIVATE
        reviewService.createReview(new CreateReviewCommand(
                userA.getId(), null, "Review pública A", false, "PUBLIC",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("4.0"), null))
        ));
        reviewService.createReview(new CreateReviewCommand(
                userA.getId(), null, "Review privada A", false, "PRIVATE",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("3.0"), null))
        ));

        // User B cria uma PUBLIC
        reviewService.createReview(new CreateReviewCommand(
                userB.getId(), null, "Review pública B", false, "PUBLIC",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("5.0"), null))
        ));

        // /me/reviews para User A
        PageResult<ReviewPublicView> meA = reviewService.findMyReviews(userA.getId(), 0, 10);
        assertEquals(2, meA.content().size());
        assertTrue(meA.content().stream().allMatch(r -> r.author().id().equals(userA.getId())));
        assertTrue(meA.content().stream().anyMatch(r -> "PRIVATE".equals(r.visibility())));

        // /me/reviews para User B
        PageResult<ReviewPublicView> meB = reviewService.findMyReviews(userB.getId(), 0, 10);
        assertEquals(1, meB.content().size());
        assertEquals(userB.getId(), meB.content().getFirst().author().id());
        assertEquals("Review pública B", meB.content().getFirst().experienceText());
    }
}
