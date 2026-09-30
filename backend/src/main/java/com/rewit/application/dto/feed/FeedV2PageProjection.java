package com.rewit.application.dto.feed;

import java.util.List;

/**
 * Projeção pública de uma página do Feed V2 (Step 24.4.2).
 *
 * <p>Envelopa os itens hidratados preservando a ordem do ranker/diversifier,
 * juntamente com os metadados de paginação correspondentes.</p>
 */
public record FeedV2PageProjection(
        List<FeedV2Projection> items,
        int page,
        int size,
        int windowSize
) {
    public FeedV2PageProjection {
        items = items != null ? List.copyOf(items) : List.of();
        if (page < 0) {
            throw new IllegalArgumentException("page cannot be negative");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        if (windowSize < 0) {
            throw new IllegalArgumentException("windowSize cannot be negative");
        }
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int totalPages() {
        return size > 0 ? (int) Math.ceil((double) windowSize / size) : 0;
    }
}
