package com.rewit.infrastructure.persistence;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.ReviewDetailView;
import com.rewit.application.dto.storage.StorageReconciliationDtos.ReviewMediaReference;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.ReviewMediaRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.ReviewService;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.ReviewMedia;
import com.rewit.domain.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração com PostgreSQL Real: referências de mídia para reconciliação (Step 28.1)")
class ReviewMediaReferenceIntegrationTest {

    @Autowired
    private ReviewMediaRepository reviewMediaRepository;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateableTargetRepository targetRepository;

    @Test
    @DisplayName("Busca referências ACTIVE e REMOVED pelas chaves e omite chaves sem linha")
    void findsReferencesByObjectKeysInAnyStatus() {
        User author = createUser();
        ReviewDetailView review = createReview(author);
        ReviewMedia active = saveMedia(review, author);
        ReviewMedia removed = saveMedia(review, author);
        removed.markRemoved();
        reviewMediaRepository.save(removed);
        String missingKey = "reviews/" + UUID.randomUUID() + "/" + UUID.randomUUID() + "/image.jpg";

        List<ReviewMediaReference> references = reviewMediaRepository
                .findReferencesByObjectKeys(List.of(active.getObjectKey(), removed.getObjectKey(), missingKey))
                .stream()
                .sorted(Comparator.comparing(reference -> reference.status()))
                .toList();

        assertEquals(2, references.size());
        assertReference(active, ReviewMediaStatus.ACTIVE, references.get(0));
        assertReference(removed, ReviewMediaStatus.REMOVED, references.get(1));
        assertTrue(reviewMediaRepository.findReferencesByObjectKeys(List.of()).isEmpty());
    }

    private static void assertReference(ReviewMedia media, ReviewMediaStatus status, ReviewMediaReference reference) {
        assertEquals(media.getId(), reference.mediaId());
        assertEquals(media.getReviewId(), reference.reviewId());
        assertEquals(media.getObjectKey(), reference.objectKey());
        assertEquals(status, reference.status());
        assertNotNull(reference.createdAt());
        assertNotNull(reference.updatedAt());
    }

    // Somente metadados: nenhum objeto é gravado ou removido no storage
    private ReviewMedia saveMedia(ReviewDetailView review, User author) {
        UUID mediaId = UUID.randomUUID();
        return reviewMediaRepository.save(new ReviewMedia(
                mediaId,
                review.id(),
                author.getId(),
                "reviews/" + review.id() + "/" + mediaId + "/image.jpg",
                ReviewMediaType.IMAGE,
                "image/jpeg",
                1024,
                10,
                10
        ));
    }

    private User createUser() {
        String unique = "ref_" + UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.save(new User(null, unique + "@rewit.test", "Password123!", AuthProvider.LOCAL, null));
        profileRepository.save(new Profile(UUID.randomUUID(), user.getId(), "u_" + unique, "User " + unique, "Bio", null));
        return user;
    }

    private ReviewDetailView createReview(User user) {
        String unique = UUID.randomUUID().toString().substring(0, 8);

        Place place = placeRepository.save(new Place(
                null, "Restaurante Referência " + unique, "restaurante-ref-" + unique,
                "RESTAURANTE", "Descrição", "Rua Teste, 100", "100", "Centro", "São Paulo", "SP", "BR",
                -23.5505, -46.6333, 50, "USER", false, null, "ACTIVE"
        ));
        RateableTarget target = targetRepository.save(new RateableTarget(UUID.randomUUID(), TargetType.PLACE));

        return reviewService.createReview(new CreateReviewCommand(
                user.getId(),
                place.getId(),
                "Review para teste de referências de mídia",
                false,
                "PUBLIC",
                null,
                null,
                null,
                List.of(new CreateReviewTargetCommand(target.getId(), new BigDecimal("4.0"), "Bom"))
        ));
    }
}
