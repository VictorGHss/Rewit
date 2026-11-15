package com.rewit.presentation.controller;

import com.rewit.application.dto.checkin.CheckInDtos.MyCheckInView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.usecase.ListMyCheckInsUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.checkin.MyCheckInResponse;
import com.rewit.presentation.dto.common.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Check-ins do usuário autenticado. O usuário vem somente do token; nenhum identificador enviado pelo cliente
 * escolhe a conta.
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Check-ins do Usuário", description = "Histórico de check-ins verificados do usuário autenticado")
public class MyCheckInsController {

    private final ListMyCheckInsUseCase listMyCheckInsUseCase;

    public MyCheckInsController(ListMyCheckInsUseCase listMyCheckInsUseCase) {
        this.listMyCheckInsUseCase = Objects.requireNonNull(listMyCheckInsUseCase,
                "ListMyCheckInsUseCase must not be null");
    }

    /** GET /api/v1/me/check-ins — check-ins verificados, do mais recente ao mais antigo. */
    @GetMapping("/check-ins")
    @Operation(summary = "Listar os check-ins verificados do usuário autenticado",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PagedResponse<MyCheckInResponse>> getMyCheckIns(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        PageResult<MyCheckInView> result = listMyCheckInsUseCase.execute(actorUserId, page, size);
        return ResponseEntity.ok(new PagedResponse<>(
                result.content().stream().map(MyCheckInResponse::fromView).toList(),
                result.pageNumber(),
                result.pageSize(),
                result.totalElements(),
                result.totalPages(),
                result.isLast()
        ));
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Identificador de usuário inválido na sessão", HttpStatus.UNAUTHORIZED,
                    "UNAUTHORIZED");
        }
    }
}
