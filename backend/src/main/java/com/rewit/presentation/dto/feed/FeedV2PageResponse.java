package com.rewit.presentation.dto.feed;

import com.rewit.application.dto.feed.FeedV2PageProjection;
import com.rewit.application.dto.feed.FeedV2Projection;

import java.util.List;

/**
 * Envelope HTTP padronizado para a resposta paginada do Feed V2 (/api/v2/feed) - Step 24.4.3.
 *
 * <p>Expõe os itens públicos hidratados e os metadados da fatia correspondente à janela
 * de candidatos ranqueados e diversificados.</p>
 */
public record FeedV2PageResponse(
        List<FeedV2Projection> items,
        int page,
        int size,
        int windowSize,
        int totalPages
) {
    public static FeedV2PageResponse from(FeedV2PageProjection projection) {
        if (projection == null) {
            return new FeedV2PageResponse(List.of(), 0, 10, 0, 0);
        }
        return new FeedV2PageResponse(
                projection.items(),
                projection.page(),
                projection.size(),
                projection.windowSize(),
                projection.totalPages()
        );
    }
}
