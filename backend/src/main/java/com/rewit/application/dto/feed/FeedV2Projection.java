package com.rewit.application.dto.feed;

import com.rewit.application.dto.ReviewDto.PublicAuthorView;
import com.rewit.application.dto.ReviewDto.ReviewTargetView;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Projeção pública de uma avaliação no Feed V2 (Step 24.4.2).
 *
 * <p>Representa a visão pública da avaliação sem expor sinais internos de ranking
 * (score, isDirectFollow, sinais brutos de relevância) e garantindo estritamente a preservação
 * do anonimato quando aplicável (sem autorId interno, perfil ou identificadores expostos).</p>
 */
public record FeedV2Projection(
        UUID id,
        PublicAuthorView author,
        UUID contextPlaceId,
        String experienceText,
        boolean isAnonymous,
        boolean isVerifiedOnSite,
        String visibility,
        String status,
        Instant createdAt,
        Instant updatedAt,
        List<ReviewTargetView> targets,
        long helpfulCount,
        boolean isHelpfulByMe
) {
    public FeedV2Projection {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(author, "author is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        targets = targets != null ? List.copyOf(targets) : List.of();
    }
}
