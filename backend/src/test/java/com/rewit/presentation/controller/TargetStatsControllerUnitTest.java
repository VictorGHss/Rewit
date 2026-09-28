package com.rewit.presentation.controller;

import com.rewit.application.dto.ReviewDto.TargetStatsView;
import com.rewit.application.service.ReviewService;
import com.rewit.common.exception.BusinessException;
import com.rewit.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: TargetStatsController (Step 13.0)")
class TargetStatsControllerUnitTest {

    @Mock
    private ReviewService reviewService;

    @InjectMocks
    private TargetStatsController targetStatsController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(targetStatsController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("Deve retornar 200 OK com estatísticas completas quando o alvo possui avaliações")
    void shouldReturn200WithStatsWhenFound() throws Exception {
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-28T18:00:00Z");
        TargetStatsView stats = new TargetStatsView(targetId, new BigDecimal("4.50"), 12, now);

        when(reviewService.getTargetStats(targetId)).thenReturn(stats);

        mockMvc.perform(get("/api/v1/targets/{id}/stats", targetId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.targetId").value(targetId.toString()))
                .andExpect(jsonPath("$.averageRating").value(4.50))
                .andExpect(jsonPath("$.reviewsCount").value(12))
                .andExpect(jsonPath("$.lastCalculatedAt").value("2026-09-28T18:00:00Z"));
    }

    @Test
    @DisplayName("Deve retornar 200 OK com valores padrão (0.00 / 0) quando o alvo existe mas não possui avaliações")
    void shouldReturn200WithDefaultStatsWhenTargetHasNoReviews() throws Exception {
        UUID targetId = UUID.randomUUID();
        TargetStatsView defaultStats = new TargetStatsView(targetId, new BigDecimal("0.00"), 0, null);

        when(reviewService.getTargetStats(targetId)).thenReturn(defaultStats);

        mockMvc.perform(get("/api/v1/targets/{id}/stats", targetId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetId").value(targetId.toString()))
                .andExpect(jsonPath("$.averageRating").value(0.00))
                .andExpect(jsonPath("$.reviewsCount").value(0))
                .andExpect(jsonPath("$.lastCalculatedAt").doesNotExist());
    }

    @Test
    @DisplayName("Deve retornar 404 Not Found RFC 7807 quando o alvo não existe em rateable_targets")
    void shouldReturn404WhenTargetNotFound() throws Exception {
        UUID missingId = UUID.randomUUID();

        when(reviewService.getTargetStats(missingId))
                .thenThrow(new BusinessException("Alvo avaliável não encontrado", HttpStatus.NOT_FOUND, "RATEABLE_TARGET_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/targets/{id}/stats", missingId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RATEABLE_TARGET_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Alvo avaliável não encontrado"));
    }
}
