package com.rewit.presentation.dto.ranking;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rewit.application.dto.ranking.RankingDtos.RankingItemDto;
import com.rewit.application.dto.ranking.RankingDtos.RankingMetadataDto;
import com.rewit.application.dto.ranking.RankingDtos.RankingResultDto;
import com.rewit.application.dto.ranking.RankingDtos.RankingUserDto;
import com.rewit.application.dto.ranking.RankingPreferencesDto;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTOs de apresentação HTTP para a API de Rankings e Preferências V1 (ADR-016).
 */
public final class RankingPresentationDtos {

    private RankingPresentationDtos() {
    }

    public record RankingPreferencesResponse(
            boolean showInRankings
    ) {
        public static RankingPreferencesResponse fromDto(RankingPreferencesDto dto) {
            return new RankingPreferencesResponse(dto.showInRankings());
        }
    }

    public record UpdateRankingPreferencesRequest(
            @NotNull(message = "O campo showInRankings é obrigatório")
            Boolean showInRankings
    ) {
    }

    public record RankingUserResponse(
            UUID id,
            String handle,
            String displayName,
            String avatarUrl
    ) {
        public static RankingUserResponse fromDto(RankingUserDto dto) {
            return new RankingUserResponse(
                    dto.id(),
                    dto.handle(),
                    dto.displayName(),
                    dto.avatarUrl()
            );
        }
    }

    public record RankingItemResponse(
            int rankPosition,
            RankingUserResponse user,
            long score,
            Instant lastContributedAt,
            @JsonProperty("isCurrentUser")
            boolean isCurrentUser
    ) {
        public static RankingItemResponse fromDto(RankingItemDto dto) {
            return new RankingItemResponse(
                    dto.rankPosition(),
                    RankingUserResponse.fromDto(dto.user()),
                    dto.score(),
                    dto.lastContributedAt(),
                    dto.isCurrentUser()
            );
        }
    }

    public record RankingMetadataResponse(
            String rankingType,
            String period,
            String state,
            String city,
            String category,
            Instant effectiveStart,
            Instant periodEnd,
            @JsonProperty("isPartialPeriod")
            boolean isPartialPeriod
    ) {
        public static RankingMetadataResponse fromDto(RankingMetadataDto dto) {
            return new RankingMetadataResponse(
                    dto.rankingType().name(),
                    dto.period().name(),
                    dto.state(),
                    dto.city(),
                    dto.category() != null ? dto.category().name() : null,
                    dto.effectiveStart(),
                    dto.periodEnd(),
                    dto.isPartialPeriod()
            );
        }
    }

    public record RankingPagedResponse(
            List<RankingItemResponse> content,
            int pageNumber,
            int pageSize,
            long totalElements,
            int totalPages,
            @JsonProperty("isLast")
            boolean isLast,
            RankingMetadataResponse metadata
    ) {
        public static RankingPagedResponse fromResult(RankingResultDto result) {
            return new RankingPagedResponse(
                    result.content().stream().map(RankingItemResponse::fromDto).toList(),
                    result.pageNumber(),
                    result.pageSize(),
                    result.totalElements(),
                    result.totalPages(),
                    result.isLast(),
                    RankingMetadataResponse.fromDto(result.metadata())
            );
        }
    }
}

