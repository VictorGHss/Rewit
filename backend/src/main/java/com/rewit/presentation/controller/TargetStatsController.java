package com.rewit.presentation.controller;

import com.rewit.application.dto.ReviewDto.TargetStatsView;
import com.rewit.application.service.ReviewService;
import com.rewit.presentation.dto.target.TargetStatsResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Controlador REST para consulta de estatísticas agregadas de alvos avaliáveis (Step 13.0).
 */
@RestController
@RequestMapping("/api/v1/targets")
public class TargetStatsController {

    private final ReviewService reviewService;

    public TargetStatsController(ReviewService reviewService) {
        this.reviewService = Objects.requireNonNull(reviewService, "reviewService must not be null");
    }

    @GetMapping("/{id}/stats")
    public ResponseEntity<TargetStatsResponse> getTargetStats(@PathVariable UUID id) {
        TargetStatsView view = reviewService.getTargetStats(id);
        return ResponseEntity.ok(new TargetStatsResponse(
                view.targetId(),
                view.averageRating(),
                view.reviewsCount(),
                view.lastCalculatedAt()
        ));
    }
}
