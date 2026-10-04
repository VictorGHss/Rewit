package com.rewit.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rewit.common.exception.BusinessException;
import com.rewit.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

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

    @Test
    @DisplayName("NoResourceFoundException deve gerar 404 RESOURCE_NOT_FOUND, no mesmo formato dos demais erros, sem log")
    void shouldMapNoResourceFoundTo404WithoutLogging() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/actuator/prometheus", "actuator/prometheus");

        List<ILoggingEvent> logs = captureLogs(() -> {
            ProblemDetail problem = handler.handleNoResourceFound(ex);

            assertEquals(404, problem.getStatus());
            assertEquals("Recurso não encontrado", problem.getDetail());
            assertEquals("Recurso Não Encontrado", problem.getTitle());
            assertEquals("https://api.rewit.app/errors/resource-not-found", String.valueOf(problem.getType()));
            assertEquals("RESOURCE_NOT_FOUND", problem.getProperties().get("code"));
            assertNotNull(problem.getProperties().get("timestamp"));
            assertFalse(String.valueOf(problem.getDetail()).contains("actuator"), "o path pedido não é devolvido");
        });

        assertTrue(logs.isEmpty(), "rota inexistente é erro do cliente: nenhum log");
    }

    @Test
    @DisplayName("Exceção inesperada continua gerando 500 e um log ERROR, no formato de erro existente")
    void shouldKeepUnexpectedExceptionAs500WithErrorLog() {
        List<ILoggingEvent> logs = captureLogs(() -> {
            try {
                ProblemDetail problem = handler.handleGeneralException(new IllegalStateException("falha interna"));

                assertEquals(500, problem.getStatus());
                assertEquals("Ocorreu um erro interno inesperado no servidor.", problem.getDetail());
                assertEquals("Erro Interno do Servidor", problem.getTitle());
                assertEquals("https://api.rewit.app/errors/internal-server-error", String.valueOf(problem.getType()));
                assertNotNull(problem.getProperties().get("timestamp"));
            } catch (Exception e) {
                fail("o handler genérico não deve relançar exceção comum");
            }
        });

        assertEquals(1, logs.size());
        assertEquals(Level.ERROR, logs.getFirst().getLevel());
        assertTrue(logs.getFirst().getFormattedMessage().contains("java.lang.IllegalStateException"));
    }

    private List<ILoggingEvent> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }
}
