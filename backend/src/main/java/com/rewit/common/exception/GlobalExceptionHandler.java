package com.rewit.common.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Tratamento global centralizado de exceções HTTP no padrão RFC 7807 (Problem Details).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusinessException(BusinessException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle("Regra de Negócio Violada");
        problem.setType(URI.create("https://api.rewit.app/errors/" + ex.getErrorCode().toLowerCase()));
        problem.setProperty("code", ex.getErrorCode());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String detail = ex.getMessage() != null ? ex.getMessage() : "";
        Throwable rootCause = ex.getRootCause();
        if (rootCause != null && rootCause.getMessage() != null) {
            detail += " " + rootCause.getMessage();
        }
        String lowerDetail = detail.toLowerCase();

        if (lowerDetail.contains("uq_profiles_handle") || lowerDetail.contains("uq_profiles_handle_lower")) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Nome de usuário (@handle) já está em uso");
            problem.setTitle("Regra de Negócio Violada");
            problem.setType(URI.create("https://api.rewit.app/errors/handle_already_exists"));
            problem.setProperty("code", "HANDLE_ALREADY_EXISTS");
            problem.setProperty("timestamp", Instant.now());
            return problem;
        }

        if (lowerDetail.contains("uq_users_email") || lowerDetail.contains("uq_users_email_lower")) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "E-mail já cadastrado na plataforma");
            problem.setTitle("Regra de Negócio Violada");
            problem.setType(URI.create("https://api.rewit.app/errors/email_already_exists"));
            problem.setProperty("code", "EMAIL_ALREADY_EXISTS");
            problem.setProperty("timestamp", Instant.now());
            return problem;
        }

        if (lowerDetail.contains("uq_places_slug")) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Slug do local já está em uso");
            problem.setTitle("Regra de Negócio Violada");
            problem.setType(URI.create("https://api.rewit.app/errors/place_slug_already_exists"));
            problem.setProperty("code", "PLACE_SLUG_ALREADY_EXISTS");
            problem.setProperty("timestamp", Instant.now());
            return problem;
        }

        if (lowerDetail.contains("uq_product_identifier")) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Identificador de produto (código de barras) já cadastrado");
            problem.setTitle("Regra de Negócio Violada");
            problem.setType(URI.create("https://api.rewit.app/errors/identifier_already_exists"));
            problem.setProperty("code", "IDENTIFIER_ALREADY_EXISTS");
            problem.setProperty("timestamp", Instant.now());
            return problem;
        }

        if (lowerDetail.contains("uq_product_place")) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Este produto já está associado a este local");
            problem.setTitle("Regra de Negócio Violada");
            problem.setType(URI.create("https://api.rewit.app/errors/product_presence_already_exists"));
            problem.setProperty("code", "PRODUCT_PRESENCE_ALREADY_EXISTS");
            problem.setProperty("timestamp", Instant.now());
            return problem;
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Conflito de integridade de dados");
        problem.setTitle("Regra de Negócio Violada");
        problem.setType(URI.create("https://api.rewit.app/errors/data_integrity_conflict"));
        problem.setProperty("code", "DATA_INTEGRITY_CONFLICT");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Parâmetros da requisição inválidos");
        problem.setTitle("Erro de Validação de Dados");
        problem.setType(URI.create("https://api.rewit.app/errors/validation-error"));

        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.put(error.getField(), error.getDefaultMessage())
        );
        problem.setProperty("fieldErrors", fieldErrors);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ProblemDetail handleHttpMessageNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Corpo da requisição inválido ou mal formatado");
        problem.setTitle("Requisição Inválida");
        problem.setType(URI.create("https://api.rewit.app/errors/malformed-request"));
        problem.setProperty("code", "MALFORMED_REQUEST");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneralException(Exception ex) throws Exception {
        // Deixa o Spring Security tratar suas próprias exceções (403/401)
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex;
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Ocorreu um erro interno inesperado no servidor.");
        problem.setTitle("Erro Interno do Servidor");
        problem.setType(URI.create("https://api.rewit.app/errors/internal-server-error"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
