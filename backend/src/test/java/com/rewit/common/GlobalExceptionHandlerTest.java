package com.rewit.common;

import com.rewit.common.exception.BusinessException;
import com.rewit.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes do Tratamento Global de Exceções (RFC 7807)")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("BusinessException deve gerar ProblemDetail formatado segundo RFC 7807")
    void shouldFormatBusinessExceptionProblemDetail() {
        BusinessException ex = new BusinessException("Nota fora do intervalo permitido", HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_RATING_RANGE");

        ProblemDetail problem = handler.handleBusinessException(ex);

        assertNotNull(problem);
        assertEquals(422, problem.getStatus());
        assertEquals("Nota fora do intervalo permitido", problem.getDetail());
        assertEquals("Regra de Negócio Violada", problem.getTitle());
        assertEquals("INVALID_RATING_RANGE", problem.getProperties().get("code"));
        assertNotNull(problem.getProperties().get("timestamp"));
    }

    @Test
    @DisplayName("DataIntegrityViolationException com uq_profiles_handle_lower deve gerar 409 HANDLE_ALREADY_EXISTS")
    void shouldFormatDataIntegrityViolationForHandleProblemDetail() {
        org.springframework.dao.DataIntegrityViolationException ex = new org.springframework.dao.DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("ERROR: duplicate key value violates unique constraint \"uq_profiles_handle_lower\"")
        );

        ProblemDetail problem = handler.handleDataIntegrityViolation(ex);

        assertNotNull(problem);
        assertEquals(409, problem.getStatus());
        assertEquals("Nome de usuário (@handle) já está em uso", problem.getDetail());
        assertEquals("HANDLE_ALREADY_EXISTS", problem.getProperties().get("code"));
        assertNotNull(problem.getProperties().get("timestamp"));
    }

    @Test
    @DisplayName("DataIntegrityViolationException com uq_users_email_lower deve gerar 409 EMAIL_ALREADY_EXISTS")
    void shouldFormatDataIntegrityViolationForEmailProblemDetail() {
        org.springframework.dao.DataIntegrityViolationException ex = new org.springframework.dao.DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("ERROR: duplicate key value violates unique constraint \"uq_users_email_lower\"")
        );

        ProblemDetail problem = handler.handleDataIntegrityViolation(ex);

        assertNotNull(problem);
        assertEquals(409, problem.getStatus());
        assertEquals("E-mail já cadastrado na plataforma", problem.getDetail());
        assertEquals("EMAIL_ALREADY_EXISTS", problem.getProperties().get("code"));
        assertNotNull(problem.getProperties().get("timestamp"));
    }

    @Test
    @DisplayName("DataIntegrityViolationException com constraint genérica deve gerar 409 DATA_INTEGRITY_CONFLICT")
    void shouldFormatGenericDataIntegrityViolationProblemDetail() {
        org.springframework.dao.DataIntegrityViolationException ex = new org.springframework.dao.DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("ERROR: insert or update on table \"profiles\" violates foreign key constraint \"fk_other\"")
        );

        ProblemDetail problem = handler.handleDataIntegrityViolation(ex);

        assertNotNull(problem);
        assertEquals(409, problem.getStatus());
        assertEquals("Conflito de integridade de dados", problem.getDetail());
        assertEquals("DATA_INTEGRITY_CONFLICT", problem.getProperties().get("code"));
        assertNotNull(problem.getProperties().get("timestamp"));
    }
}
