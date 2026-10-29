package com.rewit.application.usecase;

import com.rewit.application.dto.ranking.RankingDtos.RankingItemDto;
import com.rewit.application.dto.ranking.RankingDtos.RankingMetadataDto;
import com.rewit.application.dto.ranking.RankingDtos.RankingResultDto;
import com.rewit.application.dto.ranking.RankingDtos.RankingUserDto;
import com.rewit.application.port.RankingConfigurationRepository;
import com.rewit.application.port.RankingRepository;
import com.rewit.application.service.RankingTimeWindowCalculator;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.InterestCategory;
import com.rewit.domain.enums.RankingPeriod;
import com.rewit.domain.enums.RankingType;
import com.rewit.domain.feed.TargetCategoryMapper;
import com.rewit.domain.model.BrazilianState;
import com.rewit.domain.model.RankingItem;
import com.rewit.domain.model.RankingTimeWindow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Caso de uso principal para consultas aos Rankings Dimensionais e Locais V1 (ADR-016).
 *
 * <p>Aplica:
 * <ul>
 *   <li>Validação obrigatória de UF ({@link BrazilianState}).</li>
 *   <li>Filtro opcional de cidade normalizada (sem bairro).</li>
 *   <li>Filtro opcional de categoria canônica ({@link InterestCategory}) mapeada via {@link TargetCategoryMapper}.</li>
 *   <li>Janela temporal civil em America/Sao_Paulo truncada na ativação persistente.</li>
 *   <li>Teto rígido de TOP 100 participantes por ranking, com paginação sob demanda.</li>
 *   <li>Identificação contextual de {@code isCurrentUser} para usuário autenticado.</li>
 * </ul>
 */
@Service
public class GetRankingUseCase {

    public static final int MAX_PARTICIPANTS_PER_RANKING = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;

    private final RankingRepository rankingRepository;
    private final RankingConfigurationRepository rankingConfigurationRepository;
    private final RankingTimeWindowCalculator timeWindowCalculator;

    public GetRankingUseCase(
            RankingRepository rankingRepository,
            RankingConfigurationRepository rankingConfigurationRepository,
            RankingTimeWindowCalculator timeWindowCalculator
    ) {
        this.rankingRepository = Objects.requireNonNull(rankingRepository, "RankingRepository must not be null");
        this.rankingConfigurationRepository = Objects.requireNonNull(rankingConfigurationRepository,
                "RankingConfigurationRepository must not be null");
        this.timeWindowCalculator = Objects.requireNonNull(timeWindowCalculator,
                "RankingTimeWindowCalculator must not be null");
    }

    @Transactional(readOnly = true)
    public RankingResultDto execute(
            RankingType rankingType,
            RankingPeriod period,
            String stateCode,
            String rawCity,
            String rawCategory,
            int page,
            int size,
            UUID currentUserId
    ) {
        Objects.requireNonNull(rankingType, "rankingType must not be null");
        RankingPeriod resolvedPeriod = period != null ? period : RankingPeriod.WEEK;

        if (stateCode == null || stateCode.isBlank()) {
            throw new BusinessException("O estado (UF) é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_STATE");
        }

        BrazilianState state = BrazilianState.fromCode(stateCode)
                .orElseThrow(() -> new BusinessException("UF inválida: " + stateCode + ". Use uma sigla válida de estado brasileiro.",
                        HttpStatus.BAD_REQUEST, "INVALID_STATE"));

        InterestCategory resolvedCategory = null;
        Set<String> categoryTerms = Collections.emptySet();
        if (rawCategory != null && !rawCategory.isBlank()) {
            resolvedCategory = resolveInterestCategory(rawCategory);
            categoryTerms = TargetCategoryMapper.getMatchingTerms(resolvedCategory);
        }

        String normalizedCity = (rawCity != null && !rawCity.isBlank()) ? rawCity.trim() : null;

        int validPage = Math.max(0, page);
        int validSize = (size <= 0) ? DEFAULT_PAGE_SIZE : Math.min(MAX_PAGE_SIZE, size);

        Instant rankingsActivatedAt = rankingConfigurationRepository.getRankingsActivatedAt();
        RankingTimeWindow window = timeWindowCalculator.calculateWindow(resolvedPeriod, Instant.now(), rankingsActivatedAt);

        RankingMetadataDto metadata = new RankingMetadataDto(
                rankingType,
                resolvedPeriod,
                state.name(),
                normalizedCity,
                resolvedCategory,
                window.effectiveStart(),
                window.periodEnd(),
                window.isPartialPeriod()
        );

        if (!window.isActive()) {
            return new RankingResultDto(List.of(), validPage, validSize, 0L, 0, true, metadata);
        }

        List<RankingItem> allItems = fetchTopItems(rankingType, window, state, normalizedCity, categoryTerms);
        long totalElements = allItems.size();
        int totalPages = validSize > 0 ? (int) Math.ceil((double) totalElements / validSize) : 0;
        int fromIndex = validPage * validSize;

        if (fromIndex >= totalElements) {
            return new RankingResultDto(List.of(), validPage, validSize, totalElements, totalPages, true, metadata);
        }

        int toIndex = Math.min(fromIndex + validSize, (int) totalElements);
        List<RankingItem> pageSlice = allItems.subList(fromIndex, toIndex);
        boolean isLast = totalPages == 0 || validPage >= totalPages - 1;

        List<RankingItemDto> content = pageSlice.stream()
                .map(item -> new RankingItemDto(
                        item.rankPosition(),
                        new RankingUserDto(
                                item.userId(),
                                item.handle(),
                                item.displayName(),
                                item.avatarUrl()
                        ),
                        item.score(),
                        item.lastContributedAt(),
                        currentUserId != null && currentUserId.equals(item.userId())
                ))
                .toList();

        return new RankingResultDto(content, validPage, validSize, totalElements, totalPages, isLast, metadata);
    }

    private List<RankingItem> fetchTopItems(
            RankingType rankingType,
            RankingTimeWindow window,
            BrazilianState state,
            String city,
            Set<String> categoryTerms
    ) {
        return switch (rankingType) {
            case CHECK_INS -> rankingRepository.findCheckInsRanking(
                    window.effectiveStart(),
                    window.periodEnd(),
                    state,
                    city,
                    categoryTerms,
                    MAX_PARTICIPANTS_PER_RANKING
            );
            case PLACES_EXPLORED -> rankingRepository.findPlacesExploredRanking(
                    window.effectiveStart(),
                    window.periodEnd(),
                    state,
                    city,
                    categoryTerms,
                    MAX_PARTICIPANTS_PER_RANKING
            );
            case HELPFUL_VOTES -> rankingRepository.findHelpfulVotesRanking(
                    window.effectiveStart(),
                    window.periodEnd(),
                    state,
                    city,
                    categoryTerms,
                    MAX_PARTICIPANTS_PER_RANKING
            );
        };
    }

    private InterestCategory resolveInterestCategory(String rawCategory) {
        String trimmed = rawCategory.trim();
        try {
            return InterestCategory.valueOf(trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return TargetCategoryMapper.map(trimmed)
                    .orElseThrow(() -> new BusinessException(
                            "Categoria inválida: " + rawCategory + ". Utilize um código canônico de InterestCategory.",
                            HttpStatus.BAD_REQUEST,
                            "INVALID_CATEGORY"
                    ));
        }
    }
}

