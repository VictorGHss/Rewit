package com.rewit.infrastructure.persistence;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.ReviewDetailView;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.ReviewTargetRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.ReviewService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewTarget;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.persistence.entity.ReviewJpaEntity;
import com.rewit.infrastructure.persistence.entity.ReviewTargetJpaEntity;
import com.rewit.infrastructure.persistence.repository.ReviewJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewTargetJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração de Persistência e Integridade de Reviews no PostgreSQL (Step 10.0)")
class ReviewPersistenceIntegrationTest {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ReviewTargetRepository reviewTargetRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    @Autowired
    private ReviewJpaRepository reviewJpaRepository;

    @Autowired
    private ReviewTargetJpaRepository reviewTargetJpaRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User createActiveUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User(null, "user-" + suffix + "@rewit.com", "hash123", AuthProvider.LOCAL, null);
        return userRepository.save(user);
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

    private RateableTarget createRateableTarget(TargetType type) {
        RateableTarget target = new RateableTarget(UUID.randomUUID(), type);
        return rateableTargetRepository.save(target);
    }

    @Test
    @DisplayName("Caso A: Criar Review com um target válido (reviews = 1, review_targets = 1)")
    void shouldPersistReviewWithSingleTarget() {
        User user = createActiveUser();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        CreateReviewCommand cmd = new CreateReviewCommand(
                user.getId(),
                null,
                "Excelente experiência",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("4.5"), "Muito bom"))
        );

        ReviewDetailView created = reviewService.createReview(cmd);

        assertNotNull(created.id());
        assertTrue(reviewJpaRepository.existsById(created.id()));

        List<ReviewTargetJpaEntity> dbTargets = reviewTargetJpaRepository.findByReviewId(created.id());
        assertEquals(1, dbTargets.size());
        assertEquals(target.getId(), dbTargets.get(0).getTargetId());
        assertEquals(0, new BigDecimal("4.5").compareTo(dbTargets.get(0).getRating()));
    }

    @Test
    @DisplayName("Caso B: Criar Review com três targets diferentes (reviews = 1, review_targets = 3)")
    void shouldPersistReviewWithThreeDistinctTargets() {
        User user = createActiveUser();
        RateableTarget target1 = createRateableTarget(TargetType.PLACE);
        RateableTarget target2 = createRateableTarget(TargetType.PRODUCT);
        RateableTarget target3 = createRateableTarget(TargetType.PRODUCT);

        CreateReviewCommand cmd = new CreateReviewCommand(
                user.getId(),
                null,
                "Jantar completo",
                false,
                "PUBLIC",
                List.of(
                        new CreateReviewTargetCommand(target1.getId(), new BigDecimal("4.5"), "Ambiente"),
                        new CreateReviewTargetCommand(target2.getId(), new BigDecimal("3.0"), "Prato principal"),
                        new CreateReviewTargetCommand(target3.getId(), new BigDecimal("5.0"), "Sobremesa")
                )
        );

        ReviewDetailView created = reviewService.createReview(cmd);

        List<ReviewTargetJpaEntity> dbTargets = reviewTargetJpaRepository.findByReviewId(created.id());
        assertEquals(3, dbTargets.size());

        assertTrue(dbTargets.stream().anyMatch(t -> t.getTargetId().equals(target1.getId()) && t.getRating().compareTo(new BigDecimal("4.5")) == 0));
        assertTrue(dbTargets.stream().anyMatch(t -> t.getTargetId().equals(target2.getId()) && t.getRating().compareTo(new BigDecimal("3.0")) == 0));
        assertTrue(dbTargets.stream().anyMatch(t -> t.getTargetId().equals(target3.getId()) && t.getRating().compareTo(new BigDecimal("5.0")) == 0));
    }

    @Test
    @DisplayName("Caso C: Tentar duplicar (review_id, rateable_target_id) diretamente na persistência é rejeitado pela constraint física")
    void shouldRejectDuplicateTargetDirectlyInDatabaseByConstraint() {
        User user = createActiveUser();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        ReviewJpaEntity reviewEntity = new ReviewJpaEntity();
        reviewEntity.setId(UUID.randomUUID());
        reviewEntity.setUserId(user.getId());
        reviewEntity.setStatus("ACTIVE");
        reviewEntity.setVisibility("PUBLIC");
        reviewEntity.setAnonymous(false);
        reviewEntity.setVerifiedOnSite(false);
        reviewJpaRepository.saveAndFlush(reviewEntity);

        ReviewTargetJpaEntity first = new ReviewTargetJpaEntity(
                UUID.randomUUID(),
                reviewEntity.getId(),
                target.getId(),
                new BigDecimal("4.0"),
                "Primeiro",
                null
        );
        reviewTargetJpaRepository.saveAndFlush(first);

        ReviewTargetJpaEntity duplicate = new ReviewTargetJpaEntity(
                UUID.randomUUID(),
                reviewEntity.getId(),
                target.getId(),
                new BigDecimal("5.0"),
                "Segundo duplicado",
                null
        );

        DataIntegrityViolationException duplicateEx = assertThrows(DataIntegrityViolationException.class, () ->
                reviewTargetJpaRepository.saveAndFlush(duplicate)
        );
        assertNotNull(duplicateEx);
    }

    @Test
    @DisplayName("Caso D: Review de usuário A para Place X e Review de usuário B para Place X ambos persistem")
    void shouldAllowDifferentUsersToReviewSameTarget() {
        User userA = createActiveUser();
        User userB = createActiveUser();
        RateableTarget placeTarget = createRateableTarget(TargetType.PLACE);

        CreateReviewCommand cmdA = new CreateReviewCommand(
                userA.getId(),
                null,
                "Avaliação do usuário A",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(placeTarget.getId(), new BigDecimal("4.0"), "Opinião A"))
        );

        CreateReviewCommand cmdB = new CreateReviewCommand(
                userB.getId(),
                null,
                "Avaliação do usuário B",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(placeTarget.getId(), new BigDecimal("5.0"), "Opinião B"))
        );

        ReviewDetailView reviewA = reviewService.createReview(cmdA);
        ReviewDetailView reviewB = reviewService.createReview(cmdB);

        assertNotNull(reviewA.id());
        assertNotNull(reviewB.id());
        assertNotEquals(reviewA.id(), reviewB.id());

        assertTrue(reviewJpaRepository.existsById(reviewA.id()));
        assertTrue(reviewJpaRepository.existsById(reviewB.id()));
    }

    @Test
    @DisplayName("Caso E: Rating fora da escala rejeitado pela constraint física do PostgreSQL")
    void shouldRejectRatingOutOfScaleInDatabase() {
        User user = createActiveUser();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        ReviewJpaEntity reviewEntity = new ReviewJpaEntity();
        reviewEntity.setId(UUID.randomUUID());
        reviewEntity.setUserId(user.getId());
        reviewEntity.setStatus("ACTIVE");
        reviewEntity.setVisibility("PUBLIC");
        reviewJpaRepository.saveAndFlush(reviewEntity);

        ReviewTargetJpaEntity invalidRatingEntity = new ReviewTargetJpaEntity(
                UUID.randomUUID(),
                reviewEntity.getId(),
                target.getId(),
                new BigDecimal("5.5"),
                "Nota fora de range",
                null
        );

        DataIntegrityViolationException invalidRatingEx = assertThrows(DataIntegrityViolationException.class, () ->
                reviewTargetJpaRepository.saveAndFlush(invalidRatingEntity)
        );
        assertNotNull(invalidRatingEx);
    }

    @Test
    @DisplayName("Caso F: Context Place válido persiste com FK correta")
    void shouldPersistReviewWithValidContextPlace() {
        User user = createActiveUser();
        Place place = createPlace();
        RateableTarget productTarget = createRateableTarget(TargetType.PRODUCT);

        CreateReviewCommand cmd = new CreateReviewCommand(
                user.getId(),
                place.getId(),
                "Comi no restaurante X",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(productTarget.getId(), new BigDecimal("4.5"), "Prato"))
        );

        ReviewDetailView created = reviewService.createReview(cmd);

        ReviewJpaEntity dbReview = reviewJpaRepository.findById(created.id()).orElseThrow();
        assertEquals(place.getId(), dbReview.getContextPlaceId());
    }

    @Test
    @DisplayName("Caso G: Context Place inexistente rejeitado")
    void shouldRejectNonExistentContextPlace() {
        User user = createActiveUser();
        UUID nonExistentPlaceId = UUID.randomUUID();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        CreateReviewCommand cmd = new CreateReviewCommand(
                user.getId(),
                nonExistentPlaceId,
                "Lugar fantasma",
                false,
                "PUBLIC",
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("4.0"), "Texto"))
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> reviewService.createReview(cmd));
        assertEquals("PLACE_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    @DisplayName("Caso H & Seção 23: Teste de Rollback Real no PostgreSQL (nenhum Review nem ReviewTarget persiste após falha)")
    void shouldRollbackCompletelyWhenOneTargetIsInvalid() {
        User user = createActiveUser();
        RateableTarget targetA = createRateableTarget(TargetType.PLACE);
        UUID targetBInvalid = UUID.randomUUID(); // Inexistente no banco
        RateableTarget targetC = createRateableTarget(TargetType.PRODUCT);

        long initialReviewsCount = reviewJpaRepository.count();
        long initialTargetsCount = reviewTargetJpaRepository.count();

        CreateReviewCommand cmd = new CreateReviewCommand(
                user.getId(),
                null,
                "Tentativa com target do meio inválido",
                false,
                "PUBLIC",
                List.of(
                        new CreateReviewTargetCommand(targetA.getId(), new BigDecimal("4.0"), "Alvo A válido"),
                        new CreateReviewTargetCommand(targetBInvalid, new BigDecimal("3.0"), "Alvo B inexistente"),
                        new CreateReviewTargetCommand(targetC.getId(), new BigDecimal("5.0"), "Alvo C válido")
                )
        );

        // Operação inteira deve falhar
        BusinessException rollbackEx = assertThrows(BusinessException.class, () -> reviewService.createReview(cmd));
        assertNotNull(rollbackEx);

        // Provar diretamente no PostgreSQL que NADA foi persistido
        long finalReviewsCount = reviewJpaRepository.count();
        long finalTargetsCount = reviewTargetJpaRepository.count();

        assertEquals(initialReviewsCount, finalReviewsCount, "Nenhum Review deve ser persistido quando a validação de target falhar");
        assertEquals(initialTargetsCount, finalTargetsCount, "Nenhum ReviewTarget deve ser persistido quando a validação de target falhar");
    }

    @Test
    @DisplayName("Teste de Rollback Transacional Real: Falha durante inserção de target reverte Review persistido")
    void shouldRollbackPersistedReviewWhenTargetPersistenceFailsInTransaction() {
        User user = createActiveUser();
        RateableTarget target = createRateableTarget(TargetType.PLACE);

        long initialReviewsCount = reviewJpaRepository.count();
        long initialTargetsCount = reviewTargetJpaRepository.count();

        TransactionTemplate template = new TransactionTemplate(transactionManager);

        RuntimeException txEx = assertThrows(RuntimeException.class, () ->
                template.execute(status -> {
                    // 1. Salva Review com sucesso
                    Review review = new Review(UUID.randomUUID(), user.getId(), null, "Teste rollback", false, null, null, null);
                    reviewRepository.save(review);

                    // 2. Salva primeiro target com sucesso
                    ReviewTarget target1 = new ReviewTarget(UUID.randomUUID(), review.getId(), target.getId(), new BigDecimal("4.0"), "Ok");
                    reviewTargetRepository.save(target1);

                    // 3. Força erro intencional para provocar ROLLBACK da transação
                    throw new RuntimeException("Simulação de falha catastrófica no meio da transação");
                })
        );
        assertNotNull(txEx);

        // Provar que nem a Review nem o ReviewTarget permaneceram no banco PostgreSQL
        long finalReviewsCount = reviewJpaRepository.count();
        long finalTargetsCount = reviewTargetJpaRepository.count();

        assertEquals(initialReviewsCount, finalReviewsCount, "O Review inserido antes da falha deve ter sido completamente revertido pelo ROLLBACK");
        assertEquals(initialTargetsCount, finalTargetsCount, "O ReviewTarget inserido antes da falha deve ter sido completamente revertido pelo ROLLBACK");
    }
}
