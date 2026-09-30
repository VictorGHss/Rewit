package com.rewit.application.dto.feed;

import com.rewit.domain.feed.RankedFeedCandidate;

import java.util.List;

/**
 * Representação interna da página de candidatos do Feed V2 recortada sobre a janela do ranker (Step 24.4.1).
 *
 * <p>Não é DTO HTTP: não expõe dados públicos de autores ou reviews e não substitui o
 * PagedResponse oficial. Reflete exclusivamente o estado da candidate window ranqueada
 * e diversificada antes da hidratação.</p>
 */
public record FeedV2CandidatePage(
        List<RankedFeedCandidate> items,
        int page,
        int size,
        int windowSize
) {
    public FeedV2CandidatePage {
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

    public boolean isLast() {
        int tp = totalPages();
        return tp == 0 || page >= tp - 1;
    }
}
