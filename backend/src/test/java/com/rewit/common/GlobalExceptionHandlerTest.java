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
        BusinessException ex = new BusinessException("Nota fora do intervalo permitido", HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RATING_RANGE");

        ProblemDetail problem = handler.handleBusinessException(ex);

        assertNotNull(problem);
        assertEquals(422, problem.getStatus());
        assertEquals("Nota fora do intervalo permitido", problem.getDetail());
        assertEquals("Regra de Negócio Violada", problem.getTitle());
        assertEquals("INVALID_RATING_RANGE", problem.getProperties().get("code"));
        assertNotNull(problem.getProperties().get("timestamp"));
    }
}
