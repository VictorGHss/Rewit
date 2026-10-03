package com.rewit.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Domínio: ReviewMediaObjectKey (Step 28.1)")
class ReviewMediaObjectKeyTest {

    private static final String REVIEW_ID = UUID.randomUUID().toString();
    private static final String MEDIA_ID = UUID.randomUUID().toString();

    @Test
    @DisplayName("Aceita as chaves no formato gerado pelo upload (jpg e png)")
    void acceptsGeneratedKeys() {
        assertTrue(ReviewMediaObjectKey.isManagedKey("reviews/" + REVIEW_ID + "/" + MEDIA_ID + "/image.jpg"));
        assertTrue(ReviewMediaObjectKey.isManagedKey("reviews/" + REVIEW_ID + "/" + MEDIA_ID + "/image.png"));
    }

    @Test
    @DisplayName("Rejeita chaves fora do formato, mesmo sob o prefixo gerenciado")
    void rejectsKeysOutsideTheGeneratedFormat() {
        assertFalse(ReviewMediaObjectKey.isManagedKey(null));
        assertFalse(ReviewMediaObjectKey.isManagedKey("reviews/"));
        assertFalse(ReviewMediaObjectKey.isManagedKey("reviews/" + REVIEW_ID + "/" + MEDIA_ID + "/image.gif"));
        assertFalse(ReviewMediaObjectKey.isManagedKey("reviews/" + REVIEW_ID + "/" + MEDIA_ID + "/original.jpg"));
        assertFalse(ReviewMediaObjectKey.isManagedKey("reviews/" + REVIEW_ID + "/image.jpg"));
        assertFalse(ReviewMediaObjectKey.isManagedKey("reviews/" + REVIEW_ID.toUpperCase() + "/" + MEDIA_ID + "/image.jpg"));
        assertFalse(ReviewMediaObjectKey.isManagedKey("reviews/" + REVIEW_ID + "/" + MEDIA_ID + "/image.jpg/extra"));
        assertFalse(ReviewMediaObjectKey.isManagedKey("archive/reviews/" + REVIEW_ID + "/" + MEDIA_ID + "/image.jpg"));
    }

    @Test
    @DisplayName("Namespace gerenciado exige o separador após reviews")
    void managedNamespaceRequiresExactPrefix() {
        assertTrue(ReviewMediaObjectKey.isInManagedNamespace("reviews/qualquer"));
        assertFalse(ReviewMediaObjectKey.isInManagedNamespace("reviews-archive/x.jpg"));
        assertFalse(ReviewMediaObjectKey.isInManagedNamespace("avatars/x.jpg"));
        assertFalse(ReviewMediaObjectKey.isInManagedNamespace(null));
    }

    @Test
    @DisplayName("reviewIdOf extrai a review dona da chave e rejeita chaves fora do formato (Step 28.3)")
    void reviewIdOfExtractsOwningReview() {
        assertEquals(UUID.fromString(REVIEW_ID),
                ReviewMediaObjectKey.reviewIdOf("reviews/" + REVIEW_ID + "/" + MEDIA_ID + "/image.png"));
        assertThrows(IllegalArgumentException.class, () -> ReviewMediaObjectKey.reviewIdOf("reviews/manual-upload.jpg"));
        assertThrows(IllegalArgumentException.class, () -> ReviewMediaObjectKey.reviewIdOf(null));
    }
}
