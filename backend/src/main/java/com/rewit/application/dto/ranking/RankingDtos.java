package com.rewit.application.dto.ranking;

import com.rewit.domain.enums.InterestCategory;
import com.rewit.domain.enums.RankingPeriod;
import com.rewit.domain.enums.RankingType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Agrupador de DTOs da aplicação para Rankings V1.
 */
public final class RankingDtos {

    private RankingDtos() {
    }

    public record RankingUserDto(
            UUID id,
            String handle,
            String displayName,
            String avatarUrl
    ) {
    }

    public record RankingItemDto(
            int rankPosition,
            RankingUserDto user,
            long score,
            Instant lastContributedAt,
            boolean isCurrentUser
    ) {
    }

    public record RankingMetadataDto(
            RankingType rankingType,
            RankingPeriod period,
            String state,
            String city,
            InterestCategory category,
            Instant effectiveStart,
            Instant periodEnd,
            boolean isPartialPeriod
    ) {
    }

    public record RankingResultDto(
            List<RankingItemDto> content,
            int pageNumber,
            int pageSize,
            long totalElements,
            int totalPages,
            boolean isLast,
            RankingMetadataDto metadata
    ) {
    }
}

