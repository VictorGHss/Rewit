package com.rewit.presentation.controller;

import com.rewit.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoint de verificação de integridade e prontidão da API Rewit.
 */
@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "Health Check", description = "Verificação de status e integridade da infraestrutura")
public class HealthController {

    @GetMapping
    @Operation(summary = "Verifica se a API central está operacional")
    public ResponseEntity<ApiResponse<Map<String, String>>> checkHealth() {
        Map<String, String> status = Map.of(
                "status", "UP",
                "app", "Rewit Backend",
                "version", "0.1.0",
                "environment", "local"
        );
        return ResponseEntity.ok(ApiResponse.ok(status));
    }
}
