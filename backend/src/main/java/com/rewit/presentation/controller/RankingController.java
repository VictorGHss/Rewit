package com.rewit.presentation.controller;

import com.rewit.application.dto.ranking.RankingDtos.RankingResultDto;
import com.rewit.application.usecase.GetRankingUseCase;
import com.rewit.domain.enums.RankingPeriod;
import com.rewit.domain.enums.RankingType;
import com.rewit.presentation.dto.ranking.RankingPresentationDtos.RankingPagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Controlador REST para Rankings Locais e Dimensionais V1 (ADR-016).
 *
 * <p>Disponibiliza três rankings independentes:
 * <ol>
 *   <li>Check-ins verificados elegíveis ({@code /check-ins})</li>
 *   <li>Locais distintos explorados com avaliações públicas ({@code /places-explored})</li>
 *   <li>Votos úteis recebidos em avaliações públicas ({@code /helpful-votes})</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/rankings")
@Tag(name = "Rankings V1", description = "Rankings locais e dimensionais independentes")
public class RankingController {

    private final GetRankingUseCase getRankingUseCase;

    public RankingController(GetRankingUseCase getRankingUseCase) {
        this.getRankingUseCase = Objects.requireNonNull(getRankingUseCase, "GetRankingUseCase must not be null");
    }

    @GetMapping("/check-ins")
    @Operation(summary = "Ranking de check-ins presenciais verificados")
    public ResponseEntity<RankingPagedResponse> getCheckInsRanking(
            @RequestParam(name = "period", defaultValue = "WEEK") String period,
            @RequestParam(name = "state") String state,
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication
    ) {
        RankingPeriod rankingPeriod = RankingPeriod.fromString(period);
        UUID currentUserId = extractOptionalUserId(authentication);

        RankingResultDto result = getRankingUseCase.execute(
                RankingType.CHECK_INS,
                rankingPeriod,
                state,
                city,
                category,
                page,
                size,
                currentUserId
        );

        return ResponseEntity.ok(RankingPagedResponse.fromResult(result));
    }

    @GetMapping("/places-explored")
    @Operation(summary = "Ranking de locais distintos explorados e avaliados")
    public ResponseEntity<RankingPagedResponse> getPlacesExploredRanking(
            @RequestParam(name = "period", defaultValue = "WEEK") String period,
            @RequestParam(name = "state") String state,
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication
    ) {
        RankingPeriod rankingPeriod = RankingPeriod.fromString(period);
        UUID currentUserId = extractOptionalUserId(authentication);

        RankingResultDto result = getRankingUseCase.execute(
                RankingType.PLACES_EXPLORED,
                rankingPeriod,
                state,
                city,
                category,
                page,
                size,
                currentUserId
        );

        return ResponseEntity.ok(RankingPagedResponse.fromResult(result));
    }

    @GetMapping("/helpful-votes")
    @Operation(summary = "Ranking de votos úteis recebidos em avaliações públicas")
    public ResponseEntity<RankingPagedResponse> getHelpfulVotesRanking(
            @RequestParam(name = "period", defaultValue = "WEEK") String period,
            @RequestParam(name = "state") String state,
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication
    ) {
        RankingPeriod rankingPeriod = RankingPeriod.fromString(period);
        UUID currentUserId = extractOptionalUserId(authentication);

        RankingResultDto result = getRankingUseCase.execute(
                RankingType.HELPFUL_VOTES,
                rankingPeriod,
                state,
                city,
                category,
                page,
                size,
                currentUserId
        );

        return ResponseEntity.ok(RankingPagedResponse.fromResult(result));
    }

    private UUID extractOptionalUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
