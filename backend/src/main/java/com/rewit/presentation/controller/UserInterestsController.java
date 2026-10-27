package com.rewit.presentation.controller;

import com.rewit.application.dto.user.UserInterestDtos.InterestCatalogView;
import com.rewit.application.usecase.GetMyInterestsUseCase;
import com.rewit.application.usecase.UpdateMyInterestsUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.user.UserInterestPresentationDtos.InterestCatalogResponse;
import com.rewit.presentation.dto.user.UserInterestPresentationDtos.UpdateMyInterestsRequest;
import com.rewit.presentation.dto.user.UserInterestPresentationDtos.UserInterestsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Interesses do usuário: catálogo do questionário inicial, consulta e gravação dos interesses de quem está autenticado.
 * O usuário vem somente do token; nenhum identificador enviado pelo cliente escolhe a conta.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Interesses do Usuário", description = "Questionário inicial e interesses do usuário autenticado")
public class UserInterestsController {

    private final GetMyInterestsUseCase getMyInterestsUseCase;
    private final UpdateMyInterestsUseCase updateMyInterestsUseCase;

    public UserInterestsController(GetMyInterestsUseCase getMyInterestsUseCase,
                                   UpdateMyInterestsUseCase updateMyInterestsUseCase) {
        this.getMyInterestsUseCase = Objects.requireNonNull(getMyInterestsUseCase,
                "GetMyInterestsUseCase must not be null");
        this.updateMyInterestsUseCase = Objects.requireNonNull(updateMyInterestsUseCase,
                "UpdateMyInterestsUseCase must not be null");
    }

    /** GET /api/v1/interests/catalog — perguntas, códigos e rótulos aceitos pelo PUT. */
    @GetMapping("/interests/catalog")
    @Operation(summary = "Listar o catálogo de interesses do questionário inicial",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<InterestCatalogResponse> getCatalog(Authentication authentication) {
        extractAuthenticatedUserId(authentication);
        return ResponseEntity.ok(InterestCatalogResponse.fromView(InterestCatalogView.current()));
    }

    /** GET /api/v1/me/interests — interesses gravados e situação do questionário. */
    @GetMapping("/me/interests")
    @Operation(summary = "Consultar os interesses e o questionário inicial do usuário autenticado",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<UserInterestsResponse> getMyInterests(Authentication authentication) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        return ResponseEntity.ok(UserInterestsResponse.fromView(getMyInterestsUseCase.execute(actorUserId)));
    }

    /** PUT /api/v1/me/interests — substitui os interesses; grava rascunho ou conclui o questionário. */
    @PutMapping("/me/interests")
    @Operation(summary = "Gravar os interesses do usuário autenticado", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<UserInterestsResponse> updateMyInterests(@Valid @RequestBody UpdateMyInterestsRequest request,
                                                                   Authentication authentication) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        return ResponseEntity.ok(UserInterestsResponse.fromView(
                updateMyInterestsUseCase.execute(request.toCommand(actorUserId))));
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
